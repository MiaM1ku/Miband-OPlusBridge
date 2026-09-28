// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.app.Notification;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;
import java.util.regex.Pattern;

/**
 * ColorOS posts one CATEGORY_CALL notification for dialing, ringing, and the connected call.
 * Telephony OFFHOOK does not split dialing from answered, and the call switch may be off:
 * the notification itself has to choose incoming, outgoing, or active.
 */
public final class CallPresentation {
    public enum Kind { UNKNOWN, INCOMING, OUTGOING, ACTIVE }

    private static final Pattern ELAPSED = Pattern.compile("(?:^|\\D)\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\D|$)");
    /** CallStyle extra. 1 incoming, 2 ongoing (dialing or active), 3 screening. */
    private static final String EXTRA_CALL_TYPE = "android.callType";
    private static final String LIVE_ALERT_CARD = "oplus.livealert.card";
    private static final String LIVE_ALERT_CAPSULE = "oplus.livealert.capsule";
    private static final String CHRONOMETER_NODE = "\"desc\":\"Chronometer\"";

    private CallPresentation() {}

    public static boolean connected(Notification notification) {
        return kind(notification) == Kind.ACTIVE;
    }

    /** A running timer or an explicit in-call label. Dialing copy such as 正在呼叫 stays outgoing. */
    public static boolean connected(boolean showChronometer, String text, String title) {
        return kind(showChronometer, false, 0, text, title) == Kind.ACTIVE;
    }

    public static Kind kind(Notification notification) {
        if (notification == null) return Kind.UNKNOWN;
        Bundle extras = notification.extras;
        boolean chronometer = extras != null && extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false);
        int callType = extras == null ? 0 : extras.getInt(EXTRA_CALL_TYPE, 0);
        return kind(chronometer, liveAlertChronometer(extras), callType,
                text(extras, Notification.EXTRA_TEXT),
                text(extras, Notification.EXTRA_TITLE),
                text(extras, Notification.EXTRA_SUB_TEXT),
                text(extras, Notification.EXTRA_BIG_TEXT),
                text(extras, Notification.EXTRA_SUMMARY_TEXT),
                NotificationRelay.sanitize(notification.tickerText),
                extras == null ? "" : extras.getString(LIVE_ALERT_CARD),
                extras == null ? "" : extras.getString(LIVE_ALERT_CAPSULE));
    }

    public static Kind kind(boolean showChronometer, boolean liveAlertChronometer, int callType, String... fields) {
        if (showChronometer || liveAlertChronometer) return Kind.ACTIVE;
        boolean outgoing = false;
        boolean incoming = false;
        if (fields != null) for (String field : fields) {
            String text = field == null ? "" : field.trim();
            if (text.isEmpty()) continue;
            if (active(text)) return Kind.ACTIVE;
            if (dialing(text)) outgoing = true;
            else if (ringing(text)) incoming = true;
        }
        if (incoming || callType == 1 || callType == 3) return Kind.INCOMING;
        if (outgoing || callType == 2) return Kind.OUTGOING;
        return Kind.UNKNOWN;
    }

    public static int wire(Notification notification) {
        return wire(kind(notification));
    }

    public static int wire(Kind kind) {
        return switch (kind == null ? Kind.UNKNOWN : kind) {
            case OUTGOING -> BandNotificationCommand.CALL_OUTGOING;
            case ACTIVE -> BandNotificationCommand.CALL_ACTIVE;
            case INCOMING -> BandNotificationCommand.CALL_INCOMING;
            case UNKNOWN -> 0;
        };
    }

    public static Kind kind(int wire) {
        return switch (wire) {
            case BandNotificationCommand.CALL_OUTGOING -> Kind.OUTGOING;
            case BandNotificationCommand.CALL_ACTIVE -> Kind.ACTIVE;
            default -> Kind.INCOMING;
        };
    }

    /** A state label is not the person being called. */
    public static String displayName(String title, Kind kind) {
        String clean = title == null ? "" : title.trim();
        Kind shown = kind == null ? Kind.UNKNOWN : kind;
        if (clean.isEmpty() || stateCopy(clean)) {
            return switch (shown) {
                case OUTGOING -> "去电";
                case ACTIVE -> "通话中";
                case INCOMING, UNKNOWN -> "来电";
            };
        }
        return clean;
    }

    private static boolean stateCopy(String text) {
        return active(text) || dialing(text) || ringing(text);
    }

    private static boolean liveAlertChronometer(Bundle extras) {
        if (extras == null) return false;
        return chronometerCard(extras.getString(LIVE_ALERT_CARD))
                || chronometerCard(extras.getString(LIVE_ALERT_CAPSULE));
    }

    static boolean chronometerCard(String json) {
        return json != null && json.contains(CHRONOMETER_NODE);
    }

    private static String text(Bundle extras, String key) {
        return extras == null ? "" : NotificationRelay.sanitize(extras.getCharSequence(key));
    }

    private static boolean active(String text) {
        if (text.contains("通话中") || text.contains("正在通话") || text.contains("正在通話")
                || text.contains("通话保持") || text.contains("保持通话") || text.contains("保持通話")
                || text.contains("正在保持") || text.contains("保持中")
                || text.contains("高音质通话") || text.contains("高音質通話")) return true;
        String lower = text.toLowerCase();
        if (lower.contains("ongoing call") || lower.contains("on hold")) return true;
        return ELAPSED.matcher(text).find();
    }

    private static boolean dialing(String text) {
        if (text.contains("正在呼叫") || text.contains("呼叫中")
                || text.contains("正在拨号") || text.contains("正在拨打") || text.contains("正在重拨")
                || text.contains("正在撥號") || text.contains("正在撥打") || text.contains("正在重撥")
                || text.contains("等待接听") || text.contains("等待接聽")) return true;
        String lower = text.toLowerCase();
        return lower.contains("dialing") || lower.contains("dialling") || lower.contains("calling");
    }

    private static boolean ringing(String text) {
        if (text.contains("来电") || text.contains("來電")) return true;
        return text.toLowerCase().contains("incoming");
    }
}
