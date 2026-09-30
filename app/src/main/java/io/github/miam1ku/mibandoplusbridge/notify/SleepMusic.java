// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.content.Context;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/**
 * Type 2 subtype 78 is one baseline read ({@code isUserAsleep}).
 * Subtype 79 is the pushed change ({@code sleepState} 1 asleep, 2 awake).
 * The first sample after {@link #reset()} is only a baseline.
 * A sleep file that started after the switch was armed pauses once per night.
 * An asleep baseline, or a later awake report, holds that night until the next one.
 */
public final class SleepMusic {
    public static final String PREFS = "features";
    public static final String KEY = "sleepPauseMusic";
    public static final String ARMED_KEY = "sleepPauseArmedAt";
    public enum Effect { BASELINE, UNCHANGED, PAUSE, WOKE }

    public record Report(Effect effect, int generation) {}

    private static final int UNKNOWN = -1;
    private static final int AWAKE = 0;
    private static final int ASLEEP = 1;

    private int phase = UNKNOWN;
    private int generation;
    private boolean holdFile;
    private long baselineAtMs = Long.MAX_VALUE;
    private long pausedStart = Long.MIN_VALUE;
    /** A file whose end is older than this is a previous night, not a reason to pause. */
    public static final long FILE_WINDOW_MS = 20 * 60_000L;

    public static boolean enabled(Context context) {
        return LocalPrefs.open(context, PREFS).getBoolean(KEY, false);
    }

    /** Switch-on time. A restart keeps it; a missing value is not yet armed. */
    public static long armedAt(Context context) {
        if (!enabled(context)) return Long.MAX_VALUE;
        return LocalPrefs.open(context, PREFS).getLong(ARMED_KEY, Long.MAX_VALUE);
    }

    /** Move the cutoff forward. Nights that started before it do not pause after a restart. */
    public static boolean rememberCutoff(Context context, long cutoffMs) {
        if (!enabled(context) || cutoffMs <= 0 || cutoffMs == Long.MAX_VALUE) return false;
        long current = LocalPrefs.open(context, PREFS).getLong(ARMED_KEY, Long.MIN_VALUE);
        if (current != Long.MIN_VALUE && cutoffMs <= current) return true;
        return LocalPrefs.open(context, PREFS).edit().putLong(ARMED_KEY, cutoffMs).commit();
    }

    public static boolean setEnabled(Context context, boolean enabled) {
        var edit = LocalPrefs.open(context, PREFS).edit().putBoolean(KEY, enabled);
        if (enabled) edit.putLong(ARMED_KEY, System.currentTimeMillis());
        else edit.remove(ARMED_KEY);
        return edit.commit();
    }

    public static XiaomiProto.Command query() {
        return XiaomiProto.Command.newBuilder().setType(2).setSubtype(78).build();
    }

    /** @return null when {@code command} is not a sleep report */
    public static Boolean asleep(XiaomiProto.Command command) {
        if (command == null || command.getType() != 2 || !command.hasSystem()
                || (command.hasStatus() && command.getStatus() != 0)) return null;
        if (command.getSubtype() == 78 && command.getSystem().hasBasicDeviceState()) {
            return command.getSystem().getBasicDeviceState().getIsUserAsleep();
        }
        if (command.getSubtype() == 79 && command.getSystem().hasDeviceState()
                && command.getSystem().getDeviceState().hasSleepState()) {
            return switch (command.getSystem().getDeviceState().getSleepState()) {
                case 1 -> Boolean.TRUE;
                case 2 -> Boolean.FALSE;
                default -> null;
            };
        }
        return null;
    }

    public synchronized Report observe(boolean asleep) {
        return observe(asleep, Long.MAX_VALUE);
    }

    /** @param nowMs when this baseline was read; a file from before it does not pause */
    public synchronized Report observe(boolean asleep, long nowMs) {
        int next = asleep ? ASLEEP : AWAKE;
        if (phase == UNKNOWN) {
            phase = next;
            if (asleep) holdNight(nowMs);
            return new Report(Effect.BASELINE, generation);
        }
        if (phase == ASLEEP && !asleep) holdNight(nowMs);
        return transition(next);
    }

    /** Subtype 79. The first sample is still only a baseline. */
    public synchronized Report push(boolean asleep) {
        return push(asleep, Long.MIN_VALUE);
    }

    public synchronized Report push(boolean asleep, long nowMs) {
        int next = asleep ? ASLEEP : AWAKE;
        if (phase == UNKNOWN) {
            phase = next;
            return new Report(Effect.BASELINE, generation);
        }
        if (phase == ASLEEP && !asleep) holdNight(nowMs);
        return transition(next);
    }

    /**
     * A parsed sleep interval. Skipped when it began before the switch was armed,
     * before a held night, or its end is no longer current.
     */
    public synchronized Report currentNight(long startMs, long endMs, long armedAtMs, long nowMs) {
        if (startMs < armedAtMs || endMs <= startMs
                || endMs < nowMs - FILE_WINDOW_MS || endMs > nowMs + 5 * 60_000L) {
            return new Report(Effect.UNCHANGED, generation);
        }
        if (holdFile && startMs <= baselineAtMs) return new Report(Effect.UNCHANGED, generation);
        holdFile = false;
        if (startMs == pausedStart) return new Report(Effect.UNCHANGED, generation);
        pausedStart = startMs;
        phase = ASLEEP;
        return new Report(Effect.PAUSE, generation);
    }

    private void holdNight(long nowMs) {
        holdFile = true;
        baselineAtMs = nowMs;
    }

    public synchronized boolean asleep() { return phase == ASLEEP; }

    private Report transition(int next) {
        Effect effect = phase == next ? Effect.UNCHANGED : next == ASLEEP ? Effect.PAUSE : Effect.WOKE;
        phase = next;
        return new Report(effect, generation);
    }

    public synchronized int reset() {
        phase = UNKNOWN;
        holdFile = false;
        baselineAtMs = Long.MAX_VALUE;
        pausedStart = Long.MIN_VALUE;
        return ++generation;
    }

    public synchronized int generation() { return generation; }
}
