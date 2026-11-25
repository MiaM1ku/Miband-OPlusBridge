// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;
import java.time.Instant;
import java.time.ZoneId;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NotifyDedupeTest {
    @Test public void collapsesDuplicateDismissFromTwoListeners() {
        NotifyDedupe dedupe = new NotifyDedupe();
        XiaomiProto.Command dismiss = BandNotificationCommand.dismiss("com.tencent.mobileqq", "k1", 7);
        assertTrue(dedupe.first(dismiss, 1_000L));
        assertFalse(dedupe.first(dismiss, 1_050L));
    }

    @Test public void freshPostOnSameKeyAllowsSubsequentDismissWithinWindow() {
        NotifyDedupe dedupe = new NotifyDedupe();
        XiaomiProto.Command firstPost = BandNotificationCommand.post(
                "com.tencent.mobileqq", "QQ", "k1", 7, "Alice", "msg 1",
                Instant.EPOCH, ZoneId.of("UTC"));
        XiaomiProto.Command dismiss = BandNotificationCommand.dismiss("com.tencent.mobileqq", "k1", 7);
        XiaomiProto.Command secondPost = BandNotificationCommand.post(
                "com.tencent.mobileqq", "QQ", "k1", 7, "Alice", "msg 2",
                Instant.EPOCH, ZoneId.of("UTC"));

        assertTrue(dedupe.first(firstPost, 1_000L));
        assertTrue(dedupe.first(dismiss, 1_020L));
        assertTrue(dedupe.first(secondPost, 1_120L));
        assertTrue("Dismiss of the new post must not be dropped as duplicate",
                dedupe.first(dismiss, 1_140L));
    }
}
