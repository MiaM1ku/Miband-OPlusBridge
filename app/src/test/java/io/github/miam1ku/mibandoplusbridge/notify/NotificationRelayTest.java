// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NotificationRelayTest {
    private static final class Harness {
        final List<XiaomiProto.Command> commands = new ArrayList<>();
        final List<CompletableFuture<Void>> receipts = new ArrayList<>();
        final NotificationRelay relay = new NotificationRelay(command -> {
            commands.add(command);
            CompletableFuture<Void> receipt = new CompletableFuture<>();
            receipts.add(receipt); return receipt;
        }, () -> 512, Runnable::run);
        Harness() {
            relay.configure(true, Set.of("allowed"), true);
            relay.connected(Map.of());
        }
        void ack(int index) { receipts.get(index).complete(null); }
        XiaomiProto.Notification3 data(int index) {
            return commands.get(index).getNotification().getNotification2().getNotification3();
        }
    }
    private static NotificationRelay.Event event(String key, String body, long time) {
        return new NotificationRelay.Event("allowed", "App", key, "Title", body,
                null, null, time, 1, false, false, false, 3);
    }

    @Test public void onlyAllowedAlertingNonSecretContentIsForwarded() {
        Harness h = new Harness();
        h.relay.posted(new NotificationRelay.Event("other", "App", "a", "Title", "secret", null, null, 1, 1, false, false, false, 3));
        h.relay.posted(new NotificationRelay.Event("allowed", "App", "b", "Title", "secret", null, null, 1, -1, false, false, false, 3));
        h.relay.posted(new NotificationRelay.Event("allowed", "App", "c", "Title", "low", null, null, 1, 1, false, false, false, 2));
        h.relay.posted(new NotificationRelay.Event("allowed", "App", "d", "Title", "fgs", null, null, 1, 1, false, true, false, 3));
        h.relay.posted(new NotificationRelay.Event("allowed", "App", "e", "Title", "summary", null, null, 1, 1, false, false, true, 3));
        assertTrue(h.commands.isEmpty());
        h.relay.posted(event("f", "visible", 1));
        assertEquals("visible", h.data(0).getBody());
    }

    @Test public void baselineIsNotReplayedButLaterUpdateCanBeDelivered() {
        Harness h = new Harness();
        h.relay.connected(Map.of("key", 1L));
        h.relay.posted(event("key", "old", 1));
        h.relay.removed("key");
        assertTrue(h.commands.isEmpty());
        h.relay.posted(event("key", "new", 2));
        assertEquals("new", h.data(0).getBody());
    }

    @Test public void updatesCoalesceWhileInFlightAndKeepTheHandle() {
        Harness h = new Harness();
        h.relay.posted(event("key", "A", 1));
        h.relay.posted(event("key", "B", 2));
        h.relay.posted(event("key", "C", 3));
        assertEquals(1, h.commands.size());
        h.ack(0);
        assertEquals("C", h.data(1).getBody());
        assertEquals(h.data(0).getId(), h.data(1).getId());
        h.ack(1);
        h.relay.posted(event("key", "C", 4));
        assertEquals(2, h.commands.size());
    }

    @Test public void removeBeforeAckClearsOnlyAfterSuccessfulDelivery() {
        Harness h = new Harness();
        h.relay.posted(event("key", "A", 1));
        h.relay.removed("key");
        assertEquals(1, h.commands.size());
        h.ack(0);
        var clear = h.commands.get(1).getNotification().getNotificationDismiss().getNotificationId(0);
        assertEquals(h.data(0).getId(), clear.getId());
        assertEquals("key", clear.getKey());
        h.ack(1);
        h.relay.removed("key");
        assertEquals(2, h.commands.size());
    }

    @Test public void failedPostAndUnknownRemovalNeverClearUndeliveredNotification() {
        Harness h = new Harness();
        h.relay.removed("unknown");
        h.relay.posted(event("key", "A", 1));
        h.relay.removed("key");
        h.receipts.get(0).completeExceptionally(new IllegalStateException());
        assertEquals(1, h.commands.size());
        assertEquals("NOTIFICATION_TRANSPORT_FAILED", h.relay.lastFailureCode());
    }

    @Test public void disconnectDiscardsUpdatesAndStaleAcknowledgements() {
        Harness h = new Harness();
        h.relay.posted(event("key", "A", 1));
        h.relay.posted(event("key", "B", 2));
        h.relay.disconnected();
        h.relay.posted(event("offline", "private", 3));
        h.relay.connected(Map.of("offline", 3L));
        h.ack(0);
        h.relay.posted(event("offline", "private", 3));
        assertEquals(1, h.commands.size());
        h.relay.posted(event("fresh", "C", 4));
        assertEquals("C", h.data(1).getBody());
    }

    @Test public void lockedPrivateUsesPublicContentOrTitleOnly() {
        Harness h = new Harness();
        h.relay.posted(new NotificationRelay.Event("allowed", "App", "a", "Private title", "Private body", "Public title", "Public body", 1, 0, true, false, false, 3));
        assertEquals("Public title", h.data(0).getTitle());
        assertEquals("Public body", h.data(0).getBody());
        h.relay.posted(new NotificationRelay.Event("allowed", "App", "b", "Title only", "Private body", null, null, 1, 0, true, false, false, 3));
        assertEquals("Title only", h.data(1).getTitle());
        assertFalse(h.data(1).hasBody());
        h.relay.configure(true, Set.of("allowed"), false);
        h.relay.posted(event("c", "hidden by setting", 2));
        assertFalse(h.data(2).hasBody());
    }

    @Test public void revokedSettingsDropPendingUpdatesEvenAfterAck() {
        Harness h = new Harness();
        h.relay.posted(event("key", "A", 1));
        h.relay.posted(event("key", "B", 2));
        h.relay.configure(false, Set.of("allowed"), true);
        h.ack(0);
        h.relay.posted(event("new", "C", 3));
        assertEquals(1, h.commands.size());
    }

    @Test public void repostDuringClearWaitsAndRestoresLatestContent() {
        Harness h = new Harness();
        h.relay.posted(event("key", "A", 1)); h.ack(0);
        h.relay.removed("key");
        h.relay.posted(event("key", "B", 2));
        assertEquals(2, h.commands.size());
        h.ack(1);
        assertEquals("B", h.data(2).getBody());
        assertEquals(h.data(0).getId(), h.data(2).getId());
    }

    @Test public void lockingDropsQueuedContentButRetainsDeliveredRemovalHandle() {
        Harness h = new Harness();
        h.relay.posted(event("key", "already delivered", 1));
        h.ack(0);
        h.relay.posted(event("key", "queued private update", 2));
        h.relay.posted(event("key", "newer private update", 3));
        h.relay.cancelPending();
        h.receipts.get(1).completeExceptionally(new IllegalStateException());
        assertEquals(2, h.commands.size());
        h.relay.removed("key");
        var removed = h.commands.get(2).getNotification().getNotificationDismiss().getNotificationId(0);
        assertEquals(h.data(0).getId(), removed.getId());
        assertEquals("key", removed.getKey());
    }

    @Test public void fullMemoryDoesNotEvictDeliveredHandlesNeededForRemoval() {
        Harness h = new Harness();
        for (int i = 0; i < NotificationRelay.CAPACITY; i++) {
            h.relay.posted(event("key" + i, "body", i + 1)); h.ack(i);
        }
        h.relay.posted(event("overflow", "body", 1000));
        assertEquals(NotificationRelay.CAPACITY, h.commands.size());
        h.relay.removed("key0");
        assertEquals("key0", h.commands.get(NotificationRelay.CAPACITY).getNotification()
                .getNotificationDismiss().getNotificationId(0).getKey());
    }
}
