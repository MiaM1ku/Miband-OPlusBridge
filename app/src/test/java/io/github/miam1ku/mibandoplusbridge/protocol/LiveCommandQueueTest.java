// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class LiveCommandQueueTest {
    private static final long WAIT_SECONDS = 3;
    private static final long SESSION_TIMEOUT_MS = 30_000;

    @Test public void requestWaitsForResponseAfterItsAck() throws Exception {
        try (Harness h = new Harness()) {
            var response = command(2, 1).toBuilder().setStatus(0).build();
            var result = h.queue.request(command(2, 1), 2, 1);
            Sent sent = h.next();
            h.queue.onAck(sent.sequence);
            h.barrier();
            assertFalse(result.toCompletableFuture().isDone());
            h.queue.onCommand(response);
            assertEquals(response, await(result));
        }
    }

    @Test public void requestWaitsForItsAckAfterResponseAndIgnoresUnrelatedTraffic() throws Exception {
        try (Harness h = new Harness()) {
            var response = command(10, 5).toBuilder().setStatus(0).build();
            var result = h.queue.request(command(10, 5), 10, 5);
            Sent sent = h.next();
            h.queue.onCommand(command(10, 4));
            h.queue.onAck((sent.sequence + 73) & 255);
            h.barrier();
            assertFalse(result.toCompletableFuture().isDone());
            h.queue.onCommand(response);
            h.barrier();
            assertFalse(result.toCompletableFuture().isDone());
            h.queue.onAck(sent.sequence);
            assertEquals(response, await(result));
        }
    }

    @Test public void lateAckAcrossSequenceWrapCannotCompleteTheNextSend() throws Exception {
        try (Harness h = new Harness(255)) {
            var first = h.queue.send(command(3, 1));
            Sent previous = h.next();
            assertEquals(255, previous.sequence);
            h.queue.onAck(previous.sequence);
            await(first);

            var second = h.queue.send(command(3, 2));
            Sent current = h.next();
            assertEquals(0, current.sequence);
            h.queue.onAck(previous.sequence);
            h.barrier();
            assertFalse(second.toCompletableFuture().isDone());
            h.queue.onAck(current.sequence);
            await(second);
        }
    }

    @Test public void ackRegisteredBeforeWriteCanArriveInsideSender() throws Exception {
        AtomicReference<LiveCommandQueue> owner = new AtomicReference<>();
        try (var queue = new LiveCommandQueue(0, SESSION_TIMEOUT_MS, (sequence, outgoing) -> {
            owner.get().onCommand(outgoing);
            owner.get().onAck(sequence);
        })) {
            owner.set(queue);
            var request = command(2, 1);
            assertEquals(request, await(queue.request(request, 2, 1)));
        }
    }

    @Test public void disconnectFailsAckWaitResponseWaitAndUnsentWork() throws Exception {
        try (Harness h = new Harness()) {
            var responseWait = h.queue.request(command(2, 1), 2, 1);
            h.queue.onAck(h.next().sequence);
            var ackWait = h.queue.send(command(3, 1));
            h.next();
            Gate gate = h.pauseNextWrite();
            var writing = h.queue.send(command(3, 2));
            h.next();
            var unsent = h.queue.send(command(3, 3));
            IOException disconnect = new IOException("test disconnect");
            try {
                h.queue.close(disconnect);
            } finally {
                gate.release.countDown();
            }
            assertFailed(responseWait);
            assertFailed(ackWait);
            assertFailed(writing);
            assertFailed(unsent);
            assertFailed(h.queue.send(command(3, 4)));
        }
    }

    @Test public void readerCanAckFileConfirmationWhileResponseConsumerWaitsForIt() throws Exception {
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (Harness h = new Harness()) {
            var file = h.queue.request(command(8, 4), 8, 4);
            Sent request = h.next();
            var savedAndConfirmed = file.thenApply(response -> {
                try {
                    await(h.queue.send(command(8, 5)));
                    return response;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            var response = command(8, 4);
            reader.submit(() -> {
                h.queue.onAck(request.sequence);
                h.queue.onCommand(response);
            }).get(WAIT_SECONDS, TimeUnit.SECONDS);
            Sent confirmation = h.next();
            assertEquals(command(8, 5), confirmation.command);
            reader.submit(() -> h.queue.onAck(confirmation.sequence))
                    .get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(response, await(savedAndConfirmed));
        } finally {
            reader.shutdownNow();
            assertTrue(reader.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test public void reservedFileSlotsAreReleasedSoNinthAndLaterFilesAreAccepted() throws Exception {
        try (Harness h = new Harness()) {
            List<CompletionStage<Void>> confirmations = new ArrayList<>();
            List<Sent> sent = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                confirmations.add(h.queue.send(command(8, 5)));
                sent.add(h.next());
            }
            for (int i = 0; i < 8; i++) {
                h.queue.onAck(sent.get(i).sequence);
                await(confirmations.get(i));
                var nextFile = h.queue.send(command(8, 5));
                Sent next = h.next();
                h.queue.onAck(next.sequence);
                await(nextFile);
            }
        }
    }

    @Test public void congestionDropsOnlyOldestUnsentNotificationAndPrioritizesLatestCallClear()
            throws Exception {
        try (Harness h = new Harness()) {
            Gate gate = h.pauseNextWrite();
            List<CompletionStage<Void>> notifications = new ArrayList<>();
            notifications.add(h.queue.send(notification(1)));
            Sent first = h.next();
            try {
                for (int id = 2; id <= 64; id++) {
                    notifications.add(h.queue.send(notification(id)));
                }
                var ringing = h.queue.send(BandNotificationCommand.incomingCall("Test call",
                        Instant.EPOCH, ZoneId.of("UTC")));
                var newest = h.queue.send(notification(65));
                var clear = h.queue.send(BandNotificationCommand.endCall());
                gate.release.countDown();
                assertFailure(notifications.get(1), Throwable.class);
                assertFailure(ringing, Throwable.class);
                assertFalse(notifications.get(0).toCompletableFuture().isDone());
                Sent next = h.next();
                assertEquals(BandNotificationCommand.endCall(), next.command);
                h.queue.onAck(next.sequence);
                await(clear);
                h.queue.onAck(first.sequence);
                await(notifications.get(0));
                for (int id = 3; id <= 65; id++) {
                    Sent remaining = h.next();
                    assertEquals(notification(id), remaining.command);
                    h.queue.onAck(remaining.sequence);
                }
                for (int i = 2; i < notifications.size(); i++) await(notifications.get(i));
                await(newest);
            } finally {
                gate.release.countDown();
            }
        }
    }

    @Test public void fullQueueOfNonDisposableWorkRejectsNotificationWithoutLosingPendingCommands()
            throws Exception {
        try (Harness h = new Harness()) {
            Gate gate = h.pauseNextWrite();
            List<CompletionStage<Void>> pending = new ArrayList<>();
            pending.add(h.queue.send(command(3, 0)));
            Sent first = h.next();
            try {
                for (int i = 1; i < 64; i++) pending.add(h.queue.send(command(3, i)));
                var rejected = h.queue.send(notification(1));
                gate.release.countDown();
                assertFailure(rejected, Throwable.class);
                h.queue.onAck(first.sequence);
                for (int i = 1; i < 64; i++) {
                    Sent sent = h.next();
                    assertEquals(command(3, i), sent.command);
                    h.queue.onAck(sent.sequence);
                }
                for (var future : pending) await(future);
            } finally {
                gate.release.countDown();
            }
        }
    }

    @Test public void semanticRejectionFailsOnlyMatchingRequestAndConnectionRemainsUsable()
            throws Exception {
        try (Harness h = new Harness()) {
            var rejected = h.queue.request(command(10, 5), 10, 5);
            Sent weather = h.next();
            var battery = h.queue.request(command(2, 1), 2, 1);
            Sent batterySent = h.next();
            h.queue.onAck(weather.sequence);
            h.queue.onCommand(command(10, 5).toBuilder().setStatus(7).build());
            assertFailure(rejected, Throwable.class);
            assertFalse(battery.toCompletableFuture().isDone());
            h.queue.onCommand(command(2, 1));
            h.queue.onAck(batterySent.sequence);
            assertEquals(command(2, 1), await(battery));
            h.barrier();
        }
    }

    @Test public void duplicateHealthWeatherAndBatteryRequestsShareOneOutstandingExchange()
            throws Exception {
        for (var request : new XiaomiProto.Command[]{command(8, 1), command(10, 5), command(2, 1)}) {
            try (Harness h = new Harness()) {
                var first = h.queue.request(request, request.getType(), request.getSubtype());
                Sent sent = h.next();
                var duplicate = h.queue.request(request, request.getType(), request.getSubtype());
                h.barrier();
                assertFalse(first.toCompletableFuture().isDone());
                assertFalse(duplicate.toCompletableFuture().isDone());
                h.queue.onCommand(request);
                h.queue.onAck(sent.sequence);
                assertEquals(request, await(first));
                assertEquals(request, await(duplicate));
                h.barrier();
            }
        }
    }

    @Test public void missingAckTimesOutObservably() throws Exception {
        BlockingQueue<Integer> writes = new LinkedBlockingQueue<>();
        try (var queue = new LiveCommandQueue(0, 200, (sequence, command) -> writes.add(sequence))) {
            var result = queue.send(command(3, 1));
            assertNotNull(writes.poll(WAIT_SECONDS, TimeUnit.SECONDS));
            assertFailure(result, TimeoutException.class);
        }
    }

    private static XiaomiProto.Command command(int type, int subtype) {
        return XiaomiProto.Command.newBuilder().setType(type).setSubtype(subtype).build();
    }

    private static XiaomiProto.Command notification(int id) {
        return BandNotificationCommand.post("com.example.test", "Test", "key-" + id, id,
                "Test notification", "Test body", Instant.EPOCH, ZoneId.of("UTC"));
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static void assertFailed(CompletionStage<?> stage) {
        Throwable failure = assertThrows(Exception.class, () -> await(stage));
        assertFalse("The test wait elapsed rather than the queue reporting failure",
                failure instanceof TimeoutException);
    }

    private static void assertFailure(CompletionStage<?> stage, Class<? extends Throwable> cause) {
        // Cancellation may be direct; all other queue failures are wrapped by Future.get().
        Throwable failure = assertThrows(Exception.class, () -> await(stage));
        assertFalse("The test wait elapsed rather than the queue reporting failure",
                failure instanceof TimeoutException);
        if (failure instanceof ExecutionException) failure = failure.getCause();
        assertTrue("Unexpected queue failure: " + failure, cause.isInstance(failure));
    }

    private static final class Sent {
        final int sequence;
        final XiaomiProto.Command command;

        Sent(int sequence, XiaomiProto.Command command) {
            this.sequence = sequence;
            this.command = command;
        }
    }

    private static final class Gate {
        final CountDownLatch release = new CountDownLatch(1);
    }

    private static final class Harness implements AutoCloseable {
        final BlockingQueue<Sent> writes = new LinkedBlockingQueue<>();
        final AtomicReference<Gate> nextGate = new AtomicReference<>();
        final LiveCommandQueue queue;
        private Gate activeGate;

        Harness() {
            this(0);
        }

        Harness(int initialSequence) {
            queue = new LiveCommandQueue(initialSequence, SESSION_TIMEOUT_MS, (sequence, command) -> {
                Gate gate = nextGate.getAndSet(null);
                writes.add(new Sent(sequence, command));
                if (gate != null && !gate.release.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IOException("Test sender gate was not released");
                }
            });
        }

        Sent next() throws InterruptedException {
            Sent sent = writes.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("Expected a command to reach the transport", sent);
            return sent;
        }

        Gate pauseNextWrite() {
            activeGate = new Gate();
            nextGate.set(activeGate);
            return activeGate;
        }

        void barrier() throws Exception {
            var marker = queue.send(command(99, 1));
            Sent sent = next();
            assertEquals(command(99, 1), sent.command);
            queue.onAck(sent.sequence);
            await(marker);
        }

        @Override public void close() {
            if (activeGate != null) activeGate.release.countDown();
            queue.close();
        }
    }
}
