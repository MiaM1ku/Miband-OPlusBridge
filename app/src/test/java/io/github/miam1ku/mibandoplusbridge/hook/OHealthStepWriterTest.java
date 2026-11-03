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

    @Test public void minuteBarsKeepStepsAndMarkAWorkoutMinute() {
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
        assertEquals(6, bars[1].steps);
        assertEquals(1, bars[1].workout);
        assertEquals(9, bars[60].steps);
        assertEquals(1, bars[60].workout);
        assertEquals(0, bars[60].calories);
        assertNull(bars[2]);
        int filled = 0;
        for (OHealthStepWriter.MinuteBar bar : bars) if (bar != null) filled++;
        assertEquals(3, filled);
    }

    @Test public void watchWalkMinutesLeaveThePhoneWalkBucket() {
        assertEquals("Band", OHealthStepWriter.deviceCategory("miwear.watch.q66cn"));
        assertEquals("Band", OHealthStepWriter.deviceCategory("lchz.watch.m67"));
        assertEquals("Band", OHealthStepWriter.deviceCategory("miwear.watch.n66gl"));
        assertEquals("Watch", OHealthStepWriter.deviceCategory("mijia.watch.o62"));
        assertEquals("Watch", OHealthStepWriter.deviceCategory("miwear.watch.q65acn"));
        assertEquals("Watch", OHealthStepWriter.deviceCategory("hqbd3.watch.l67"));
        assertEquals(1, OHealthStepWriter.minuteSportMode(false));
        assertEquals(-1, OHealthStepWriter.minuteSportMode(true));
        assertEquals(1, OHealthStepWriter.groupSportType(2));
        assertEquals(0, OHealthStepWriter.groupSportType(3));
    }

    @Test public void publishStepsOnlyWhenTheBandIsAhead() {
        assertTrue(OHealthStepWriter.publishSteps(0, 100));
        assertFalse(OHealthStepWriter.publishSteps(200, 100));
        assertFalse(OHealthStepWriter.publishSteps(0, 0));
        assertFalse(OHealthStepWriter.publishSteps(100, 100));
    }

    @Test public void ambiguousTimezoneDateLeavesOtherHistoricalDatesEligible() {
        long day = LocalDate.of(2026, 11, 1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        var days = io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser.stepDays(List.of(
                minute(day, 1, 221, 60, 117, "+08:00"),
                minute(day, 1, 221, 60, 117, "Asia/Shanghai"),
                minute(day - 86_400_000L, 1, 120, 10, 60)));
        assertEquals(3, days.size());
        var requests = OHealthStepWriter.aggregateAuditRequests("account", "device", "MAC", "Band", days,
                day + 2 * 86_400_000L);
        assertEquals(1, requests.size());
        assertEquals(20261031, requests.get(0).date());
    }

    @Test public void aggregateRequestsIncludeCachedHistoryAndKeepRecentBoundedWindow() {
        long today = LocalDate.of(2026, 11, 3).atStartOfDay(ZONE).toInstant().toEpochMilli();
        var records = new java.util.ArrayList<HealthRecord>();
        for (int days = -1; days <= 40; days++) records.add(minute(today - days * 86_400_000L, 1, 221, 60, 117));
        var requests = OHealthStepWriter.aggregateAuditRequests("account", "device", "MAC", "Watch",
                io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser.stepDays(records), today);
        assertEquals(31, requests.size());
        assertEquals(20261102, requests.get(0).date());
        for (var request : requests) {
            assertEquals(-1, request.minuteMode());
            assertTrue(request.startMs() <= today - 86_400_000L);
            assertTrue(request.startMs() >= today - 32 * 86_400_000L);
        }
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
