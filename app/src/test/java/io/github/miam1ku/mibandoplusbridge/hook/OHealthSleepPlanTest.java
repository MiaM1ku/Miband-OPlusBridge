// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class OHealthSleepPlanTest {
    private static final ZoneId ZONE = ZoneId.of("+08:00");

    @Test public void longerUnstagedSessionKeepsTheTwentyHourWindow() {
        HealthRecord morning = interval("morning", at(2026, 9, 25, 3, 41), at(2026, 9, 25, 11, 45));
        HealthRecord nap = interval("nap", at(2026, 9, 25, 13, 18), at(2026, 9, 25, 15, 23));
        HealthRecord late = interval("late", at(2026, 9, 25, 21, 0), at(2026, 9, 25, 22, 0));
        List<OHealthSleepPlan.Night> nights = OHealthSleepPlan.nights(List.of(nap, late, morning));
        assertEquals(3, nights.size());
        OHealthSleepPlan.Night day = nights.get(0);
        assertEquals(20260925, day.date());
        assertEquals(morning.startMs, day.fallAsleepMs());
        assertEquals(morning.endMs, day.wakeMs());
        assertEquals(484, day.sleepMinutes());
        assertEquals(484, day.lightMinutes());
        assertEquals(0, day.deepMinutes());
        assertEquals(0, day.remMinutes());
        assertEquals(1, day.segments().size());
        assertEquals(OHealthSleepPlan.LIGHT, day.segments().get(0).sleepState());
        assertEquals(at(2026, 9, 24, 20, 0), day.dayStartMs());
        assertEquals(at(2026, 9, 25, 20, 0), day.dayEndMs());
        assertTrue(OHealthSleepPlan.summary(nights, day));
        OHealthSleepPlan.Night afternoon = nights.get(1);
        assertEquals(20260925, afternoon.date());
        assertEquals(nap.startMs, afternoon.fallAsleepMs());
        assertEquals(125, afternoon.sleepMinutes());
        assertFalse(OHealthSleepPlan.summary(nights, afternoon));
        assertEquals(20260926, nights.get(2).date());
        assertEquals(60, nights.get(2).sleepMinutes());
        assertEquals(late.startMs, nights.get(2).fallAsleepMs());
        assertFalse(OHealthSleepPlan.summary(nights, nights.get(2)));
    }

    @Test public void stagedNightKeepsTheLaterNap() {
        long start = at(2026, 9, 28, 2, 10);
        long wake = at(2026, 9, 28, 5, 57);
        HealthRecord night = interval("night", start, wake);
        HealthRecord deep = stage("deep", start, start + 3_600_000, 2);
        HealthRecord light = stage("light", start + 3_600_000, wake, 3);
        HealthRecord nap = interval("nap", at(2026, 9, 28, 15, 33), at(2026, 9, 28, 16, 34));
        List<OHealthSleepPlan.Night> nights = OHealthSleepPlan.nights(List.of(nap, night, light, deep));
        assertEquals(2, nights.size());
        OHealthSleepPlan.Night chosen = nights.get(0);
        assertEquals(start, chosen.fallAsleepMs());
        assertEquals(wake, chosen.wakeMs());
        assertEquals(2, chosen.segments().size());
        assertEquals(OHealthSleepPlan.DEEP, chosen.segments().get(0).sleepState());
        assertEquals(OHealthSleepPlan.LIGHT, chosen.segments().get(1).sleepState());
        assertEquals(227, chosen.sleepMinutes());
        assertTrue(OHealthSleepPlan.summary(nights, chosen));
        OHealthSleepPlan.Night kept = nights.get(1);
        assertEquals(nap.startMs, kept.fallAsleepMs());
        assertEquals(nap.endMs, kept.wakeMs());
        assertEquals(61, kept.sleepMinutes());
        assertEquals(OHealthSleepPlan.LIGHT, kept.segments().get(0).sleepState());
        assertFalse(OHealthSleepPlan.summary(nights, kept));
    }

    @Test public void sessionStartingBeforeTwentyExtendsTheClearWindow() {
        HealthRecord early = interval("early", at(2026, 9, 24, 19, 30), at(2026, 9, 25, 2, 0));
        HealthRecord nap = interval("nap", at(2026, 9, 25, 14, 0), at(2026, 9, 25, 15, 0));
        List<OHealthSleepPlan.Night> nights = OHealthSleepPlan.nights(List.of(nap, early));
        assertEquals(2, nights.size());
        assertEquals(early.startMs, nights.get(0).fallAsleepMs());
        assertEquals(early.startMs, nights.get(0).dayStartMs());
        assertEquals(at(2026, 9, 25, 20, 0), nights.get(0).dayEndMs());
        assertEquals(nap.startMs, nights.get(1).fallAsleepMs());
        assertEquals(60, nights.get(1).sleepMinutes());
        assertEquals(at(2026, 9, 24, 20, 0), nights.get(1).dayStartMs());
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
        assertEquals(5, OHealthSleepPlan.AWAKE);
        assertEquals(OHealthSleepPlan.AWAKE, night.segments().get(3).sleepState());
        assertEquals(180, night.sleepMinutes());
        assertEquals(60, night.deepMinutes());
        assertEquals(60, night.lightMinutes());
        assertEquals(60, night.remMinutes());
        assertEquals(60, night.wakeMinutes());
        assertThrows(IllegalArgumentException.class, () -> OHealthSleepPlan.hostState(1));
    }

    @Test public void overlappingStagesPartitionInsteadOfStacking() {
        long start = at(2026, 10, 2, 1, 0);
        long lightStart = at(2026, 10, 2, 1, 30);
        long two = at(2026, 10, 2, 2, 0);
        long end = at(2026, 10, 2, 3, 0);
        HealthRecord interval = interval("night", start, end);
        List<OHealthSleepPlan.Night> nights = OHealthSleepPlan.nights(List.of(
                interval,
                stage("deep", start, two, 2),
                stage("light", lightStart, end, 3)));
        assertEquals(1, nights.size());
        OHealthSleepPlan.Night night = nights.get(0);
        assertEquals(2, night.segments().size());
        assertEquals(start, night.segments().get(0).startMs());
        assertEquals(two, night.segments().get(0).endMs());
        assertEquals(OHealthSleepPlan.DEEP, night.segments().get(0).sleepState());
        assertEquals(two, night.segments().get(1).startMs());
        assertEquals(end, night.segments().get(1).endMs());
        assertEquals(OHealthSleepPlan.LIGHT, night.segments().get(1).sleepState());
        assertEquals(120, night.sleepMinutes());
        assertEquals(60, night.deepMinutes());
        assertEquals(60, night.lightMinutes());

        // Shorter than the light that starts at the same minute, and it runs past the first deep.
        HealthRecord shorter = stage("deep-short", lightStart, at(2026, 10, 2, 2, 30), 2);
        nights = OHealthSleepPlan.nights(List.of(
                interval, stage("deep", start, two, 2), stage("light", lightStart, end, 3), shorter));
        assertEquals(1, nights.size());
        night = nights.get(0);
        assertEquals(2, night.segments().size());
        assertEquals(two, night.segments().get(1).startMs());
        assertEquals(end, night.segments().get(1).endMs());
        assertEquals(120, night.sleepMinutes());
        assertEquals(60, night.deepMinutes());
        assertEquals(60, night.lightMinutes());
    }

    @Test public void gapsInsideOneNapStayOneSession() {
        long start = at(2026, 10, 5, 14, 0);
        long deepEnd = start + 20 * 60_000L;
        long lightStart = deepEnd + 20_000L;
        long lightEnd = lightStart + 15 * 60_000L;
        long remStart = lightEnd + 2 * 60_000L;
        long remEnd = remStart + 10 * 60_000L;
        List<OHealthSleepPlan.Night> nights = OHealthSleepPlan.nights(List.of(
                interval("nap", start, remEnd),
                stage("deep", start, deepEnd, 2),
                stage("light", lightStart, lightEnd, 3),
                stage("rem", remStart, remEnd, 4)));
        assertEquals(1, nights.size());
        List<OHealthSleepPlan.Segment> segments = nights.get(0).segments();
        assertEquals(4, segments.size());
        assertEquals(lightStart, segments.get(0).endMs());
        assertEquals(OHealthSleepPlan.AWAKE, segments.get(2).sleepState());
        assertEquals(segments.get(2).endMs(), segments.get(3).startMs());
        assertFalse(OHealthSleepPlan.summary(nights, nights.get(0)));
    }

    @Test public void summaryClockUsesSleepDayMinutes() {
        // Matches the host's StoreUtil.changeMillisToCurrentDayMinutes: 20:00-23:59 keep their
        // clock value, 00:00-19:59 count into the next day.
        assertEquals(1200, OHealthSleepPlan.sleepDayMinutes(at(2026, 10, 4, 20, 0), ZONE));
        assertEquals(1395, OHealthSleepPlan.sleepDayMinutes(at(2026, 10, 4, 23, 15), ZONE));
        assertEquals(1539, OHealthSleepPlan.sleepDayMinutes(at(2026, 10, 5, 1, 39), ZONE));
        assertEquals(2065, OHealthSleepPlan.sleepDayMinutes(at(2026, 10, 5, 10, 25), ZONE));
        assertEquals(2639, OHealthSleepPlan.sleepDayMinutes(at(2026, 10, 5, 19, 59), ZONE));
    }


    @Test public void daysCombinesMultipleSessionsIntoUnifiedDay() {
        long mainStart = at(2026, 10, 8, 2, 0);
        long mainEnd = at(2026, 10, 8, 7, 0); // 300 min
        long napStart = at(2026, 10, 8, 14, 0);
        long napEnd = at(2026, 10, 8, 15, 30); // 90 min

        HealthRecord mainInterval = interval("main", mainStart, mainEnd);
        HealthRecord napInterval = interval("nap", napStart, napEnd);
        List<OHealthSleepPlan.Night> nights = OHealthSleepPlan.nights(List.of(
                mainInterval,
                stage("deep", mainStart, mainStart + 120 * 60_000L, 2),
                stage("light", mainStart + 120 * 60_000L, mainEnd, 3),
                napInterval,
                stage("nap-light", napStart, napEnd, 3)));
        assertEquals(2, nights.size());

        List<OHealthSleepPlan.Day> days = OHealthSleepPlan.days(nights);
        assertEquals(1, days.size());
        OHealthSleepPlan.Day day = days.get(0);
        assertEquals(20261008, day.date());
        assertEquals(2, day.nights().size());
        assertEquals(390, day.sleepMinutes());
        assertEquals(120, day.deepMinutes());
        assertEquals(270, day.lightMinutes());
        assertEquals(mainStart, day.fallAsleepMs());
        assertEquals(napEnd, day.wakeMs());
        assertEquals(nights.get(0), day.mainSession());
        assertEquals(ZONE, day.zone());
    }

    @Test public void daysWithNapsOnlyHasNullMainSession() {
        long nap1Start = at(2026, 10, 8, 13, 0);
        long nap1End = at(2026, 10, 8, 14, 0); // 60 min
        long nap2Start = at(2026, 10, 8, 16, 0);
        long nap2End = at(2026, 10, 8, 16, 45); // 45 min

        List<OHealthSleepPlan.Night> nights = OHealthSleepPlan.nights(List.of(
                interval("nap1", nap1Start, nap1End),
                stage("nap1-light", nap1Start, nap1End, 3),
                interval("nap2", nap2Start, nap2End),
                stage("nap2-light", nap2Start, nap2End, 3)));
        assertEquals(2, nights.size());

        List<OHealthSleepPlan.Day> days = OHealthSleepPlan.days(nights);
        assertEquals(1, days.size());
        OHealthSleepPlan.Day day = days.get(0);
        assertEquals(20261008, day.date());
        assertEquals(105, day.sleepMinutes());
        assertNull(day.mainSession());
        assertEquals(nap1Start, day.fallAsleepMs());
        assertEquals(nap2End, day.wakeMs());
    }

    @Test public void daysSeparatesDifferentDates() {
        long d1Start = at(2026, 10, 7, 1, 0);
        long d1End = at(2026, 10, 7, 7, 0); // 360 min
        long d2Start = at(2026, 10, 8, 2, 0);
        long d2End = at(2026, 10, 8, 6, 0); // 240 min

        List<OHealthSleepPlan.Night> nights = OHealthSleepPlan.nights(List.of(
                interval("d1", d1Start, d1End),
                stage("d1-light", d1Start, d1End, 3),
                interval("d2", d2Start, d2End),
                stage("d2-light", d2Start, d2End, 3)));
        assertEquals(2, nights.size());

        List<OHealthSleepPlan.Day> days = OHealthSleepPlan.days(nights);
        assertEquals(2, days.size());
        assertEquals(20261007, days.get(0).date());
        assertEquals(360, days.get(0).sleepMinutes());
        assertEquals(20261008, days.get(1).date());
        assertEquals(240, days.get(1).sleepMinutes());
    }

    @Test public void daysHandlesEmptyOrNull() {
        assertTrue(OHealthSleepPlan.days(null).isEmpty());
        assertTrue(OHealthSleepPlan.days(List.of()).isEmpty());
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
