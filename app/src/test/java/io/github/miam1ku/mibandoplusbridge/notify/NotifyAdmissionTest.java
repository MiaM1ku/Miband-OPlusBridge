// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NotifyAdmissionTest {
    @Test public void emptySelectionAllowsEveryPackageAndASavedSelectionDoesNot() {
        assertTrue(NotifyAdmission.packageAllowed(Set.of(), "com.tencent.mm"));
        assertFalse(NotifyAdmission.packageAllowed(Set.of("com.tencent.mm"), "com.android.mms"));
        assertTrue(NotifyAdmission.packageAllowed(Set.of("com.tencent.mm"), "com.tencent.mm"));
        assertFalse(NotifyAdmission.packageAllowed(Set.of(), " "));
    }

    @Test public void healthDenyIsRememberedAndAnUnknownPackageStaysOpen() {
        assertTrue(NotifyAdmission.healthAllows(false, false, Set.of(), "com.tencent.mm"));
        assertFalse(NotifyAdmission.healthAllows(true, false, Set.of(), "com.tencent.mm"));
        assertFalse(NotifyAdmission.healthAllows(true, true, Set.of("com.android.mms"), "com.android.mms"));
        assertTrue(NotifyAdmission.healthAllows(true, true, Set.of("com.android.mms"), "com.tencent.mm"));
    }

    @Test public void screenOnPushBlocksOnlyAnUnlockedLitScreen() {
        assertFalse(NotifyAdmission.screenBlocks(true, true, false));
        assertTrue(NotifyAdmission.screenBlocks(false, true, false));
        assertFalse(NotifyAdmission.screenBlocks(false, true, true));
        assertFalse(NotifyAdmission.screenBlocks(false, false, false));
    }

    @Test public void holdSurvivesUntilTheSessionAndDoesNotReplayAfterDrain() {
        NotifyHold hold = new NotifyHold();
        NotificationRelay.Event first = event("old");
        hold.put(first);
        hold.put(event("new"));
        assertTrue(hold.contains("old"));
        assertEquals("new", hold.drain().get(1).key());
        assertFalse(hold.contains("old"));
        hold.put(event("gone"));
        hold.remove("gone");
        assertTrue(hold.drain().isEmpty());
    }

    @Test public void holdDropsTheOldestWhenFull() {
        NotifyHold hold = new NotifyHold();
        for (int i = 0; i < NotifyHold.CAPACITY + 1; i++) hold.put(event("k" + i));
        assertFalse(hold.contains("k0"));
        assertTrue(hold.contains("k" + NotifyHold.CAPACITY));
    }

    private static NotificationRelay.Event event(String key) {
        return new NotificationRelay.Event("pkg", "App", key, "Title", "body",
                null, null, Instant.EPOCH.toEpochMilli(), 1, false, false, false, 3);
    }

    @Test public void samePostTwiceIsOneDeliveryAndAnEditIsAnother() {
        NotifyDedupe dedupe = new NotifyDedupe();
        var first = io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.post(
                "com.tencent.mm", "微信", "key", 1, "标题", "正文", Instant.EPOCH, ZoneId.of("UTC"));
        var edit = io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.post(
                "com.tencent.mm", "微信", "key", 1, "标题", "新正文", Instant.EPOCH, ZoneId.of("UTC"));
        assertTrue(dedupe.first(NotifyDedupe.identity(first), 0));
        assertFalse(dedupe.first(NotifyDedupe.identity(first), 500));
        assertTrue(dedupe.first(NotifyDedupe.identity(edit), 500));
        assertTrue(dedupe.first(NotifyDedupe.identity(first), NotifyDedupe.WINDOW_MS + 1));
    }
}
