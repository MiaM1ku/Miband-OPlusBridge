// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DeviceLogMassGateTest {
    @Test public void inFlightMassStreamDoesNotPoisonPendingExportAndIsRateLimited() throws Exception {
        File dir = Files.createTempDirectory("mass-gate-test").toFile();
        List<String> logs = new ArrayList<>();
        AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(10));
        var gate = new SppDiagnosticClient.DeviceLogMassGate(logs::add, clock::get);
        byte[] stray = midStreamPacket(400, 120);
        XiaomiMassReceive receiver = new XiaomiMassReceive(dir, null);
        try {
            // Unsolicited/prior MASS packets before any export is requested are rate-limited.
            for (int i = 0; i < 50; i++) {
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(30));
                gate.onMass(stray);
            }
            assertEquals(1, logs.size());
            assertTrue(logs.get(0), logs.get(0).startsWith("rx mass ignored reason=no-receiver "));
            assertTrue(logs.get(0), logs.get(0).contains("part=120/400"));

            // Export starts while the prior MASS stream is still arriving.
            gate.begin(receiver);
            for (int i = 0; i < 10; i++) {
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(30));
                gate.onMass(stray);
            }
            assertFalse("Mid-stream packets before 2/81 must not fail the receiver",
                    receiver.completion().toCompletableFuture().isDone());

            // Watch answers 2/81 SUCCESS; even one leftover tail packet before part 1 is tolerated.
            gate.onCommand(reportResult(XiaomiProto.ReportData.Status.SUCCESS));
            gate.onMass(midStreamPacket(400, 400));
            assertFalse(receiver.completion().toCompletableFuture().isDone());

            // Real device log starts at part 1 and completes cleanly.
            for (byte[] packet : completeFile("device-log.tar.gz", "watch-logs-ok")) {
                gate.onMass(packet);
            }
            XiaomiMassReceive.Result result = receiver.await(2, TimeUnit.SECONDS);
            assertEquals("device-log.tar.gz", result.fileName());
            assertArrayEquals("watch-logs-ok".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(result.file().toPath()));
            assertTrue(logs.stream().anyMatch(line -> line.startsWith("DEVICE_LOG_EXPORT stream start ")));
        } finally {
            receiver.close();
            for (File file : dir.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }

    @Test public void identityCheckedCancelDoesNotClobberNewerReceiver() throws Exception {
        File dir = Files.createTempDirectory("mass-gate-cancel-test").toFile();
        List<String> logs = new ArrayList<>();
        var gate = new SppDiagnosticClient.DeviceLogMassGate(logs::add, System::nanoTime);
        XiaomiMassReceive first = new XiaomiMassReceive(dir, null);
        XiaomiMassReceive second = new XiaomiMassReceive(dir, null);
        try {
            gate.begin(first);
            gate.cancel(first, "USER_SKIPPED");
            assertTrue(first.completion().toCompletableFuture().isCompletedExceptionally());

            gate.begin(second);
            // Late cleanup from first export must not cancel second.
            gate.cancel(first, "DEVICE_LOG_EXPORT_FAILED");
            assertFalse(second.completion().toCompletableFuture().isDone());

            gate.onCommand(reportResult(XiaomiProto.ReportData.Status.SUCCESS));
            for (byte[] packet : completeFile("second.tar.gz", "fresh")) {
                gate.onMass(packet);
            }
            XiaomiMassReceive.Result result = second.await(2, TimeUnit.SECONDS);
            assertEquals("second.tar.gz", result.fileName());
        } finally {
            first.close();
            second.close();
            for (File file : dir.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }

    @Test public void acceptsValidPartOneEvenIfItRacesAheadOfReportResult() throws Exception {
        File dir = Files.createTempDirectory("mass-gate-race-test").toFile();
        List<String> logs = new ArrayList<>();
        var gate = new SppDiagnosticClient.DeviceLogMassGate(logs::add, System::nanoTime);
        XiaomiMassReceive receiver = new XiaomiMassReceive(dir, null);
        try {
            gate.begin(receiver);
            List<byte[]> packets = completeFile("early.tar.gz", "hello-early");
            gate.onMass(packets.get(0));
            gate.onCommand(reportResult(XiaomiProto.ReportData.Status.SUCCESS));
            for (int i = 1; i < packets.size(); i++) gate.onMass(packets.get(i));
            XiaomiMassReceive.Result result = receiver.await(2, TimeUnit.SECONDS);
            assertEquals("early.tar.gz", result.fileName());
            assertArrayEquals("hello-early".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(result.file().toPath()));
        } finally {
            receiver.close();
            for (File file : dir.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }

    @Test public void completesWhenMassStreamsWithoutAnyReportResult() throws Exception {
        File dir = Files.createTempDirectory("mass-gate-no281-test").toFile();
        List<String> logs = new ArrayList<>();
        var gate = new SppDiagnosticClient.DeviceLogMassGate(logs::add, System::nanoTime);
        XiaomiMassReceive receiver = new XiaomiMassReceive(dir, null);
        try {
            gate.begin(receiver);
            for (byte[] packet : completeFile("direct.tar.gz", "no-281-needed")) {
                gate.onMass(packet);
            }
            XiaomiMassReceive.Result result = receiver.await(2, TimeUnit.SECONDS);
            assertEquals("direct.tar.gz", result.fileName());
            assertArrayEquals("no-281-needed".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(result.file().toPath()));
        } finally {
            receiver.close();
            for (File file : dir.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }

    @Test public void nonZeroReportResultAndProtocolRejectionFailReceiverImmediately() throws Exception {
        File dir = Files.createTempDirectory("mass-gate-reject-test").toFile();
        List<String> logs = new ArrayList<>();
        var gate = new SppDiagnosticClient.DeviceLogMassGate(logs::add, System::nanoTime);
        XiaomiMassReceive first = new XiaomiMassReceive(dir, null);
        XiaomiMassReceive second = new XiaomiMassReceive(dir, null);
        try {
            gate.begin(first);
            gate.onCommand(reportResult(XiaomiProto.ReportData.Status.BUSY));
            assertTrue(first.completion().toCompletableFuture().isCompletedExceptionally());
            assertEquals("DEVICE_LOG_DEVICE_BUSY", first.failureCode());

            gate.begin(second);
            gate.onCommand(XiaomiProto.Command.newBuilder().setType(2).setSubtype(80).setStatus(2).build());
            assertTrue(second.completion().toCompletableFuture().isCompletedExceptionally());
            assertEquals("DEVICE_LOG_DEVICE_REJECTED", second.failureCode());
        } finally {
            first.close();
            second.close();
            for (File file : dir.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }

    private static XiaomiProto.Command reportResult(XiaomiProto.ReportData.Status status) {
        return XiaomiProto.Command.newBuilder().setType(2).setSubtype(81)
                .setSystem(XiaomiProto.System.newBuilder().setReportDataResult(
                        XiaomiProto.ReportData.Result.newBuilder()
                                .setType(XiaomiProto.ReportData.Type.DEVICE_LOG)
                                .setStatus(status)))
                .build();
    }

    private static byte[] midStreamPacket(int total, int current) {
        byte[] packet = new byte[4096];
        packet[0] = 0;
        packet[1] = 0;
        packet[2] = (byte) (total & 0xff);
        packet[3] = (byte) ((total >>> 8) & 0xff);
        packet[4] = (byte) (current & 0xff);
        packet[5] = (byte) ((current >>> 8) & 0xff);
        return packet;
    }

    private static List<byte[]> completeFile(String name, String text) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        meta.write(nameBytes.length);
        meta.writeBytes(nameBytes);
        meta.writeBytes(new byte[]{1, 2, 3, 4, 5});
        byte[] metadata = meta.toByteArray();
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int split = payload.length / 2;
        byte[] part1Data = java.util.Arrays.copyOfRange(payload, 0, split);
        byte[] part2Data = java.util.Arrays.copyOfRange(payload, split, payload.length);
        CRC32 crc = new CRC32();
        crc.update(metadata);
        crc.update(part1Data);
        crc.update(part2Data);
        int checksum = (int) crc.getValue();

        ByteArrayOutputStream p1 = new ByteArrayOutputStream();
        p1.write(0); p1.write(0);
        p1.write(2); p1.write(0);
        p1.write(1); p1.write(0);
        p1.writeBytes(metadata);
        p1.writeBytes(part1Data);

        ByteArrayOutputStream p2 = new ByteArrayOutputStream();
        p2.write(0); p2.write(0);
        p2.write(2); p2.write(0);
        p2.write(2); p2.write(0);
        p2.writeBytes(part2Data);
        p2.write(checksum & 0xff);
        p2.write((checksum >>> 8) & 0xff);
        p2.write((checksum >>> 16) & 0xff);
        p2.write((checksum >>> 24) & 0xff);
        return List.of(p1.toByteArray(), p2.toByteArray());
    }
}
