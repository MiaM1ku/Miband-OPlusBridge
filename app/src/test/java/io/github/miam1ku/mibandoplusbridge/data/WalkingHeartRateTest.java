// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class WalkingHeartRateTest {
    private static final long MINUTE = 60_000L;
    private static final long DAY = 86_400_000L;
    private static final long NOON = 1_700_000_000_000L;

    @Test public void walkingMinutesAreAveraged() {
        List<HealthRecord> steps = new ArrayList<>();
        List<HealthRecord> heart = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            steps.add(step(i, 90));
            heart.add(beat(i, 90 + i));
        }
        assertEquals(Integer.valueOf(95), WalkingHeartRate.average(steps, heart, List.of()));
    }

    @Test public void tooFewWalkingMinutesHaveNoAverage() {
        List<HealthRecord> steps = new ArrayList<>();
        List<HealthRecord> heart = new ArrayList<>();
        for (int i = 0; i < WalkingHeartRate.MIN_SAMPLES - 1; i++) {
            steps.add(step(i, 90));
            heart.add(beat(i, 90));
        }
        assertNull(WalkingHeartRate.average(steps, heart, List.of()));
        assertNull(WalkingHeartRate.average(List.of(), List.of(), List.of()));
    }

    @Test public void idleAndRunningMinutesAreNotWalking() {
        List<HealthRecord> steps = new ArrayList<>();
        List<HealthRecord> heart = new ArrayList<>();
        int index = 0;
        for (int count : new int[] {WalkingHeartRate.MIN_STEPS_PER_MINUTE - 1,
                WalkingHeartRate.MAX_STEPS_PER_MINUTE + 1, 0}) {
            steps.add(step(index, count));
            heart.add(beat(index, 120));
            index++;
        }
        for (int i = 0; i < WalkingHeartRate.MIN_SAMPLES; i++) {
            steps.add(step(index, WalkingHeartRate.MIN_STEPS_PER_MINUTE));
            heart.add(beat(index, 100));
            index++;
        }
        assertEquals(Integer.valueOf(100), WalkingHeartRate.average(steps, heart, List.of()));
    }

    @Test public void onlyAContinuousHeartRateCounts() {
        List<HealthRecord> steps = new ArrayList<>();
        List<HealthRecord> heart = new ArrayList<>();
        for (int i = 0; i < WalkingHeartRate.MIN_SAMPLES; i++) {
            steps.add(step(i, 90));
        }
        for (int i = 0; i < WalkingHeartRate.MIN_SAMPLES; i++) {
            heart.add(new HealthRecord("manual" + i, "device", "heart_rate", NOON + i * MINUTE,
                    NOON + i * MINUTE + 1, 120, null, 1, "+05:30", "manual", false));
            heart.add(new HealthRecord("resting" + i, "device", "heart_rate", NOON + i * MINUTE,
                    NOON + i * MINUTE + 1, 120, null, 1, "+05:30", "resting", false));
        }
        assertNull(WalkingHeartRate.average(steps, heart, List.of()));

        for (int i = 0; i < WalkingHeartRate.MIN_SAMPLES; i++) {
            heart.add(beat(i, 88));
        }
        assertEquals(Integer.valueOf(88), WalkingHeartRate.average(steps, heart, List.of()));
    }

    @Test public void minutesInsideAWorkoutAreLeftOut() {
        List<HealthRecord> steps = new ArrayList<>();
        List<HealthRecord> heart = new ArrayList<>();
        for (int i = 0; i < WalkingHeartRate.MIN_SAMPLES + 2; i++) {
            steps.add(step(i, 90));
            heart.add(beat(i, 130));
        }
        // The last two minutes are part of a recorded workout, not a walk to work.
        long from = NOON + WalkingHeartRate.MIN_SAMPLES * MINUTE;
        var workout = new HealthRecord("sport", "device", "sport_session", from,
                from + 2 * MINUTE, 2, null, 1, "+05:30", "continuous", false);
        assertEquals(Integer.valueOf(130),
                WalkingHeartRate.average(steps, heart, List.of(workout)));
        List<HealthRecord> shortWalk = new ArrayList<>();
        List<HealthRecord> shortHeart = new ArrayList<>();
        for (int i = 0; i < WalkingHeartRate.MIN_SAMPLES; i++) {
            shortWalk.add(step(i, 90));
            shortHeart.add(beat(i, 130));
        }
        long all = NOON;
        assertNull(WalkingHeartRate.average(shortWalk, shortHeart, List.of(
                new HealthRecord("sport", "device", "sport_session", all, all + DAY / 2, 2,
                        null, 1, "+05:30", "continuous", false))));
    }

    @Test public void aSparseHeartRateIsBorrowedByTheNearbyWalkingMinutes() {
        // The band reports a heart rate about every ten minutes, not every minute.
        List<HealthRecord> steps = new ArrayList<>();
        List<HealthRecord> heart = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            steps.add(step(i, 90));
        }
        for (int i = 0; i < 10; i++) {
            heart.add(beat(i * 6, 95 + i));
        }
        assertEquals(Integer.valueOf(100), WalkingHeartRate.average(steps, heart, List.of()));
    }

    @Test public void oneReadingIsCountedOnce() {
        // Twenty walking minutes covered by only five readings are five measurements, not twenty.
        List<HealthRecord> steps = new ArrayList<>();
        List<HealthRecord> heart = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            steps.add(step(i, 90));
        }
        for (int i = 0; i < 5; i++) {
            heart.add(beat(i * 4, 110));
        }
        assertNull(WalkingHeartRate.average(steps, heart, List.of()));
    }

    @Test public void averageRoundsToTheNearestBeat() {
        List<HealthRecord> steps = new ArrayList<>();
        List<HealthRecord> heart = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            steps.add(step(i, 90));
            heart.add(beat(i, i < 5 ? 100 : 101));
        }
        assertEquals(Integer.valueOf(101), WalkingHeartRate.average(steps, heart, List.of()));
    }

    private static HealthRecord step(int minute, int count) {
        long start = NOON + minute * MINUTE;
        return new HealthRecord("step" + minute, "device", "steps_interval", start, start + MINUTE,
                count, null, 1, "+05:30", "continuous", false);
    }

    private static HealthRecord beat(int minute, int value) {
        long start = NOON + minute * MINUTE;
        return new HealthRecord("beat" + minute, "device", "heart_rate", start, start + MINUTE,
                value, null, 1, "+05:30", "continuous", false);
    }
}
