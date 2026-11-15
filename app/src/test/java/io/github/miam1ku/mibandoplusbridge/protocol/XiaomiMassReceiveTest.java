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
        } finally {
            receiver.close();
            for (File file : dir.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
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
