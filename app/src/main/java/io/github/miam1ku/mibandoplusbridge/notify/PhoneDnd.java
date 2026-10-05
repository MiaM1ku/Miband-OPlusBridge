// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.app.NotificationManager;
import android.content.Context;

/** Phone Do Not Disturb. Values match NotificationManager interruption filters. */
public final class PhoneDnd {
    public static final int UNKNOWN = 0;
    public static final int ALL = 1;
    public static final int PRIORITY = 2;
    public static final int NONE = 3;
    public static final int ALARMS = 4;

    private PhoneDnd() {}

    public static int currentFilter(Context context) {
        int zen = global(context, "zen_mode");
        int focus = secure(context, "focusmode_switch");
        int focusNew = secure(context, "focusmode_switch_new");
        int breath = secure(context, "op_breath_mode_status");
        int interruption = UNKNOWN;
        if (focusNew != 1 && focus != 1 && breath != 1
                && zen != 0 && zen != 1 && zen != 2 && zen != 3) {
            interruption = interruptionFilter(context);
        }
        return resolve(zen, focus, focusNew, breath, interruption);
    }

    /** Health 6.9.40 ZenModeObserver: vendor switch 1 is on. Classic zen_mode still wins when those keys are absent. */
    public static int resolve(int zen, int focus, int focusNew, int breath, int interruptionFilter) {
        if (focusNew == 1 || focus == 1 || breath == 1) return PRIORITY;
        if (zen == 1) return PRIORITY;
        if (zen == 2) return NONE;
        if (zen == 3) return ALARMS;
        if (zen == 0) return ALL;
        return interruptionFilter;
    }

    public static String describe(Context context) {
        int zen = global(context, "zen_mode");
        int focus = secure(context, "focusmode_switch");
        int focusNew = secure(context, "focusmode_switch_new");
        int breath = secure(context, "op_breath_mode_status");
        int filter = currentFilter(context);
        return "filter=" + filter + " on=" + blocksNotifications(filter)
                + " zen=" + zen + " focus=" + focus + " focusNew=" + focusNew + " breath=" + breath;
    }

    private static int global(Context context, String key) {
        try {
            return android.provider.Settings.Global.getInt(context.getContentResolver(), key, 0);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static int secure(Context context, String key) {
        try {
            return android.provider.Settings.Secure.getInt(context.getContentResolver(), key, -1);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static int interruptionFilter(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager == null ? UNKNOWN : manager.getCurrentInterruptionFilter();
    }

    /** Priority, alarms-only, and total silence all suppress ordinary notification pushes. */
    public static boolean blocksNotifications(int filter) {
        return filter == PRIORITY || filter == NONE || filter == ALARMS;
    }

    /** ColorOS delivers one change as both a filter broadcast and a zen_mode write. */
    public static boolean repeatSync(int previousFilter, long elapsedNanos, int filter) {
        return previousFilter == filter && elapsedNanos >= 0 && elapsedNanos < 1_000_000_000L;
    }

    public static boolean acceptBandManual(boolean bandOn, int phoneFilter, long sentAtNanos, long nowNanos) {
        if (phoneFilter == UNKNOWN) return false;
        if (bandOn == blocksNotifications(phoneFilter)) return false;
        return sentAtNanos == 0 || nowNanos - sentAtNanos >= 3_000_000_000L;
    }

    public record Attempt(boolean applied, boolean policy, String via) {}

    /**
     * ColorOS keeps the status-bar switch in zen_mode. setInterruptionFilter can return
     * without writing it, and a normal app cannot write zen_mode. Callers that still
     * see applied=false retry from a system process.
     */
    public static Attempt apply(Context context, boolean on) {
        boolean policy = policyGranted(context);
        if (blocksNotifications(currentFilter(context)) == on) return new Attempt(true, policy, "already");
        if (policy) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            try {
                if (manager != null) manager.setInterruptionFilter(on ? PRIORITY : ALL);
            } catch (RuntimeException ignored) { }
            if (blocksNotifications(currentFilter(context)) == on) return new Attempt(true, true, "filter");
        }
        if (writeZen(context, on) && blocksNotifications(currentFilter(context)) == on) {
            return new Attempt(true, policy, "zen");
        }
        return new Attempt(false, policy, "none");
    }

    public static boolean policyGranted(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.isNotificationPolicyAccessGranted();
    }

    /** 0 off, 1 priority. Matches the zen values this phone reports when the tile moves. */
    public static int zenMode(boolean on) { return on ? 1 : 0; }

    private static boolean writeZen(Context context, boolean on) {
        try {
            return android.provider.Settings.Global.putInt(
                    context.getContentResolver(), "zen_mode", zenMode(on));
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
