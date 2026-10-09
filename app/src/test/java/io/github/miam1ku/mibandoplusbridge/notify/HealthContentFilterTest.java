// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HealthContentFilterTest {
    @Test public void anOrdinaryWeChatMessagePasses() {
        assertNull(HealthContentFilter.blockReason(false, null, 0, null, "msg", 0, "tag",
                "0|com.tencent.mm|1|tag|1", "微信", "你好", "message", "com.tencent.mm", false));
    }

    @Test public void healthDropsOngoingMediaProgressRankerBlankAndChannels() {
        assertEquals("content-empty", HealthContentFilter.blockReason(true, null, 0, null, null, 0,
                null, "k", "t", "b", null, "com.tencent.mm", false));
        assertEquals("content-media", HealthContentFilter.blockReason(false,
                "android.app.Notification$MediaStyle", 0, null, null, 0, null, "k", "t", "b",
                null, "com.android.music", false));
        assertEquals("content-ongoing", HealthContentFilter.blockReason(false, null, 0x22, null, null, 0,
                null, "k", "t", "b", null, "com.example", false));
        assertEquals("content-foreground", HealthContentFilter.blockReason(false, null, 0x40, null, null, 0,
                null, "k", "t", "b", null, "com.example", false));
        assertEquals("content-progress", HealthContentFilter.blockReason(false, null, 0, null, "progress", 0,
                null, "k", "t", "b", null, "com.example", false));
        assertEquals("content-ranker", HealthContentFilter.blockReason(false, null, 0, null, "msg", 0,
                "ranker_group", "k", "t", "b", null, "com.example", false));
        assertEquals("content-summary", HealthContentFilter.blockReason(false, null, 0x200, "group", "msg", 0,
                null, "k", "t", "b", null, "com.example", false));
        assertEquals("content-blank", HealthContentFilter.blockReason(false, null, 0, null, "msg", 0,
                null, "k", " ", "", null, "com.example", false));
        assertEquals("content-mms", HealthContentFilter.blockReason(false, null, 0, null, "msg", 0,
                null, "k", "短信", "", null, "com.android.mms", true));
        assertEquals("content-channel", HealthContentFilter.blockReason(false, null, 0, null, "msg", 0,
                null, "k", "微信", "提醒", "reminder_channel_id", "com.tencent.mm", false));
    }
}
