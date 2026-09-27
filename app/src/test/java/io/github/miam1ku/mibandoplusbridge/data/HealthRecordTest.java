// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HealthRecordTest {
    @Test public void metricBoundsNeverTurnMissingValuesIntoZeroHeartOrOxygen() {
        for (String kind : new String[] {"heart_rate", "spo2", "stress"}) {
            int min = kind.equals("stress") ? 0 : 1;
            int max = kind.equals("heart_rate") ? 250 : 100;
            for (Number invalid : new Number[] {null, min - 1, max + 1, 1.5, Double.NaN}) {
                assertThrows(IllegalArgumentException.class,
                        () -> metric(kind, invalid, "continuous", 60_000));
            }
            assertEquals(min, metric(kind, min, "continuous", 60_000).value.intValue());
            assertEquals(max, metric(kind, max, "manual", 1).value.intValue());
            assertThrows(IllegalArgumentException.class, () -> metric(kind, min, "manual", 60_000));
        }
        HealthRecord quietMinute = new HealthRecord("steps", "stable", "steps_interval",
                60_000, 120_000, 0, null, 1, "+05:30", "continuous", false);
        assertEquals(0, quietMinute.value.intValue());
    }
    @Test public void hostStoreRejectsParserValuesItWillNotKeep() {
        assertFalse(metric("stress", 0, "continuous", 60_000).hostAccepts());
        assertTrue(metric("stress", 1, "continuous", 60_000).hostAccepts());
        assertFalse(metric("heart_rate", 39, "continuous", 60_000).hostAccepts());
        assertTrue(metric("heart_rate", 40, "continuous", 60_000).hostAccepts());
        assertFalse(metric("spo2", 59, "manual", 1).hostAccepts());
        assertTrue(metric("spo2", 60, "manual", 1).hostAccepts());
    }


    @Test public void sleepIntervalIsNotATotalAndOnlyKnownStagesAreAccepted() {
        HealthRecord sleep = new HealthRecord("sleep", "stable", "sleep_interval", 0, 60_000,
                null, null, 1, "+05:30", "sleep", true);
        assertNull(sleep.value);
        assertTrue(sleep.complete);
        assertThrows(IllegalArgumentException.class,
                () -> new HealthRecord("sleep", "stable", "sleep_interval", 0, 60_000,
                        60_001, null, 1, null, "sleep", true));
        for (Integer stage : new Integer[] {null, -1, 0, 1, 6}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new HealthRecord("sleep", "stable", "sleep_stage", 0, 60_000,
                            null, stage, 1, null, "sleep", false));
        }
        for (int stage = 2; stage <= 5; stage++) {
            assertEquals(stage, new HealthRecord("sleep", "stable", "sleep_stage", 0, 60_000,
                    null, stage, 1, null, "continuous", false).stage.intValue());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new HealthRecord("steps", "stable", "steps_day", 0, 86_400_000,
                        10, null, 0, null, "continuous", false));
    }

    @Test public void serializationRequiresNewModeAndBooleanCompleteness() throws Exception {
        HealthRecord original = metric("spo2", 98, "manual", 1);
        HealthRecord decoded = HealthRecord.fromJson(original.toJson());
        assertEquals("manual", decoded.measurementMode);
        assertEquals(98, decoded.value.intValue());
        assertFalse(decoded.complete);
        JSONObject legacy = original.toJson(); legacy.remove("measurementMode");
        assertThrows(org.json.JSONException.class, () -> HealthRecord.fromJson(legacy));
        JSONObject missingComplete = original.toJson(); missingComplete.remove("complete");
        assertThrows(org.json.JSONException.class, () -> HealthRecord.fromJson(missingComplete));
        JSONObject textBoolean = original.toJson(); textBoolean.put("complete", "false");
        assertThrows(IllegalArgumentException.class, () -> HealthRecord.fromJson(textBoolean));
        JSONObject unknown = original.toJson(); unknown.put("derivedSleepScore", 99);
        assertThrows(IllegalArgumentException.class, () -> HealthRecord.fromJson(unknown));
        JSONObject falseComplete = original.toJson(); falseComplete.put("complete", true);
        assertThrows(IllegalArgumentException.class, () -> HealthRecord.fromJson(falseComplete));
    }

    private static HealthRecord metric(String kind, Number value, String mode, long duration) {
        return new HealthRecord("metric", "stable", kind, 60_000, 60_000 + duration,
                value, null, 1, "+05:30", mode, false);
    }
}
