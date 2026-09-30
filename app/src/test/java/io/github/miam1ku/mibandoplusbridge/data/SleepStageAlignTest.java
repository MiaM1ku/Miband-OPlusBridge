// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SleepStageAlignTest {
    @Test public void newerFileKeepsTheNightAndAnOlderFileDoesNotDeleteIt() {
        var night = List.of(interval(0, 100));
        List<SleepStageAlign.Interval> claimed = new ArrayList<>();
        var newer = SleepStageAlign.withStages(SleepStageAlign.claim(night, claimed), List.of(interval(0, 40)));
        claimed.addAll(newer);
        assertEquals(1, newer.size());
        assertTrue(SleepStageAlign.claim(night, claimed).isEmpty());
    }

    @Test public void newerFileWithoutStagesLeavesTheNightToTheOlderFile() {
        var night = List.of(interval(0, 100));
        var open = SleepStageAlign.withStages(SleepStageAlign.claim(night, List.of()), List.of());
        assertTrue(open.isEmpty());
        assertEquals(1, SleepStageAlign.claim(night, open).size());
    }

    @Test public void separateNightsStayWithTheirOwnFiles() {
        List<SleepStageAlign.Interval> claimed = new ArrayList<>();
        claimed.addAll(SleepStageAlign.withStages(
                SleepStageAlign.claim(List.of(interval(1_000, 2_000)), List.of()),
                List.of(interval(1_000, 1_400))));
        var older = SleepStageAlign.claim(List.of(interval(0, 100)), claimed);
        assertEquals(1, older.size());
        assertEquals(0, older.get(0).startMs());
    }

    private static SleepStageAlign.Interval interval(long start, long end) {
        return new SleepStageAlign.Interval(start, end);
    }
}
