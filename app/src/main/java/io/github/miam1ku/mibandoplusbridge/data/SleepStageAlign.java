// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.util.ArrayList;
import java.util.List;

/** Decides which sleep file owns an interval. The newest file wins; an empty analysis does not. */
public final class SleepStageAlign {
    private SleepStageAlign() {}

    public record Interval(long startMs, long endMs) {
        public boolean overlaps(long start, long end) {
            return start < endMs && end > startMs;
        }
    }

    /** Intervals in this file that a newer file has not already taken. */
    public static List<Interval> claim(List<Interval> fileIntervals, List<Interval> newer) {
        List<Interval> owned = new ArrayList<>();
        if (fileIntervals == null) return owned;
        for (Interval interval : fileIntervals) {
            if (interval == null || interval.endMs() <= interval.startMs()) continue;
            if (overlapsAny(interval.startMs(), interval.endMs(), newer)) continue;
            owned.add(interval);
        }
        return owned;
    }

    /** An owned interval with no stage stays free so an older file can keep its chart. */
    public static List<Interval> withStages(List<Interval> owned, List<Interval> stages) {
        List<Interval> covered = new ArrayList<>();
        if (owned == null) return covered;
        for (Interval interval : owned) {
            if (interval != null && overlapsAny(interval.startMs(), interval.endMs(), stages)) covered.add(interval);
        }
        return covered;
    }

    public static boolean overlapsAny(long startMs, long endMs, List<Interval> others) {
        if (others == null || endMs <= startMs) return false;
        for (Interval other : others) {
            if (other != null && other.overlaps(startMs, endMs)) return true;
        }
        return false;
    }
}
