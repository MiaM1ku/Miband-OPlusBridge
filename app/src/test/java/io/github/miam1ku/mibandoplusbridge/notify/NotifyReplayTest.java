// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NotifyReplayTest {
    private static final Instant WHEN = Instant.parse("2026-10-01T00:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test public void sameNotificationWaitsOnceAndADismissBeforeSendDropsIt() {
        NotifyReplay hold = new NotifyReplay();
        var first = hold.add(BandNotificationCommand.post(
                "com.example", "Example", "k", 1, "Hi", "one", WHEN, ZONE), 0);
        var second = hold.add(BandNotificationCommand.post(
                "com.example", "Example", "k", 1, "Hi", "one", WHEN, ZONE), 1_000);
        assertEquals(1, hold.size());
        var dismissed = hold.add(BandNotificationCommand.dismiss("com.example", "k", 1), 2_000);
        assertEquals(0, hold.size());
        assertTrue(first.isDone() && !first.isCompletedExceptionally());
        assertTrue(second.isDone() && !second.isCompletedExceptionally());
        assertTrue(dismissed.isDone() && !dismissed.isCompletedExceptionally());
    }

    @Test public void dismissWithoutAHeldPostIsStillSent() {
        NotifyReplay hold = new NotifyReplay();
        hold.add(BandNotificationCommand.dismiss("com.example", "k", 1), 0);
        assertEquals(1, hold.poll(0).size());
    }

    @Test public void callsExpireBeforeOrdinaryNotifications() {
        NotifyReplay hold = new NotifyReplay();
        CompletableFuture<Void> call = hold.add(BandNotificationCommand.incomingCall("Ada", WHEN, ZONE), 0);
        CompletableFuture<Void> note = hold.add(BandNotificationCommand.post(
                "com.example", "Example", "k", 1, "Hi", "one", WHEN, ZONE), 0);
        assertEquals(1, hold.expire(NotifyReplay.CALL_MS));
        assertTrue(call.isCompletedExceptionally());
        assertFalse(note.isDone());
        assertEquals(1, hold.expire(NotifyReplay.NOTIFICATION_MS));
        assertTrue(note.isCompletedExceptionally());
        assertTrue(hold.poll(NotifyReplay.NOTIFICATION_MS).isEmpty());
    }

    @Test public void endingAHeldCallDropsIt() {
        NotifyReplay hold = new NotifyReplay();
        CompletableFuture<Void> call = hold.add(BandNotificationCommand.incomingCall("Ada", WHEN, ZONE), 0);
        CompletableFuture<Void> end = hold.add(BandNotificationCommand.endCall(), 1_000);
        assertEquals(0, hold.size());
        assertTrue(call.isDone() && !call.isCompletedExceptionally());
        assertTrue(end.isDone() && !end.isCompletedExceptionally());
    }

    @Test public void oldestIsDroppedWhenFull() {
        NotifyReplay hold = new NotifyReplay();
        CompletableFuture<Void> oldest = null;
        for (int i = 0; i < NotifyReplay.LIMIT; i++) {
            CompletableFuture<Void> added = hold.add(BandNotificationCommand.post(
                    "com.example", "Example", "k" + i, i, "Hi", "n" + i, WHEN, ZONE), 0);
            if (i == 0) oldest = added;
        }
        hold.add(BandNotificationCommand.post(
                "com.example", "Example", "fresh", 99, "Hi", "new", WHEN, ZONE), 0);
        assertEquals(NotifyReplay.LIMIT, hold.size());
        assertEquals("NOTIFY_HOLD_DROPPED", causeMessage(oldest));
    }

    private static String causeMessage(CompletableFuture<Void> future) {
        try {
            future.get(1, TimeUnit.SECONDS);
            return "";
        } catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return cause.getMessage();
        }
    }
}
