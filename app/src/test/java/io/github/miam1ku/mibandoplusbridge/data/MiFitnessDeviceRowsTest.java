// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class MiFitnessDeviceRowsTest {
    private static final String KEY = "00112233445566778899aabbccddeeff";

    @Test public void prefersEncryptKeyAndNormalizesMac() {
        List<MiFitnessDeviceRows.Row> rows = MiFitnessDeviceRows.select(List.of(new MiFitnessDeviceRows.Detail(
                "miwear.watch.q66cn", "Band 11", "123", "did",
                "{\"encrypt_key\":\"" + KEY + "\",\"token\":\"ffffffffffffffffffffffffffffffff\","
                        + "\"mac\":\"aa-bb-cc-dd-ee-ff\",\"phone_id\":\"phone\"}")), "");
        assertEquals(1, rows.size());
        assertEquals(KEY, rows.get(0).token);
        assertEquals("AA:BB:CC:DD:EE:FF", rows.get(0).address);
        assertEquals("miwear.watch.q66cn", rows.get(0).model);
        assertEquals("phone", rows.get(0).phoneId);
    }

    @Test public void skipsRowsWithoutAKey() {
        List<MiFitnessDeviceRows.Row> rows = MiFitnessDeviceRows.select(List.of(
                new MiFitnessDeviceRows.Detail("mihlth.xiaomi.fitness.phone", "phone", "1", "", ""),
                new MiFitnessDeviceRows.Detail("miwear.watch.q66cn", "band", "2", "d", "{\"mac\":\"AA:BB:CC:DD:EE:FF\"}")
        ), "");
        assertTrue(rows.isEmpty());
    }

    @Test public void filtersToTheRequestedAddress() {
        MiFitnessDeviceRows.Detail first = band("AA:BB:CC:DD:EE:01");
        MiFitnessDeviceRows.Detail second = band("AA:BB:CC:DD:EE:02");
        assertEquals(2, MiFitnessDeviceRows.select(List.of(first, second), "").size());
        List<MiFitnessDeviceRows.Row> chosen = MiFitnessDeviceRows.select(List.of(first, second), "aa:bb:cc:dd:ee:02");
        assertEquals(1, chosen.size());
        assertEquals("AA:BB:CC:DD:EE:02", chosen.get(0).address);
    }

    private static MiFitnessDeviceRows.Detail band(String mac) {
        return new MiFitnessDeviceRows.Detail("miwear.watch.q66cn", "band", "9", "d",
                "{\"encrypt_key\":\"" + KEY + "\",\"mac\":\"" + mac + "\"}");
    }
}
