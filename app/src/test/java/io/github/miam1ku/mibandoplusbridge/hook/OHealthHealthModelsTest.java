// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class OHealthHealthModelsTest {
    @Test public void heartRateModesMapToTheTypesHealthDerivesItsCardsFrom() {
        assertEquals(0, OHealthHealthModels.Kind.HEART.type(record("heart_rate", "continuous", 60_000)));
        assertEquals(3, OHealthHealthModels.Kind.HEART.type(record("heart_rate", "manual", 1)));
        assertEquals(5, OHealthHealthModels.Kind.HEART.type(record("heart_rate", "sleep", 1)));
        // Health reads the day's resting rate from points typed 1 and the walking average from 4.
        assertEquals(1, OHealthHealthModels.Kind.HEART.type(record("heart_rate", "resting", 1)));
        assertEquals(4, OHealthHealthModels.Kind.HEART.type(record("heart_rate", "walking", 1)));
        assertEquals(10, OHealthHealthModels.Kind.OXYGEN.type(record("spo2", "continuous", 60_000)));
        assertEquals(1, OHealthHealthModels.Kind.STRESS.type(record("stress", "manual", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> OHealthHealthModels.Kind.HEART.type(record("heart_rate", "unknown", 1)));
    }

    @Test public void kindsRejectAMeasurementOfAnotherKind() {
        HealthRecord steps = new HealthRecord("day", "device", "steps_day", 0, 86_400_000L, 10,
                null, 1, "+05:30", "continuous", false);
        assertEquals(OHealthHealthModels.Kind.HEART,
                OHealthHealthModels.Kind.of(record("heart_rate", "resting", 1)));
        assertThrows(IllegalArgumentException.class, () -> OHealthHealthModels.Kind.of(steps));
        assertThrows(IllegalArgumentException.class,
                () -> OHealthHealthModels.Kind.OXYGEN.type(record("heart_rate", "resting", 1)));
    }

    private static HealthRecord record(String kind, String mode, long duration) {
        long start = 1_700_000_000_000L;
        return new HealthRecord("id", "device", kind, start, start + duration, 58, null, 1,
                "+05:30", mode, false);
    }
}
