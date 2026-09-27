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
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager == null ? UNKNOWN : manager.getCurrentInterruptionFilter();
    }

    /** Priority, alarms-only, and total silence all suppress ordinary notification pushes. */
    public static boolean blocksNotifications(int filter) {
        return filter == PRIORITY || filter == NONE || filter == ALARMS;
    }
}
