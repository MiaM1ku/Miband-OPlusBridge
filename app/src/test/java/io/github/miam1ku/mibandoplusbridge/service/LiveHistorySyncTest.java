// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import com.google.protobuf.ByteString;
import io.github.miam1ku.mibandoplusbridge.protocol.LiveCommandQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LiveHistorySyncTest {
    @Test public void acknowledgedButMissingFileTimesOutAndNextSyncRecovers() throws Exception {
        exerciseRecovery(false);
    }

    @Test public void rejectedDownloadDoesNotStrandFutureSync() throws Exception {
        exerciseRecovery(true);
    }

    private void exerciseRecovery(boolean rejection) throws Exception {
        var worker = Executors.newSingleThreadScheduledExecutor();
        var queueRef = new AtomicReference<LiveCommandQueue>();
        var historyRef = new AtomicReference<LiveHistorySync>();
        var requests = new AtomicInteger();
        BlockingQueue<String> outcomes = new LinkedBlockingQueue<>();
        try (var queue = new LiveCommandQueue(0, rejection ? 10_000 : 300, (sequence, command) -> {
            var current = queueRef.get();
            if (command.getSubtype() == 1) {
                byte[] ids = requests.getAndIncrement() == 0 ? new byte[]{1, 0, 0, 0, 0, 4, 0} : new byte[0];
                current.onCommand(XiaomiProto.Command.newBuilder().setType(8).setSubtype(1)
                        .setHealth(XiaomiProto.Health.newBuilder().setActivityRequestFileIds(ByteString.copyFrom(ids))).build());
            } else if (rejection && command.getSubtype() == 3) {
                worker.execute(() -> historyRef.get().onCommand(
                        XiaomiProto.Command.newBuilder().setType(8).setSubtype(3).setStatus(1).build()));
            }
            current.onAck(sequence);
        })) {
            queueRef.set(queue);
            var history = new LiveHistorySync(queue, worker, () -> outcomes.add("complete"), outcomes::add, () -> true);
            historyRef.set(history);
            worker.execute(() -> history.request(false));
            String failure = outcomes.poll(5, TimeUnit.SECONDS);
            assertNotNull("A missing or rejected file must release the sync round", failure);
            assertNotEquals("complete", failure);
            worker.execute(() -> history.request(false));
            assertEquals("complete", outcomes.poll(5, TimeUnit.SECONDS));
            assertEquals(2, requests.get());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test public void anAlreadyStoredFileIsNotDownloadedAgain() throws Exception {
        var worker = Executors.newSingleThreadScheduledExecutor();
        var queueRef = new AtomicReference<LiveCommandQueue>();
        var historyRef = new AtomicReference<LiveHistorySync>();
        var downloads = new AtomicInteger();
        byte[] id = new byte[] {1, 0, 0, 0, 0, 4, 0};
        BlockingQueue<String> outcomes = new LinkedBlockingQueue<>();
        try (var queue = new LiveCommandQueue(0, 5_000, (sequence, command) -> {
            var current = queueRef.get();
            if (command.getSubtype() == 1) {
                current.onCommand(XiaomiProto.Command.newBuilder().setType(8).setSubtype(1)
                        .setHealth(XiaomiProto.Health.newBuilder()
                                .setActivityRequestFileIds(ByteString.copyFrom(id))).build());
            } else if (command.getSubtype() == 3) {
                downloads.incrementAndGet();
                worker.execute(() -> historyRef.get().saved(id));
            }
            current.onAck(sequence);
        })) {
            queueRef.set(queue);
            var history = new LiveHistorySync(queue, worker, () -> outcomes.add("complete"), outcomes::add, () -> true);
            historyRef.set(history);
            worker.execute(() -> history.request(false));
            assertEquals("complete", outcomes.poll(5, TimeUnit.SECONDS));
            worker.execute(() -> history.request(false));
            assertEquals("complete", outcomes.poll(5, TimeUnit.SECONDS));
            assertEquals(1, downloads.get());
        } finally {
            worker.shutdownNow();
        }
    }
    @Test public void storageFailureReleasesTheRound() throws Exception {
        var worker = Executors.newSingleThreadScheduledExecutor();
        var queueRef = new AtomicReference<LiveCommandQueue>();
        var listed = new AtomicInteger();
        var opens = new AtomicInteger();
        BlockingQueue<String> outcomes = new LinkedBlockingQueue<>();
        try (var queue = new LiveCommandQueue(0, 5_000, (sequence, command) -> {
            var current = queueRef.get();
            if (command.getSubtype() == 1) {
                byte[] ids = listed.getAndIncrement() == 0 ? new byte[] {1, 0, 0, 0, 0, 4, 0} : new byte[0];
                current.onCommand(XiaomiProto.Command.newBuilder().setType(8).setSubtype(1)
                        .setHealth(XiaomiProto.Health.newBuilder()
                                .setActivityRequestFileIds(ByteString.copyFrom(ids))).build());
            }
            current.onAck(sequence);
        })) {
            queueRef.set(queue);
            var history = new LiveHistorySync(queue, worker, () -> outcomes.add("complete"), outcomes::add, () -> {
                if (opens.getAndIncrement() == 0) throw new IllegalStateException("HEALTH_STORAGE_UNAVAILABLE");
                return true;
            });
            worker.execute(() -> history.request(false));
            assertEquals("HEALTH_STORAGE_UNAVAILABLE", outcomes.poll(5, TimeUnit.SECONDS));
            worker.execute(() -> history.request(false));
            assertEquals("complete", outcomes.poll(5, TimeUnit.SECONDS));
        } finally {
            worker.shutdownNow();
        }
    }

}
