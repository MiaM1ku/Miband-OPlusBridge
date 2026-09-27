// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class OHealthSleepPlanTest {
    private static final ZoneId ZONE = ZoneId.of("+08:00");

    @Test public void unstagedIntervalStaysLightAndUsesTheTwentyHourWindow() {
        HealthRecord morning = interval("morning", at(2026, 9, 25, 3, 41), at(2026, 9, 25, 11, 45));
        HealthRecord nap = interval("nap", at(2026, 9, 25, 13, 18), at(2026, 9, 25, 15, 23));
        HealthRecord late = interval("late", at(2026, 9, 25, 21, 0), at(2026, 9, 25, 22, 0));
        List<OHealthSleepPlan.Night> nights = OHealthSleepPlan.nights(List.of(nap, late, morning));
        assertEquals(2, nights.size());
        OHealthSleepPlan.Night day = nights.get(0);
        assertEquals(20260925, day.date());
        assertEquals(morning.startMs, day.fallAsleepMs());
        assertEquals(nap.endMs, day.wakeMs());
        assertEquals(484 + 125, day.sleepMinutes());
        assertEquals(484 + 125, day.lightMinutes());
        assertEquals(0, day.deepMinutes());
        assertEquals(0, day.remMinutes());
        assertEquals(2, day.segments().size());
        assertEquals(morning.startMs, day.segments().get(0).startMs());
        assertEquals(morning.endMs, day.segments().get(0).endMs());
        assertEquals(nap.startMs, day.segments().get(1).startMs());
        assertEquals(nap.endMs, day.segments().get(1).endMs());
        assertEquals(OHealthSleepPlan.LIGHT, day.segments().get(0).sleepState());
        assertEquals(20260926, nights.get(1).date());
        assertEquals(60, nights.get(1).sleepMinutes());
    }

    @Test public void stagesMapOntoHostStatesAndAreNotDuplicated() {
        long start = at(2026, 9, 25, 1, 0);
        HealthRecord interval = interval("night", start, at(2026, 9, 25, 5, 0));
        HealthRecord deep = stage("deep", start, start + 3_600_000, 2);
        HealthRecord light = stage("light", start + 3_600_000, start + 7_200_000, 3);
        HealthRecord rem = stage("rem", start + 7_200_000, start + 10_800_000, 4);
        HealthRecord awake = stage("awake", start + 10_800_000, start + 14_400_000, 5);
        List<OHealthSleepPlan.Night> nights = OHealthSleepPlan.nights(
                List.of(interval, awake, rem, light, deep));
        assertEquals(1, nights.size());
        OHealthSleepPlan.Night night = nights.get(0);
        assertEquals(4, night.segments().size());
        assertEquals(OHealthSleepPlan.DEEP, night.segments().get(0).sleepState());
        assertEquals(OHealthSleepPlan.LIGHT, night.segments().get(1).sleepState());
        assertEquals(OHealthSleepPlan.REM, night.segments().get(2).sleepState());
        assertEquals(OHealthSleepPlan.AWAKE, night.segments().get(3).sleepState());
        assertEquals(180, night.sleepMinutes());
        assertEquals(60, night.deepMinutes());
        assertEquals(60, night.lightMinutes());
        assertEquals(60, night.remMinutes());
        assertEquals(60, night.wakeMinutes());
        assertThrows(IllegalArgumentException.class, () -> OHealthSleepPlan.hostState(1));
    }

    private static HealthRecord interval(String id, long start, long end) {
        return new HealthRecord(id, "band", "sleep_interval", start, end, null, null, 1, "+08:00", "sleep", true);
    }

    private static HealthRecord stage(String id, long start, long end, int stage) {
        return new HealthRecord(id, "band", "sleep_stage", start, end, null, stage, 1, "+08:00", "sleep", false);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli();
    }
}
