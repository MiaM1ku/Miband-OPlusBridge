// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class OHealthStepWriterTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test public void minuteBarsKeepTheHourMarkOnTheFirstActiveMinute() {
        long day = LocalDate.of(2026, 9, 30).atStartOfDay(ZONE).toInstant().toEpochMilli();
        OHealthStepWriter.MinuteBar[] bars = OHealthStepWriter.MinuteBar.day(List.of(
                minute(day, 0, 4, 1, 12),
                minute(day, 1, 6, 2, 8),
                minute(day, 60, 9, null, null),
                minute(day, 1_440, 3, 1, 1),
                minute(day, 1, 5, 1, 1, "+05:30")), day, "Asia/Shanghai");
        assertEquals(4, bars[0].steps);
        assertEquals(1, bars[0].calories);
        assertEquals(12, bars[0].distance);
        assertEquals(1, bars[0].workout);
        assertEquals(1, bars[0].moveAbout);
        assertEquals(6, bars[1].steps);
        assertEquals(0, bars[1].moveAbout);
        assertEquals(1, bars[1].workout);
        assertEquals(9, bars[60].steps);
        assertEquals(0, bars[60].calories);
        assertEquals(1, bars[60].moveAbout);
        assertNull(bars[2]);
        int filled = 0;
        for (OHealthStepWriter.MinuteBar bar : bars) if (bar != null) filled++;
        assertEquals(3, filled);
    }

    @Test public void publishStepsOnlyWhenTheBandIsAhead() {
        assertTrue(OHealthStepWriter.publishSteps(0, 100));
        assertFalse(OHealthStepWriter.publishSteps(200, 100));
        assertFalse(OHealthStepWriter.publishSteps(0, 0));
        assertFalse(OHealthStepWriter.publishSteps(100, 100));
    }

    private static HealthRecord minute(long dayStart, int index, int steps, Integer calories, Integer distance) {
        return minute(dayStart, index, steps, calories, distance, "Asia/Shanghai");
    }

    private static HealthRecord minute(long dayStart, int index, int steps, Integer calories, Integer distance,
            String zone) {
        long start = dayStart + index * 60_000L;
        return new HealthRecord("m-" + index + zone, "device", "steps_interval", start, start + 60_000L,
                steps, null, 1, zone, "continuous", false, calories, distance, null);
    }
}
