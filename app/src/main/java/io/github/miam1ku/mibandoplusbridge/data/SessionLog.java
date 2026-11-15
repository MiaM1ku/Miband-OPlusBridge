// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Local session trace. A line carries the event name. Tokens, MAC addresses, nonces, and ciphertext stay in the caller. */
public final class SessionLog {
    static final String START_MARK = "--- module start ";
    static final int KEPT_STARTS = 3;
    private static final long MAX_BYTES = 256 * 1024;
    private static final Object LOCK = new Object();
    private static boolean started;

    private SessionLog() {}

    /** One marker per process. Older module starts beyond the newest three are deleted. */
    public static void start(Context context) {
        if (context == null || started) return;
        started = true;
        synchronized (LOCK) {
            try {
                File file = logFile(context);
                String existing = file.isFile()
                        ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
                if (!existing.isBlank() && !existing.contains(START_MARK)) {
                    existing = START_MARK + "legacy ---\n" + existing;
                }
                String next = retain(existing + START_MARK + Instant.now() + " ---\n");
                Files.write(file.toPath(), next.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        }
    }

    /** Drops MAC-shaped addresses and control characters before a line is stored. */
    public static String redact(String value) {
        if (value == null || value.isBlank()) return "";
        String clean = value.replaceAll("(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}", "mac")
                .replaceAll("[\\p{Cntrl}]+", " ").trim();
        return clean.length() > 160 ? clean.substring(0, 160) : clean;
    }

    public static void line(Context context, String message) {
        if (context == null || message == null || message.isBlank()) return;
        String row = Instant.now() + " " + message.replace('\n', ' ').replace('\r', ' ') + "\n";
        byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            try {
                File file = logFile(context);
                if (file.length() + bytes.length > MAX_BYTES && file.isFile()) {
                    String kept = retain(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) + row);
                    try (FileOutputStream out = new FileOutputStream(file, false)) {
                        out.write(kept.getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                }
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(bytes);
                }
            } catch (IOException ignored) { }
        }
    }

    private static final String BUNDLE = "band-debug";
    private static final int CAPTURE_FILES = 3;
    private static final long CAPTURE_BYTES = 24L * 1024 * 1024;

    /**
     * One archive for analysis: the session trace, the checked transport profile, and the
     * newest protocol captures. The captures are the band frames the debug window recorded.
     */
    public static File export(Context context) throws IOException {
        File dir = new File(context.getCacheDir(), "debug");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("DEBUG_LOG_UNAVAILABLE");
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                .withZone(ZoneId.systemDefault()).format(Instant.now());
        File out = new File(dir, BUNDLE + "-" + stamp + ".zip");
        StringBuilder text = new StringBuilder();
        text.append("miband-oplusbridge debug\n");
        File live = new File(context.getNoBackupFilesDir(), "live-status.txt");
        if (live.isFile() && live.length() < 512) {
            text.append("live-status ").append(new String(Files.readAllBytes(live.toPath()), StandardCharsets.UTF_8).trim()).append('\n');
        }
        File log = logFile(context);
        if (log.isFile()) text.append(new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8));
        byte[] trace = retain(text.toString()).getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            put(zip, "band-debug.txt", trace);
            put(zip, "transport-observation.json",
                    transport(context).getBytes(StandardCharsets.UTF_8));
            put(zip, "README.txt", readme().getBytes(StandardCharsets.UTF_8));
            for (File capture : pickCaptures(captureFiles(context), CAPTURE_FILES, CAPTURE_BYTES)) {
                put(zip, "captures/" + capture.getName(), capture);
            }
        } catch (IOException failed) {
            out.delete();
            throw failed;
        }
        File[] stale = dir.listFiles((parent, name) -> name.startsWith(BUNDLE + "-") && name.endsWith(".zip"));
        if (stale != null) {
            for (File bundle : stale) {
                if (!bundle.equals(out)) bundle.delete();
            }
        }
        return out;
    }

    private static String transport(Context context) {
        org.json.JSONObject observation = TransportObservation.read(context);
        try {
            return observation.toString(2);
        } catch (org.json.JSONException invalid) {
            return observation.toString();
        }
    }

    /** Newest captures first, bounded by both count and total bytes. */
    static List<File> pickCaptures(File[] files, int maxFiles, long maxBytes) {
        if (files == null || files.length == 0 || maxFiles < 1 || maxBytes <= 0) return List.of();
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort(Comparator.comparingLong(File::lastModified).reversed());
        List<File> picked = new ArrayList<>();
        long total = 0;
        for (File file : sorted) {
            if (picked.size() >= maxFiles) break;
            long size = file.length();
            if (size <= 0 || total + size > maxBytes) continue;
            picked.add(file);
            total += size;
        }
        return picked;
    }

    private static File[] captureFiles(Context context) {
        File directory = new File(context.getNoBackupFilesDir(), "protocol-captures");
        return directory.listFiles((parent, name) -> name.startsWith("capture-") && name.endsWith(".jsonl"));
    }

    private static String readme() {
        return "miband-oplusbridge 调试包\n"
                + "band-debug.txt：会话日志（已去除 MAC 与控制字符）\n"
                + "transport-observation.json：最近一次连接的传输参数（SPP/GATT、鉴权分支、RFCOMM）\n"
                + "captures/*.jsonl：协议消息采集，一行一条，payloadBase64 是原始帧；\n"
                + "  tx/rx 是方向，capturedAtMs/elapsedRealtimeNs 是宿主时间，receivedAtMs 是模块落盘时间。\n";
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(System.currentTimeMillis());
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static void put(ZipOutputStream zip, String name, File source) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(source.lastModified());
        zip.putNextEntry(entry);
        try (InputStream in = new FileInputStream(source)) {
            in.transferTo(zip);
        }
        zip.closeEntry();
    }

    /** Keeps the newest {@code keep} module starts. A single start that is still too long is cut from the tail. */
    static String retain(String text) {
        String kept = keepLastStarts(text, KEPT_STARTS);
        byte[] raw = kept.getBytes(StandardCharsets.UTF_8);
        while (raw.length > MAX_BYTES && countStarts(kept) > 1) {
            kept = keepLastStarts(kept, countStarts(kept) - 1);
            raw = kept.getBytes(StandardCharsets.UTF_8);
        }
        if (raw.length <= MAX_BYTES) return kept;
        int from = raw.length - (int) MAX_BYTES;
        String tail = new String(raw, from, raw.length - from, StandardCharsets.UTF_8);
        int newline = tail.indexOf('\n');
        if (newline >= 0 && newline + 1 < tail.length()) tail = tail.substring(newline + 1);
        int marker = kept.indexOf(START_MARK);
        if (marker >= 0 && !tail.startsWith(START_MARK)) {
            int end = kept.indexOf('\n', marker);
            String head = end < 0 ? kept.substring(marker) : kept.substring(marker, end + 1);
            return head + "… earlier lines trimmed\n" + tail;
        }
        return "… earlier lines trimmed\n" + tail;
    }

    static String keepLastStarts(String text, int keep) {
        if (text == null || text.isEmpty() || keep < 1) return "";
        int count = countStarts(text);
        if (count <= keep) return text;
        int at = 0;
        for (int i = 0; i <= count - keep; i++) {
            at = text.indexOf(START_MARK, at);
            if (i < count - keep) at += START_MARK.length();
        }
        return at < 0 ? text : text.substring(at);
    }

    private static int countStarts(String text) {
        int count = 0;
        for (int from = 0; (from = text.indexOf(START_MARK, from)) >= 0; from += START_MARK.length()) count++;
        return count;
    }

    private static File logFile(Context context) {
        return new File(context.getNoBackupFilesDir(), "session-log.txt");
    }
}
