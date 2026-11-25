// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class NotifyCoalescerTest {
    @Test public void collapsesRapidCancelAndRepostStormOnSameKeyIntoLatestPost() {
        NotifyCoalescer coalescer = new NotifyCoalescer();
        CompletableFuture<Void> f1 = coalescer.offer(post("k1", "群聊", "消息1"), 1_000L);
        CompletableFuture<Void> d1 = coalescer.offer(dismiss("k1"), 1_020L);
        assertTrue("Transient unsent post should be completed when cancelled", f1.isDone());
        assertTrue("Transient dismiss of never-sent post should complete without wire send", d1.isDone());
        assertEquals(0, coalescer.size());

        CompletableFuture<Void> f2 = coalescer.offer(post("k1", "群聊(2条)", "消息2"), 1_100L);
        CompletableFuture<Void> f3 = coalescer.offer(post("k1", "群聊(3条)", "消息3"), 1_180L);
        assertTrue(f2.isDone());
        assertFalse(f3.isDone());
        assertNull(coalescer.pollNext(1_529L));

        NotifyCoalescer.Entry ready = coalescer.pollNext(1_530L);
        assertNotNull(ready);
        assertEquals("群聊(3条)", ready.command.getNotification().getNotification2().getNotification3().getTitle());
        ready.succeed();
        assertTrue(f3.isDone());
        assertNull(coalescer.pollNext(2_000L));
    }

    @Test public void continuousUpdatesFlushAtMaxHoldCap() {
        NotifyCoalescer coalescer = new NotifyCoalescer();
        for (int i = 0; i < 15; i++) {
            coalescer.offer(post("k1", "群聊(" + (i + 1) + "条)", "m" + i), 1_000L + i * 70L);
        }
        assertEquals(1_000L + NotifyCoalescer.MAX_HOLD_MS, coalescer.nextDueAtMs());
        NotifyCoalescer.Entry ready = coalescer.pollNext(1_000L + NotifyCoalescer.MAX_HOLD_MS);
        assertNotNull(ready);
        assertEquals("群聊(15条)", ready.command.getNotification().getNotification2().getNotification3().getTitle());
    }

    @Test public void spacesConsecutivePostsAcrossDifferentKeys() {
        NotifyCoalescer coalescer = new NotifyCoalescer();
        coalescer.offer(post("k1", "微信", "你好"), 1_000L);
        coalescer.offer(post("k2", "QQ", "在吗"), 1_050L);

        long firstDue = 1_000L + NotifyCoalescer.COALESCE_MS;
        NotifyCoalescer.Entry first = coalescer.pollNext(firstDue);
        assertNotNull(first);
        assertEquals("微信", first.command.getNotification().getNotification2().getNotification3().getTitle());

        // Second post is past its own coalesce window at firstDue+50, but must wait for POST_SPACING_MS.
        assertNull(coalescer.pollNext(firstDue + NotifyCoalescer.POST_SPACING_MS - 1));
        assertEquals(firstDue + NotifyCoalescer.POST_SPACING_MS, coalescer.nextDueAtMs());
        NotifyCoalescer.Entry second = coalescer.pollNext(firstDue + NotifyCoalescer.POST_SPACING_MS);
        assertNotNull(second);
        assertEquals("QQ", second.command.getNotification().getNotification2().getNotification3().getTitle());
    }

    @Test public void deliveredKeyDismissSurvivesAndAbsorbsCancelThenRepost() {
        NotifyCoalescer coalescer = new NotifyCoalescer();
        coalescer.offer(post("k1", "QQ", "消息1"), 1_000L);
        assertNotNull(coalescer.pollNext(1_000L + NotifyCoalescer.COALESCE_MS));

        // Cancel-then-repost within the coalesce window replaces the pending dismiss with the new post.
        coalescer.offer(dismiss("k1"), 2_000L);
        coalescer.offer(post("k1", "QQ(2条)", "消息2"), 2_080L);
        NotifyCoalescer.Entry second = coalescer.pollNext(2_080L + NotifyCoalescer.COALESCE_MS);
        assertNotNull(second);
        assertTrue(second.post);
        assertEquals("QQ(2条)", second.command.getNotification().getNotification2().getNotification3().getTitle());

        // A real dismiss with no follow-up post still flushes to the band.
        coalescer.offer(dismiss("k1"), 5_000L);
        NotifyCoalescer.Entry clear = coalescer.pollNext(5_000L + NotifyCoalescer.COALESCE_MS);
        assertNotNull(clear);
        assertFalse(clear.post);
        assertEquals(1, clear.command.getSubtype());
    }

    @Test public void callsAreNeverCoalesced() {
        var call = BandNotificationCommand.incomingCall("来电", null, Instant.EPOCH, ZoneId.of("UTC"), false);
        assertEquals("", NotifyCoalescer.slotKey(call));
        assertEquals("", NotifyCoalescer.slotKey(BandNotificationCommand.endCall()));
    }

    private static XiaomiProto.Command post(String key, String title, String body) {
        return BandNotificationCommand.post("com.tencent.mobileqq", "QQ", key, 520,
                title, body, Instant.EPOCH, ZoneId.of("UTC"));
    }

    private static XiaomiProto.Command dismiss(String key) {
        return BandNotificationCommand.dismiss("com.tencent.mobileqq", key, 520);
    }
}
