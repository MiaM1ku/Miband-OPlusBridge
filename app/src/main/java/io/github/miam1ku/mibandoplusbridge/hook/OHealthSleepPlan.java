// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Band intervals and stages become the host's 20:00 sleep-day rows. No score is invented. */
public final class OHealthSleepPlan {
    /** Host Sleep.sleepState. SleepDataMapping turns these into the chart. */
    public static final int DEEP = 2;
    public static final int REM = 3;
    public static final int LIGHT = 4;
    public static final int AWAKE = 1;

    public record Segment(long startMs, long endMs, int sleepState) {}

    public record Night(int date, long fallAsleepMs, long wakeMs, long sleepMinutes, long deepMinutes,
            long lightMinutes, long remMinutes, long wakeMinutes, List<Segment> segments) {}

    private OHealthSleepPlan() {}

    /** The calendar date whose 20:00-20:00 window contains this wake time. */
    public static int sleepDate(long endMs, ZoneId zone) {
        var local = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDateTime();
        LocalDate day = local.getHour() >= 20 ? local.toLocalDate().plusDays(1) : local.toLocalDate();
        return day.getYear() * 10000 + day.getMonthValue() * 100 + day.getDayOfMonth();
    }

    /** Xiaomi SleepState: 2 deep, 3 light, 4 REM, 5 awake. There is no separate 熟睡. */
    public static int hostState(int bandStage) {
        return switch (bandStage) {
            case 2 -> DEEP;
            case 3 -> LIGHT;
            case 4 -> REM;
            case 5 -> AWAKE;
            default -> throw new IllegalArgumentException("UNSUPPORTED_SLEEP_STAGE");
        };
    }

    public static List<Night> nights(List<HealthRecord> records) {
        List<HealthRecord> intervals = new ArrayList<>();
        List<HealthRecord> stages = new ArrayList<>();
        for (HealthRecord record : records) {
            if ("sleep_interval".equals(record.kind)) intervals.add(record);
            else if ("sleep_stage".equals(record.kind)) stages.add(record);
        }
        intervals.sort(Comparator.comparingLong(record -> record.startMs));
        boolean[] used = new boolean[stages.size()];
        List<NightBuilder> builders = new ArrayList<>();
        for (HealthRecord interval : intervals) {
            List<Segment> segments = new ArrayList<>();
            for (int i = 0; i < stages.size(); i++) {
                if (used[i]) continue;
                HealthRecord stage = stages.get(i);
                long overlap = overlap(interval, stage);
                if (overlap <= 0 || !bestInterval(interval, stage, intervals, overlap)) continue;
                used[i] = true;
                segments.add(new Segment(stage.startMs, stage.endMs, hostState(stage.stage)));
            }
            // The host chart has no stage-unknown bar. Light does not claim deep sleep or REM.
            if (segments.isEmpty()) {
                segments.add(new Segment(interval.startMs, interval.endMs, LIGHT));
            }
            segments = merge(segments);
            ZoneId zone = interval.timezone == null ? ZoneId.systemDefault() : ZoneId.of(interval.timezone);
            int date = sleepDate(interval.endMs, zone);
            // Host SleepDataStat is one row per date: axis is min(fall)..max(wake),
            // totals are the sum of real segments. Do not invent a bar across the nap gap.
            NightBuilder builder = null;
            for (NightBuilder existing : builders) if (existing.date == date) builder = existing;
            if (builder == null) {
                builder = new NightBuilder(date);
                builders.add(builder);
            }
            builder.fall = builder.fall == 0 ? interval.startMs : Math.min(builder.fall, interval.startMs);
            builder.wake = Math.max(builder.wake, interval.endMs);
            builder.segments.addAll(segments);
        }
        List<Night> nights = new ArrayList<>();
        for (NightBuilder builder : builders) {
            List<Segment> segments = merge(builder.segments);
            long sleep = 0, deep = 0, light = 0, rem = 0, wake = 0;
            for (Segment segment : segments) {
                long minutes = Math.max(0, (segment.endMs - segment.startMs) / 60_000);
                switch (segment.sleepState) {
                    case DEEP -> { deep += minutes; sleep += minutes; }
                    case LIGHT -> { light += minutes; sleep += minutes; }
                    case REM -> { rem += minutes; sleep += minutes; }
                    case AWAKE -> wake += minutes;
                    default -> throw new IllegalStateException("SLEEP_STATE_UNMAPPED");
                }
            }
            nights.add(new Night(builder.date, builder.fall, builder.wake, sleep, deep, light, rem, wake,
                    List.copyOf(segments)));
        }
        nights.sort(Comparator.comparingInt(Night::date));
        return nights;
    }

    private static boolean bestInterval(HealthRecord interval, HealthRecord stage, List<HealthRecord> intervals,
            long overlap) {
        for (HealthRecord other : intervals) {
            if (other == interval) continue;
            long otherOverlap = overlap(other, stage);
            if (otherOverlap > overlap || (otherOverlap == overlap && other.startMs < interval.startMs)) return false;
        }
        return true;
    }

    private static long overlap(HealthRecord interval, HealthRecord stage) {
        return Math.max(0, Math.min(interval.endMs, stage.endMs) - Math.max(interval.startMs, stage.startMs));
    }

    private static List<Segment> merge(List<Segment> segments) {
        if (segments.isEmpty()) return segments;
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingLong(Segment::startMs).thenComparingInt(Segment::sleepState));
        List<Segment> merged = new ArrayList<>();
        Segment current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            Segment next = sorted.get(i);
            if (next.sleepState == current.sleepState && next.startMs <= current.endMs) {
                current = new Segment(current.startMs, Math.max(current.endMs, next.endMs), current.sleepState);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static final class NightBuilder {
        final int date;
        long fall, wake;
        final List<Segment> segments = new ArrayList<>();
        NightBuilder(int date) { this.date = date; }
    }
}
