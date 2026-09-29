// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.content.Context;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/**
 * Type 2 subtype 78 is one baseline read ({@code isUserAsleep}).
 * Subtype 79 is the pushed change ({@code sleepState} 1 asleep, 2 awake).
 * The first sample after {@link #reset()} is only a baseline.
 */
public final class SleepMusic {
    public static final String PREFS = "features";
    public static final String KEY = "sleepPauseMusic";

    public enum Effect { BASELINE, UNCHANGED, PAUSE, WOKE }

    public record Report(Effect effect, int generation) {}

    private static final int UNKNOWN = -1;
    private static final int AWAKE = 0;
    private static final int ASLEEP = 1;

    private int phase = UNKNOWN;
    private int generation;

    public static boolean enabled(Context context) {
        return LocalPrefs.open(context, PREFS).getBoolean(KEY, false);
    }

    public static boolean setEnabled(Context context, boolean enabled) {
        return LocalPrefs.open(context, PREFS).edit().putBoolean(KEY, enabled).commit();
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
        int next = asleep ? ASLEEP : AWAKE;
        Effect effect = phase == UNKNOWN ? Effect.BASELINE
                : phase == next ? Effect.UNCHANGED
                : next == ASLEEP ? Effect.PAUSE : Effect.WOKE;
        phase = next;
        return new Report(effect, generation);
    }

    public synchronized int reset() {
        phase = UNKNOWN;
        return ++generation;
    }

    public synchronized int generation() { return generation; }
}
