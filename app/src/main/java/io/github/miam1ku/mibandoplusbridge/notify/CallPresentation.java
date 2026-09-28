// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.app.Notification;

/** Telephony OFFHOOK covers both dialing and the answered call. The in-call notification does not. */
public final class CallPresentation {
    private CallPresentation() {}

    public static boolean connected(Notification notification) {
        if (notification == null || notification.extras == null) return false;
        boolean chronometer = notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false);
        String text = NotificationRelay.sanitize(notification.extras.getCharSequence(Notification.EXTRA_TEXT));
        String title = NotificationRelay.sanitize(notification.extras.getCharSequence(Notification.EXTRA_TITLE));
        return connected(chronometer, text, title);
    }

    /** A running timer or an explicit in-call label. Dialing copy such as 正在呼叫 stays outgoing. */
    public static boolean connected(boolean showChronometer, String text, String title) {
        return showChronometer || live(text) || live(title);
    }

    private static boolean live(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.contains("通话中") || text.contains("正在通话")) return true;
        return text.matches("\\d{1,2}:\\d{2}(:\\d{2})?");
    }
}
