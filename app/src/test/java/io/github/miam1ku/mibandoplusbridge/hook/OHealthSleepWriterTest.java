// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class OHealthSleepWriterTest {
    @Test public void oneStageBarBecomesOneRowPerMinute() {
        // OHealth's device writer stores one Sleep row per minute and the score pass indexes the
        // stage array by start minute. A 30-minute bar must become 30 contiguous rows.
        List<OHealthSleepPlan.Segment> rows = OHealthSleepWriter.minuteRows(
                List.of(new OHealthSleepPlan.Segment(1_000_000L, 1_000_000L + 30 * 60_000L,
                        OHealthSleepPlan.DEEP)));
        assertEquals(30, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            OHealthSleepPlan.Segment row = rows.get(i);
            assertEquals(1_000_000L + i * 60_000L, row.startMs());
            assertEquals(1_000_000L + (i + 1) * 60_000L, row.endMs());
            assertEquals(OHealthSleepPlan.DEEP, row.sleepState());
        }
    }

    @Test public void adjacentBarsStayContiguous() {
        long start = 2_000_000L;
        List<OHealthSleepPlan.Segment> rows = OHealthSleepWriter.minuteRows(List.of(
                new OHealthSleepPlan.Segment(start, start + 2 * 60_000L, OHealthSleepPlan.LIGHT),
                new OHealthSleepPlan.Segment(start + 2 * 60_000L, start + 3 * 60_000L,
                        OHealthSleepPlan.REM)));
        assertEquals(3, rows.size());
        assertEquals(start, rows.get(0).startMs());
        assertEquals(OHealthSleepPlan.LIGHT, rows.get(0).sleepState());
        assertEquals(start + 60_000L, rows.get(1).startMs());
        assertEquals(OHealthSleepPlan.LIGHT, rows.get(1).sleepState());
        assertEquals(start + 2 * 60_000L, rows.get(2).startMs());
        assertEquals(OHealthSleepPlan.REM, rows.get(2).sleepState());
        assertEquals(start + 3 * 60_000L, rows.get(2).endMs());
    }

    @Test public void aSubMinuteTailIsClampedNotDropped() {
        List<OHealthSleepPlan.Segment> rows = OHealthSleepWriter.minuteRows(
                List.of(new OHealthSleepPlan.Segment(0L, 90_000L, OHealthSleepPlan.LIGHT)));
        assertEquals(2, rows.size());
        assertEquals(0L, rows.get(0).startMs());
        assertEquals(60_000L, rows.get(0).endMs());
        assertEquals(60_000L, rows.get(1).startMs());
        assertEquals(90_000L, rows.get(1).endMs());
    }

    @Test public void emptyAndInvertedBarsProduceNothing() {
        assertEquals(0, OHealthSleepWriter.minuteRows(List.of()).size());
        assertEquals(0, OHealthSleepWriter.minuteRows(
                List.of(new OHealthSleepPlan.Segment(5_000L, 5_000L, OHealthSleepPlan.DEEP))).size());
        assertEquals(0, OHealthSleepWriter.minuteRows(
                List.of(new OHealthSleepPlan.Segment(5_000L, 4_000L, OHealthSleepPlan.DEEP))).size());
    }
}
