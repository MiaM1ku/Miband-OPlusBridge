// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The band does not report a walking average. Health only shows one when it holds a heart-rate
 * point typed for walking, so this derives the day's value from the band's own minute rows:
 * minutes that look like walking and carry a nearby continuous heart rate.
 */
public final class WalkingHeartRate {
    /** Below this a minute is idle shuffling, above it the minute is a run. */
    static final int MIN_STEPS_PER_MINUTE = 60;
    static final int MAX_STEPS_PER_MINUTE = 140;
    /**
     * The band samples a heart rate about every ten minutes, so a walking minute borrows the
     * nearest reading inside half that interval instead of demanding one in the same minute.
     */
    static final long MATCH_WINDOW_MS = 5 * 60_000L;
    /** Fewer readings than this is not an average worth showing. */
    static final int MIN_SAMPLES = 10;

    private WalkingHeartRate() { }

    /** Null when the day does not hold enough walking readings to average. */
    public static Integer average(List<HealthRecord> steps, List<HealthRecord> heart,
            List<HealthRecord> sport) {
        if (steps == null || heart == null) return null;
        TreeMap<Long, Integer> beats = new TreeMap<>();
        for (HealthRecord sample : heart) {
            if (sample == null || !"heart_rate".equals(sample.kind)
                    || !"continuous".equals(sample.measurementMode) || sample.value == null) continue;
            int value = sample.value.intValue();
            if (value < 40 || value > 220) continue;
            beats.putIfAbsent(sample.startMs, value);
        }
        if (beats.isEmpty()) return null;
        List<HealthRecord> workouts = sport == null ? List.of() : sport;
        Set<Long> used = new TreeSet<>();
        long total = 0;
        for (HealthRecord minute : steps) {
            if (!walking(minute) || duringWorkout(workouts, minute.startMs)) continue;
            Map.Entry<Long, Integer> beat = nearest(beats, minute.startMs);
            // One reading is one measurement, however many walking minutes borrow it.
            if (beat != null && used.add(beat.getKey())) total += beat.getValue();
        }
        if (used.size() < MIN_SAMPLES) return null;
        return (int) Math.round((double) total / used.size());
    }

    private static boolean walking(HealthRecord minute) {
        if (minute == null || !"steps_interval".equals(minute.kind) || minute.value == null) return false;
        int count = minute.value.intValue();
        return count >= MIN_STEPS_PER_MINUTE && count <= MAX_STEPS_PER_MINUTE;
    }

    private static Map.Entry<Long, Integer> nearest(TreeMap<Long, Integer> beats, long atMs) {
        Map.Entry<Long, Integer> before = beats.floorEntry(atMs);
        Map.Entry<Long, Integer> after = beats.ceilingEntry(atMs);
        if (before != null && atMs - before.getKey() > MATCH_WINDOW_MS) before = null;
        if (after != null && after.getKey() - atMs > MATCH_WINDOW_MS) after = null;
        if (before == null) return after;
        if (after == null) return before;
        return after.getKey() - atMs < atMs - before.getKey() ? after : before;
    }

    private static boolean duringWorkout(List<HealthRecord> workouts, long atMs) {
        for (HealthRecord workout : workouts) {
            if (workout == null || !"sport_session".equals(workout.kind)) continue;
            if (atMs >= workout.startMs && atMs < workout.endMs) return true;
        }
        return false;
    }
}
