// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DexAnchorsTest {
    @Test public void classNamesComeFromDexBytesNotTheLinker() throws Exception {
        Path apk = Files.createTempFile("dex-anchors", ".apk");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk))) {
                byte[] compact = dex("Lcdex/ShouldSkip;");
                compact[0] = 'c';
                put(zip, "classes.dex", compact);
                put(zip, "classes2.dex", dex(
                        "Laa/Nw;",
                        "Laa/Nw$a;",
                        "Lcom/heytap/health/device/tab/itemview/wearable/MenuSleepItem;"));
                put(zip, "assets/classes.dex", dex("Lignored/NotADexEntry;"));
            }
            List<String> names = DexAnchors.classNames(apk.toString());
            assertEquals(List.of(
                    "aa.Nw",
                    "aa.Nw$a",
                    "com.heytap.health.device.tab.itemview.wearable.MenuSleepItem"), names);
            assertFalse(names.contains("cdex.ShouldSkip"));
            assertFalse(names.contains("ignored.NotADexEntry"));
        } finally {
            Files.deleteIfExists(apk);
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] dex(String... descriptors) {
        int count = descriptors.length;
        int stringIdsOff = 0x70;
        int typeIdsOff = stringIdsOff + count * 4;
        int classDefsOff = typeIdsOff + count * 4;
        int dataOff = classDefsOff + count * 32;
        byte[][] items = new byte[count][];
        int[] at = new int[count];
        int cursor = dataOff;
        for (int i = 0; i < count; i++) {
            byte[] utf = descriptors[i].getBytes(StandardCharsets.UTF_8);
            if (utf.length >= 128) throw new IllegalArgumentException(descriptors[i]);
            byte[] item = new byte[utf.length + 2];
            item[0] = (byte) utf.length;
            System.arraycopy(utf, 0, item, 1, utf.length);
            items[i] = item;
            at[i] = cursor;
            cursor += item.length;
        }
        byte[] dex = new byte[cursor];
        dex[0] = 'd';
        dex[1] = 'e';
        dex[2] = 'x';
        dex[3] = '\n';
        ByteBuffer buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(56, count);
        buf.putInt(60, stringIdsOff);
        buf.putInt(64, count);
        buf.putInt(68, typeIdsOff);
        buf.putInt(96, count);
        buf.putInt(100, classDefsOff);
        for (int i = 0; i < count; i++) {
            buf.putInt(stringIdsOff + i * 4, at[i]);
            buf.putInt(typeIdsOff + i * 4, i);
            buf.putInt(classDefsOff + i * 32, i);
            System.arraycopy(items[i], 0, dex, at[i], items[i].length);
        }
        return dex;
    }
}
