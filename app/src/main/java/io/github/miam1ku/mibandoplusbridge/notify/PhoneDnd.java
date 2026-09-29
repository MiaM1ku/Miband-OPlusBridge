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
        int zen = 0;
        try {
            zen = android.provider.Settings.Global.getInt(context.getContentResolver(), "zen_mode", 0);
        } catch (RuntimeException ignored) { }
        // ColorOS often updates zen_mode from SystemUI without a filter broadcast.
        if (zen == 1) return PRIORITY;
        if (zen == 2) return NONE;
        if (zen == 3) return ALARMS;
        if (zen == 0) return ALL;
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

    public static boolean apply(Context context, boolean on) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.isNotificationPolicyAccessGranted()) return false;
        int want = on ? PRIORITY : ALL;
        if (currentFilter(context) == want) return true;
        manager.setInterruptionFilter(want);
        return currentFilter(context) == want || blocksNotifications(currentFilter(context)) == on;
    }
}
