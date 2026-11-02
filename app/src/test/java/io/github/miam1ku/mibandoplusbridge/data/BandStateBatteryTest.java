// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import static org.junit.Assert.*;
import io.github.miam1ku.mibandoplusbridge.protocol.BandBatteryState;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;

public class BandStateBatteryTest {
    @Test public void unknownStatusClearsStaleChargingWhileUpdatingBattery() throws Exception {
        for (Integer status : new Integer[] {null, 0, 9}) {
            JSONObject stored = persist(new JSONObject().put("charging", true).put("chargerState", 1)
                    .put("battery", 10).put("connected", true), new BandBatteryState(72, status));
            assertEquals(72, stored.getInt("battery"));
            assertTrue(stored.getBoolean("connected"));
            assertFalse(stored.has("charging"));
            assertFalse(stored.has("chargerState"));
        }
    }

    @Test public void knownStatusPersistsChargingAndFullOrIdle() throws Exception {
        for (int status = 1; status <= 3; status++) {
            JSONObject stored = persist(new JSONObject(), new BandBatteryState(100, status));
            assertEquals(status, stored.getInt("chargerState"));
            assertEquals(status == 1, stored.getBoolean("charging"));
            assertEquals(100, stored.getInt("battery"));
        }
    }

    private static JSONObject persist(JSONObject initial, BandBatteryState battery) throws Exception {
        File file = Files.createTempFile("band-battery", ".json").toFile();
        try {
            var constructor = LocalPrefs.class.getDeclaredConstructor(File.class, JSONObject.class);
            constructor.setAccessible(true);
            LocalPrefs prefs = constructor.newInstance(file, initial);
            var editor = prefs.edit();
            BandStateRepository.writeBattery(editor, battery);
            assertTrue(editor.commit());
            return LocalPrefs.readStored(file).json;
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }
}
