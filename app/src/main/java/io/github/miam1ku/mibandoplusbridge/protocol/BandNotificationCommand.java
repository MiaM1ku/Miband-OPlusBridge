/* Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo.
 * Notification wire semantics adapted from Gadgetbridge XiaomiNotificationService.java
 * at commit 75f923904f8504b03fabdee0987fd1c269a92278.
 */
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Wire encoder only. Real Band 11 ordinary-notification/call display remains unverified. */
public final class BandNotificationCommand {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);
    private BandNotificationCommand() {}
    /** Field 8. Mi Fitness uses 1 for a ringing call and 3 for a call this phone placed. */
    public static final int CALL_INCOMING = 1;
    /** Answered call. The band keeps a hang-up screen instead of the incoming buttons. */
    public static final int CALL_ACTIVE = 2;
    public static final int CALL_OUTGOING = 3;

    public static XiaomiProto.Command post(String packageName, String appName, String key,
                                            int id, String title, String body,
                                            Instant when, ZoneId zone) {
        if (packageName == null || packageName.isBlank() || appName == null || appName.isBlank()
                || key == null || key.isBlank() || when == null || zone == null) {
            throw new IllegalArgumentException("NOTIFICATION_IDENTITY_REQUIRED");
        }
        var notification = XiaomiProto.Notification3.newBuilder()
                .setPackage(packageName).setAppName(appName)
                .setUnknown4("")
                .setId(id).setKey(key).setOpenOnPhone(false)
                .setTimestamp(TIMESTAMP.format(when.atZone(zone)));
        if (title != null && !title.isBlank()) notification.setTitle(title);
        if (body != null && !body.isBlank()) notification.setBody(body);
        return XiaomiProto.Command.newBuilder().setType(7).setSubtype(0)
                .setNotification(XiaomiProto.Notification.newBuilder().setNotification2(
                        XiaomiProto.Notification2.newBuilder().setNotification3(notification))).build();
    }

    public static XiaomiProto.Command dismiss(String packageName, String key, int id) {
        if (packageName == null || packageName.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("NOTIFICATION_IDENTITY_REQUIRED");
        }
        var target = XiaomiProto.NotificationId.newBuilder().setId(id)
                .setPackage(packageName).setKey(key);
        return XiaomiProto.Command.newBuilder().setType(7).setSubtype(1)
                .setNotification(XiaomiProto.Notification.newBuilder().setNotificationDismiss(
                        XiaomiProto.NotificationDismiss.newBuilder().addNotificationId(target))).build();
    }

    public static XiaomiProto.Command incomingCall(String displayName,
                                                    Instant when, ZoneId zone) {
        return incomingCall(displayName, null, when, zone, false);
    }

    public static XiaomiProto.Command incomingCall(String displayName, String number,
                                                    Instant when, ZoneId zone, boolean repliesAllowed) {
        return call(displayName, number, CALL_INCOMING, when, zone, repliesAllowed);
    }

    public static XiaomiProto.Command call(String displayName, String number, int callState,
                                            Instant when, ZoneId zone, boolean repliesAllowed) {
        if (when == null || zone == null) throw new IllegalArgumentException("CALL_TIME_REQUIRED");
        if (callState != CALL_INCOMING && callState != CALL_ACTIVE && callState != CALL_OUTGOING) {
            throw new IllegalArgumentException("CALL_STATE");
        }
        boolean reply = callState == CALL_INCOMING && repliesAllowed && usableNumber(number);
        String title = displayName != null && !displayName.isBlank() ? displayName : "?";
        String body = reply ? number.trim() : switch (callState) {
            case CALL_ACTIVE -> "通话中";
            case CALL_OUTGOING -> "去电";
            default -> "?";
        };
        var notification = XiaomiProto.Notification3.newBuilder().setPackage("phone").setAppName("phone")
                .setId(0).setUnknown4("").setCallState(callState).setRepliesAllowed(reply)
                .setTimestamp(TIMESTAMP.format(when.atZone(zone)))
                .setTitle(title).setBody(body);
        return XiaomiProto.Command.newBuilder().setType(7).setSubtype(0)
                .setNotification(XiaomiProto.Notification.newBuilder().setNotification2(
                        XiaomiProto.Notification2.newBuilder().setNotification3(notification))).build();
    }

    public static boolean isCall(XiaomiProto.Notification3 data) {
        return data != null && data.getCallState() != 0;
    }

    public static boolean usableNumber(String number) {
        if (number == null) return false;
        String compact = number.replaceAll("[\\s()-]", "");
        if (compact.length() < 3 || compact.length() > 20 || !compact.matches("\\+?\\d+")) return false;
        return switch (compact) {
            case "110", "119", "120", "112", "911" -> false;
            default -> true;
        };
    }

    public static XiaomiProto.Command smsReplyAck(boolean success) {
        return XiaomiProto.Command.newBuilder().setType(7).setSubtype(14)
                .setNotification(XiaomiProto.Notification.newBuilder()
                        .setNotificationReplyStatus(success ? 0 : 1)).build();
    }

    public static XiaomiProto.Command iconQueryReply(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException("NOTIFICATION_IDENTITY_REQUIRED");
        }
        return XiaomiProto.Command.newBuilder().setType(7).setSubtype(15)
                .setNotification(XiaomiProto.Notification.newBuilder().setNotificationIconReply(
                        XiaomiProto.NotificationIconPackage.newBuilder().setPackage(packageName))).build();
    }

    public static XiaomiProto.Command endCall() {
        // The pinned upstream sends id 0/package phone for a call-end dismissal.
        return XiaomiProto.Command.newBuilder().setType(7).setSubtype(1)
                .setNotification(XiaomiProto.Notification.newBuilder().setNotificationDismiss(
                        XiaomiProto.NotificationDismiss.newBuilder().addNotificationId(
                                XiaomiProto.NotificationId.newBuilder().setId(0).setPackage("phone")))).build();
    }

    /** The limit is the negotiated plaintext budget, including the entire command envelope. */
    public static XiaomiProto.Command fitToPayload(XiaomiProto.Command command, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("NOTIFICATION_PAYLOAD_LIMIT");
        if (command.getSerializedSize() <= limit) return command;
        if (command.getType() != 7 || command.getSubtype() != 0
                || !command.getNotification().getNotification2().hasNotification3()) {
            throw new IllegalArgumentException("NOTIFICATION_IDENTITY_TOO_LARGE");
        }
        var original = command.getNotification().getNotification2().getNotification3();
        var data = original.toBuilder().clearTitle().clearBody();
        if (replaceContent(command, data.build()).getSerializedSize() > limit) {
            throw new IllegalArgumentException("NOTIFICATION_IDENTITY_TOO_LARGE");
        }
        fitField(command, data, original.getTitle(), true, limit);
        fitField(command, data, original.getBody(), false, limit);
        return replaceContent(command, data.build());
    }

    private static void fitField(XiaomiProto.Command command, XiaomiProto.Notification3.Builder data,
                                 String text, boolean title, int limit) {
        int low = 0;
        int high = text.codePointCount(0, text.length());
        while (low < high) {
            int count = low + (high - low + 1) / 2;
            String prefix = text.substring(0, text.offsetByCodePoints(0, count));
            if (title) data.setTitle(prefix); else data.setBody(prefix);
            if (replaceContent(command, data.build()).getSerializedSize() <= limit) low = count;
            else high = count - 1;
        }
        String prefix = text.substring(0, text.offsetByCodePoints(0, low));
        if (title) {
            if (low == 0) data.clearTitle(); else data.setTitle(prefix);
        } else {
            if (low == 0) data.clearBody(); else data.setBody(prefix);
        }
    }

    private static XiaomiProto.Command replaceContent(XiaomiProto.Command command,
                                                       XiaomiProto.Notification3 data) {
        return command.toBuilder().setNotification(command.getNotification().toBuilder()
                .setNotification2(command.getNotification().getNotification2().toBuilder()
                        .setNotification3(data))).build();
    }
}
