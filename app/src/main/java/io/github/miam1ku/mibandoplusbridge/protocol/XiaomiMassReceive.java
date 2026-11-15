// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * Streaming receiver for Xiaomi's reverse MASS file format.
 *
 * <p>The first packet starts with {@code version, flags, total LE16, current LE16, nameLen},
 * followed by the UTF-8 name and a five-byte retained header. Every packet then carries its file
 * fragment. Only the final packet ends with the little-endian CRC32 of the retained header and
 * the complete file.
 */
public final class XiaomiMassReceive implements AutoCloseable {
    private static final int PACKET_HEADER = 6;
    private static final int RETAINED_HEADER = 5;
    private static final int CRC_BYTES = 4;
    private static final int MAX_NAME_BYTES = 180;
    private static final long MAX_FILE_BYTES = 512L * 1024 * 1024;

    public record Progress(int currentPart, int totalParts, String fileName, long bytes) {}
    public record Result(File file, String fileName, long bytes) {}

    private final File directory;
    private final Consumer<Progress> progress;
    private final CompletableFuture<Result> completion = new CompletableFuture<>();
    private final CRC32 crc = new CRC32();
    private File part;
    private OutputStream output;
    private String fileName = "";
    private int totalParts;
    private int currentPart;
    private long bytes;
    private boolean complete;
    private Progress latestProgress = new Progress(0, 0, "", 0);

    public XiaomiMassReceive(File directory, Consumer<Progress> progress) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.progress = progress == null ? ignored -> { } : progress;
    }

    public CompletionStage<Result> completion() {
        return completion;
    }

    /** Called by the transport reader in wire order, after MASS payload decryption. */
    public synchronized void accept(byte[] packet) {
        if (complete || completion.isCompletedExceptionally()) return;
        try {
            if (packet == null || packet.length < PACKET_HEADER + CRC_BYTES) {
                throw new IllegalArgumentException("MASS_PACKET_TOO_SHORT");
            }
            if (packet[0] != 0) throw new IllegalArgumentException("MASS_VERSION_UNSUPPORTED");
            int total = u16(packet, 2);
            int current = u16(packet, 4);
            if (total <= 0 || current <= 0 || current > total) {
                throw new IllegalArgumentException("MASS_PART_RANGE_INVALID");
            }

            int from;
            if (output == null) {
                if (current != 1) throw new IllegalArgumentException("MASS_FIRST_PART_MISSING");
                int nameLength = packet[6] & 0xff;
                int firstData = 7 + nameLength + RETAINED_HEADER;
                if (nameLength <= 0 || nameLength > MAX_NAME_BYTES
                        || firstData + CRC_BYTES > packet.length) {
                    throw new IllegalArgumentException("MASS_HEADER_INVALID");
                }
                fileName = safeFileName(new String(packet, 7, nameLength, StandardCharsets.UTF_8));
                totalParts = total;
                startFile(packet, 6, firstData);
                from = firstData;
                crc.update(packet, 6, firstData - 6);
            } else {
                if (total != totalParts) throw new IllegalArgumentException("MASS_TOTAL_CHANGED");
                if (current != currentPart + 1) {
                    throw new IllegalArgumentException("MASS_PART_OUT_OF_ORDER");
                }
                from = PACKET_HEADER;
            }

            int to = packet.length;
            if (current == totalParts) {
                if (to - from < CRC_BYTES) throw new IllegalArgumentException("MASS_FRAGMENT_MISSING");
                int expected = le32(packet, to - CRC_BYTES);
                to -= CRC_BYTES;
                output.write(packet, from, to - from);
                crc.update(packet, from, to - from);
                bytes += to - from;
                synchronized (this) {
                    if ((int) crc.getValue() != expected) {
                        throw new IllegalArgumentException("MASS_CRC_MISMATCH");
                    }
                    currentPart = current;
                    finishFile();
                    publishProgress(current, total);
                }
                return;
            }

            output.write(packet, from, to - from);
            crc.update(packet, from, to - from);
            bytes += to - from;
            currentPart = current;
            if (bytes > MAX_FILE_BYTES) throw new IOException("MASS_FILE_TOO_LARGE");
            publishProgress(current, total);
        } catch (Exception failure) {
            fail(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    private void startFile(byte[] packet, int headerFrom, int firstData) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("MASS_DIRECTORY_FAILED");
        }
        part = new File(directory, ".incoming-" + System.nanoTime() + ".part");
        output = new BufferedOutputStream(new FileOutputStream(part), 64 * 1024);
        if (firstData > packet.length) throw new IOException("MASS_HEADER_INVALID");
    }

    private void finishFile() throws IOException {
        if (output == null) return;
        output.flush();
        output.close();
        output = null;
        File destination = unique(directory, fileName);
        Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        part = null;
        complete = true;
        completion.complete(new Result(destination, fileName, bytes));
    }

    public synchronized void fail(String code) {
        if (complete || completion.isDone()) return;
        try {
            if (output != null) output.close();
        } catch (IOException ignored) { }
        output = null;
        if (part != null) part.delete();
        part = null;
        completion.completeExceptionally(new IllegalStateException(
                code == null || code.isBlank() ? "MASS_RECEIVE_FAILED" : code));
    }

    public synchronized Progress progressSnapshot() {
        return latestProgress;
    }

    public Result await(long timeout, TimeUnit unit) throws Exception {
        return completion.get(timeout, unit);
    }

    @Override public void close() {
        fail("MASS_RECEIVE_CANCELLED");
    }

    private static File unique(File directory, String name) {
        File candidate = new File(directory, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        for (int index = 2; ; index++) {
            candidate = new File(directory, stem + "-" + index + suffix);
            if (!candidate.exists()) return candidate;
        }
    }

    private static String safeFileName(String raw) {
        String clean = raw == null ? "" : raw.replace('\\', '/');
        clean = clean.substring(clean.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "").trim();
        if (clean.isBlank() || ".".equals(clean) || "..".equals(clean)) return "device-log.bin";
        return clean.length() > 120 ? clean.substring(clean.length() - 120) : clean;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static int le32(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private void publishProgress(int current, int total) {
        Progress snapshot = new Progress(current, total, fileName, bytes);
        latestProgress = snapshot;
        progress.accept(snapshot);
    }
}
