// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BandCatalogTest {
    @Test public void officialModelIdsMapToBandFamilyNames() {
        assertEquals("小米手环8", BandCatalog.displayName("miwear.watch.m66", ""));
        assertEquals("小米手环8 NFC", BandCatalog.displayName("miwear.watch.m66nfc", "ignored"));
        assertEquals("小米手环8 Pro", BandCatalog.displayName("lchz.watch.m67", "Xiaomi Smart Band 8 Pro ABCD"));
        assertEquals("小米手环8 活力版", BandCatalog.displayName("mijia.watch.m69bgl", null));
        assertEquals("小米手环9", BandCatalog.displayName("miwear.watch.n66cn", "Xiaomi Smart Band 9 F123"));
        assertEquals("小米手环9 NFC", BandCatalog.displayName("miwear.watch.n66nfc", ""));
        assertEquals("小米手环9 Pro", BandCatalog.displayName("miwear.watch.n67cn", ""));
        assertEquals("小米手环9 活力版", BandCatalog.displayName("miwear.watch.n69gl", ""));
        assertEquals("小米手环9 活力版", BandCatalog.displayName("miwear.watch.n69cn", ""));
        assertEquals("小米手环10", BandCatalog.displayName("miwear.watch.o66cn", ""));
        assertEquals("小米手环10 NFC", BandCatalog.displayName("miwear.watch.o66gln", ""));
        assertEquals("小米手环10 Pro", BandCatalog.displayName("miwear.watch.p67cn", "Xiaomi Smart Band 10 Pro 9C00"));
        assertEquals("小米手环11", BandCatalog.displayName("miwear.watch.q66cn", "Xiaomi Smart Band 11 F488"));
    }

    @Test public void bluetoothNamesIdentifySupportedBandsWithoutModel() {
        assertEquals("小米手环8", BandCatalog.displayName(null, "Xiaomi Smart Band 8 ABCD"));
        assertEquals("小米手环8 Pro", BandCatalog.displayName("", "Xiaomi Smart Band 8 Pro 12AF"));
        assertEquals("小米手环8 活力版", BandCatalog.displayName(null, "Xiaomi Band 8 Active 00A1"));
        assertEquals("小米手环9", BandCatalog.displayName(null, "Xiaomi Smart Band 9 F123"));
        assertEquals("小米手环10 Pro", BandCatalog.displayName(null, "Xiaomi Smart Band 10 Pro 9C00"));
        assertEquals("小米手环11", BandCatalog.displayName(null, "小米手环11"));
        assertTrue(BandCatalog.looksLikeBand("Xiaomi Smart Band 8 ABCD"));
        assertTrue(BandCatalog.looksLikeBand("Redmi Smart Band 3"));
        assertEquals("红米手环 Pro", BandCatalog.displayName(null, "Redmi Band Pro"));
        assertTrue(BandCatalog.looksLikeBand("Xiaomi Smart Band 10 1111"));
        assertFalse(BandCatalog.looksLikeBand("WH-1000XM5"));
        assertFalse(BandCatalog.looksLikeBand("OPPO Watch 2"));
        assertFalse(BandCatalog.looksLikeBand("miwear.watch.m66"));
    }

    @Test public void unknownSppModelsKeepRawNameAndAreNotRejected() {
        assertEquals("some custom name", BandCatalog.displayName("future.watch.z99", "some custom name"));
        assertEquals("future.watch.z99", BandCatalog.displayName("future.watch.z99", ""));
        assertEquals("小米手环", BandCatalog.displayName(null, null));
        assertEquals("小米手环", BandCatalog.displayName("", "   "));
    }
}
