// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class BandNotificationCommandTest {
    @Test public void chineseNotificationAndDismissalShareTheExactHandle() {
        String key = "0|com.example.allowed|123|null|event";
        var post = BandNotificationCommand.post("com.example.allowed", "测试应用", key,
                3281, "测试手环11：中文 ABC 123", "内容", Instant.parse("2026-09-23T18:32:06Z"),
                ZoneId.of("Asia/Shanghai"));
        var data = post.getNotification().getNotification2().getNotification3();
        assertEquals(7, post.getType());
        assertEquals(0, post.getSubtype());
        assertEquals("测试手环11：中文 ABC 123", data.getTitle());
        assertEquals("20260924T023206", data.getTimestamp());
        assertEquals(key, data.getKey());
        assertFalse(data.getOpenOnPhone());
        var delete = BandNotificationCommand.dismiss("com.example.allowed", key, 3281)
                .getNotification().getNotificationDismiss().getNotificationId(0);
        assertEquals(data.getId(), delete.getId());
        assertEquals(data.getPackage(), delete.getPackage());
        assertEquals(data.getKey(), delete.getKey());
    }

    @Test public void callWithoutNumberDoesNotInventIdentityOrReplyPermission() {
        var incoming = BandNotificationCommand.incomingCall(null,
                Instant.parse("2026-09-23T18:32:06Z"), ZoneId.of("Asia/Shanghai"))
                .getNotification().getNotification2().getNotification3();
        assertTrue(incoming.getIsCall());
        assertFalse(incoming.getRepliesAllowed());
        assertEquals("?", incoming.getTitle());
        assertEquals("?", incoming.getBody());
        assertEquals("", incoming.getUnknown4());
        var end = BandNotificationCommand.endCall().getNotification().getNotificationDismiss().getNotificationId(0);
        assertEquals("phone", end.getPackage());
        assertEquals(0, end.getId());
    }

    @Test public void smsReplyRequiresUsableNumberAndDoesNotAllowEmergency() {
        var allowed = BandNotificationCommand.incomingCall("张三", "+86 13800138000",
                Instant.parse("2026-09-23T18:32:06Z"), ZoneId.of("Asia/Shanghai"), true)
                .getNotification().getNotification2().getNotification3();
        assertTrue(allowed.getRepliesAllowed());
        assertEquals("+86 13800138000", allowed.getBody());
        var blocked = BandNotificationCommand.incomingCall("来电", "110",
                Instant.EPOCH, ZoneId.of("UTC"), true)
                .getNotification().getNotification2().getNotification3();
        assertFalse(blocked.getRepliesAllowed());
        assertEquals("?", blocked.getBody());
        assertFalse(BandNotificationCommand.usableNumber("?"));
    }

    @Test public void missingStableNotificationKeyCannotBeForwarded() {
        assertThrows(IllegalArgumentException.class,
                () -> BandNotificationCommand.post("com.example.allowed", "测试应用", "", 1,
                        "标题", "内容", Instant.EPOCH, ZoneId.of("UTC")));
    }

    @Test public void payloadFittingKeepsIdentityAndWholeUnicodeCodePoints() {
        String title = "来电\ud83d\udcf1".repeat(30);
        String body = "正文\ud83d\ude03".repeat(100);
        var original = BandNotificationCommand.post("allowed", "App", "stable-key", 33,
                title, body, Instant.EPOCH, ZoneId.of("UTC"));
        var fitted = BandNotificationCommand.fitToPayload(original, 180);
        assertTrue(fitted.getSerializedSize() <= 180);
        var data = fitted.getNotification().getNotification2().getNotification3();
        assertEquals("stable-key", data.getKey());
        assertEquals(33, data.getId());
        assertEquals("App", data.getAppName());
        assertTrue(title.startsWith(data.getTitle()));
        assertTrue(body.startsWith(data.getBody()));
        assertFalse(data.getTitle().isEmpty());
        assertFalse(Character.isHighSurrogate(data.getTitle().charAt(data.getTitle().length() - 1)));
        assertEquals(data.getTitle(), new String(data.getTitle().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void identityCannotBeTruncatedToHideOversizeHandle() {
        var original = BandNotificationCommand.post("allowed", "App", "key".repeat(200), 1,
                "title", "body", Instant.EPOCH, ZoneId.of("UTC"));
        assertThrows(IllegalArgumentException.class, () -> BandNotificationCommand.fitToPayload(original, 100));
    }

    @Test public void exactPayloadBoundaryPreservesContent() {
        var original = BandNotificationCommand.post("allowed", "App", "key", 1,
                "\ud83d\ude03", "\ud83d\ude03", Instant.EPOCH, ZoneId.of("UTC"));
        assertEquals(original, BandNotificationCommand.fitToPayload(original, original.getSerializedSize()));
        var fitted = BandNotificationCommand.fitToPayload(original, original.getSerializedSize() - 1);
        assertEquals("\ud83d\ude03", fitted.getNotification().getNotification2().getNotification3().getTitle());
        assertFalse(fitted.getNotification().getNotification2().getNotification3().hasBody());
    }
}
