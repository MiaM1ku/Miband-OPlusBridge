// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.content.Context;
import android.service.notification.StatusBarNotification;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;
import io.github.miam1ku.mibandoplusbridge.protocol.BandAlarmCommand;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** OPPO clock ringing is a foreground notification. The band gets the phone-alarm packet, not a normal push. */
public final class PhoneAlarmNotice {
    public static final String CLOCK = "com.coloros.alarmclock";
    private static final long DEDUPE_MS = 1500;
    private static final Set<String> ringing = ConcurrentHashMap.newKeySet();
    /** AlarmSnoozeService foreground id. A countdown, not a ringing alarm. */
    static final int SNOOZE_NOTIFICATION_ID = -1018;
    private static long lastSentAt;
    private static int lastSentOp = -1;
    private static long clockRingAt;

    private PhoneAlarmNotice() {}

    public static boolean ringing(String pkg, int flags, String channel, int id, boolean fullScreen) {
        if (!CLOCK.equals(pkg) || channel == null || channel.contains("next.alarm")) return false;
        if (id == SNOOZE_NOTIFICATION_ID) return false;
        boolean service = (flags & android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0;
        // ColorOS 17 posts the klaxon on this channel. The snooze countdown uses the same channel at -1018.
        if ("clock_foreground_service_channel_id".equals(channel)) return service;
        if (id == Integer.MIN_VALUE || id == -1017) return true;
        if (!channel.contains("alarmclock")) return false;
        return fullScreen || service;
    }

    public static boolean ringing(StatusBarNotification item) {
        if (item == null || item.getNotification() == null) return false;
        android.app.Notification notification = item.getNotification();
        return ringing(item.getPackageName(), notification.flags, notification.getChannelId(),
                item.getId(), notification.fullScreenIntent != null);
    }

    public static void posted(Context context, StatusBarNotification item) {
        if (!ringing(item)) return;
        ringing.add(item.getKey());
        String title = item.getNotification().extras == null ? ""
                : item.getNotification().extras.getString(android.app.Notification.EXTRA_TITLE, "");
        send(context, 0, title);
    }

    public static void removed(Context context, StatusBarNotification item) {
        if (item == null || !ringing.remove(item.getKey())) return;
        send(context, 1, "");
    }

    /** The clock klaxon just alerted the band. Notification echoes must not replace that alarm id. */
    public static void noteClockRing() {
        clockRingAt = android.os.SystemClock.elapsedRealtime();
    }

    public static boolean claim(int op) {
        if (op != 0 && op != 1 && op != 2) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (PhoneAlarmNotice.class) {
            if (op == lastSentOp && now - lastSentAt < DEDUPE_MS) return false;
            lastSentOp = op;
            lastSentAt = now;
            return true;
        }
    }

    public static void send(Context context, int op, String label) {
        if (context == null) return;
        if (op == 0 && android.os.SystemClock.elapsedRealtime() - clockRingAt < 15_000L) return;
        if (!claim(op)) return;
        String shown = label == null ? "" : label.replace('\n', ' ').replace('\r', ' ').trim();
        if (shown.length() > 40) shown = shown.substring(0, 40);
        SessionLog.line(context, "ALARM_PHONE op=" + op);
        try {
            BandLiveService.sendSessionCommand(BandAlarmCommand.operation(op, 1,
                    op == 0 ? (int) (System.currentTimeMillis() / 1000L) : -1, shown));
        } catch (RuntimeException failure) {
            SessionLog.line(context, "ALARM_PHONE_FAILED " + failure.getClass().getSimpleName());
        }
    }
}
