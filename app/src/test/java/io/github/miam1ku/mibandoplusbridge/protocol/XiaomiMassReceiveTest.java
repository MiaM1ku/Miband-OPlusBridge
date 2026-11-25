// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class XiaomiMassReceiveTest {
    @Test public void assemblesAndChecksCompleteReverseMassFile() throws Exception {
        File dir = Files.createTempDirectory("mass-log-test").toFile();
        byte[] first = "hello ".getBytes(StandardCharsets.UTF_8);
        byte[] second = "world\n".getBytes(StandardCharsets.UTF_8);
        byte[] metadata = metadata("device-log.tar.gz");
        CRC32 crc = new CRC32();
        crc.update(metadata);
        crc.update(first);
        crc.update(second);
        List<byte[]> packets = packets("device-log.tar.gz", metadata,
                List.of(first, second), (int) crc.getValue());
        List<XiaomiMassReceive.Progress> progress = new ArrayList<>();
        XiaomiMassReceive receiver = new XiaomiMassReceive(dir, progress::add);
        try {
            packets.forEach(receiver::accept);
            XiaomiMassReceive.Result result = receiver.await(2, TimeUnit.SECONDS);
            assertEquals("device-log.tar.gz", result.fileName());
            assertArrayEquals("hello world\n".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(result.file().toPath()));
            assertEquals(2, progress.size());
            assertEquals(2, progress.get(1).currentPart());
            assertEquals(2, progress.get(1).totalParts());
        } finally {
            receiver.close();
            for (File file : dir.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }

    @Test public void rejectsChecksumMismatch() throws Exception {
        File dir = Files.createTempDirectory("mass-crc-test").toFile();
        byte[] metadata = metadata("device-log.bin");
        byte[] data = new byte[]{1, 2, 3};
        List<byte[]> packets = packets("device-log.bin", metadata, List.of(data), 0x12345678);
        XiaomiMassReceive receiver = new XiaomiMassReceive(dir, null);
        try {
            packets.forEach(receiver::accept);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> receiver.await(2, TimeUnit.SECONDS));
            assertEquals("MASS_CRC_MISMATCH", failure.getCause().getMessage());
            assertEquals("MASS_CRC_MISMATCH", receiver.failureCode());
        } finally {
            receiver.close();
            for (File file : dir.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }

    @Test public void skipsMidStreamTailPacketsBeforePartOneArrives() throws Exception {
        File dir = Files.createTempDirectory("mass-midstream-test").toFile();
        byte[] first = "part-one-".getBytes(StandardCharsets.UTF_8);
        byte[] second = "part-two".getBytes(StandardCharsets.UTF_8);
        byte[] metadata = metadata("device-log.tar.gz");
        CRC32 crc = new CRC32();
        crc.update(metadata);
        crc.update(first);
        crc.update(second);
        List<byte[]> packets = packets("device-log.tar.gz", metadata,
                List.of(first, second), (int) crc.getValue());
        byte[] strayTail = new byte[64];
        strayTail[0] = 0;
        strayTail[2] = 10; // total = 10
        strayTail[4] = 7;  // current = 7 (mid-stream from prior transfer)
        XiaomiMassReceive receiver = new XiaomiMassReceive(dir, null);
        try {
            receiver.accept(strayTail);
            assertEquals(1, receiver.skippedMidStreamCount());
            org.junit.Assert.assertFalse(receiver.hasStarted());
            org.junit.Assert.assertFalse(receiver.completion().toCompletableFuture().isDone());
            packets.forEach(receiver::accept);
            XiaomiMassReceive.Result result = receiver.await(2, TimeUnit.SECONDS);
            assertEquals("device-log.tar.gz", result.fileName());
            assertArrayEquals("part-one-part-two".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(result.file().toPath()));
        } finally {
            receiver.close();
            for (File file : dir.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }

    @Test public void multiPartFirstPacketDoesNotRequireCrcSpace() throws Exception {
        File dir = Files.createTempDirectory("mass-short-first-test").toFile();
        byte[] first = new byte[0];
        byte[] second = "ok".getBytes(StandardCharsets.UTF_8);
        byte[] metadata = metadata("a.gz");
        CRC32 crc = new CRC32();
        crc.update(metadata);
        crc.update(first);
        crc.update(second);
        List<byte[]> packets = packets("a.gz", metadata, List.of(first, second), (int) crc.getValue());
        org.junit.Assert.assertTrue(XiaomiMassReceive.isFirstPart(packets.get(0)));
        XiaomiMassReceive receiver = new XiaomiMassReceive(dir, null);
        try {
            packets.forEach(receiver::accept);
            XiaomiMassReceive.Result result = receiver.await(2, TimeUnit.SECONDS);
            assertEquals("a.gz", result.fileName());
            assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(result.file().toPath()));
        } finally {
            receiver.close();
            for (File file : dir.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }

    @Test public void acceptsCrcStraddlingFinalTwoPackets() throws Exception {
        File dir = Files.createTempDirectory("mass-split-crc-test").toFile();
        byte[] content = "split-crc-payload".getBytes(StandardCharsets.UTF_8);
        byte[] metadata = metadata("split.tar.gz");
        CRC32 crc = new CRC32();
        crc.update(metadata);
        crc.update(content);
        int checksum = (int) crc.getValue();

        ByteArrayOutputStream full = new ByteArrayOutputStream();
        full.writeBytes(metadata);
        full.writeBytes(content);
        full.write(checksum & 0xff);
        full.write((checksum >>> 8) & 0xff);
        full.write((checksum >>> 16) & 0xff);
        full.write((checksum >>> 24) & 0xff);
        byte[] stream = full.toByteArray();
        // Put only the last 2 bytes of CRC32 into packet 2.
        int cut = stream.length - 2;

        ByteArrayOutputStream p1 = new ByteArrayOutputStream();
        p1.write(0); p1.write(129); le16(p1, 2); le16(p1, 1);
        p1.write(stream, 0, cut);

        ByteArrayOutputStream p2 = new ByteArrayOutputStream();
        p2.write(0); p2.write(129); le16(p2, 2); le16(p2, 2);
        p2.write(stream, cut, stream.length - cut);

        org.junit.Assert.assertTrue(XiaomiMassReceive.isFirstPart(p1.toByteArray()));
        XiaomiMassReceive receiver = new XiaomiMassReceive(dir, null);
        try {
            receiver.accept(p1.toByteArray());
            receiver.accept(p2.toByteArray());
            XiaomiMassReceive.Result result = receiver.await(2, TimeUnit.SECONDS);
            assertEquals("split.tar.gz", result.fileName());
            assertArrayEquals(content, Files.readAllBytes(result.file().toPath()));
        } finally {
            receiver.close();
            for (File file : dir.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }

    @Test public void describePacketSummarizesHeaderWithoutPayloadLeak() {
        byte[] metadata = metadata("watch-log.tar.gz");
        List<byte[]> packets = packets("watch-log.tar.gz", metadata,
                List.of("SECRET_PAYLOAD".getBytes(StandardCharsets.UTF_8)), 0);
        String summary = XiaomiMassReceive.describePacket(packets.get(0));
        org.junit.Assert.assertTrue(summary, summary.contains("ver=0 flags=0 part=1/1"));
        org.junit.Assert.assertTrue(summary, summary.contains("name=watch-log.tar.gz"));
        org.junit.Assert.assertFalse(summary, summary.contains("SECRET_PAYLOAD"));
    }

    private static byte[] metadata(String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(bytes.length);
        out.writeBytes(bytes);
        out.writeBytes(new byte[]{1, 2, 3, 4, 5});
        return out.toByteArray();
    }

    private static List<byte[]> packets(String name, byte[] metadata,
            List<byte[]> fragments, int crc) {
        List<byte[]> packets = new ArrayList<>();
        for (int index = 0; index < fragments.size(); index++) {
            int current = index + 1;
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            packet.write(0);
            packet.write(0);
            le16(packet, fragments.size());
            le16(packet, current);
            if (index == 0) packet.writeBytes(metadata);
            packet.writeBytes(fragments.get(index));
            if (current == fragments.size()) {
                packet.write(crc & 0xff);
                packet.write((crc >>> 8) & 0xff);
                packet.write((crc >>> 16) & 0xff);
                packet.write((crc >>> 24) & 0xff);
            }
            packets.add(packet.toByteArray());
        }
        return packets;
    }

    private static void le16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }
}
