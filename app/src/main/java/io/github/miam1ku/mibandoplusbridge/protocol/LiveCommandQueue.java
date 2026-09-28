// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/**
 * The live session's bounded command owner. No socket reads belong here.
 *
 * <p>Capacity includes queued and in-flight work: 64 general operations, eight independent
 * file confirmations, and two in-flight calls plus one replaceable latest call state. Call
 * state has priority over file confirmations, then general work. Only unsent ordinary posts
 * may be evicted; full non-disposable queues fail admission. Deadlines start at admission.
 * Exact duplicate health/weather/battery operations share their original deadline/result;
 * payload equality keeps different file IDs and weather snapshots separate. Nothing is replayed.
 *
 * <p>The initial sequence is the NEXT sequence to send after the handshake. A sequence stays
 * reserved until the entire operation finishes, then is quarantined for at least ten seconds
 * (or the configured timeout, if longer). The eight-bit wire ACK has no generation: an ACK
 * delayed beyond that quarantine cannot be distinguished after reuse. Likewise, semantic
 * responses have no transaction ID; requests for the same response type/subtype are serialized.
 * Unsolicited or arbitrarily late responses with that same pair are not distinguishable.
 *
 * <p>Only the owned worker calls Sender. ACK/command ingress never invokes consumer callbacks:
 * completions use the common pool, so a callback may submit and await a file confirmation
 * without blocking the socket reader or writer. A separate deadline worker keeps expiration
 * observable even during a blocked write. Closing the transport itself remains the caller's job;
 * interrupting the writer cannot guarantee that an underlying socket write returns.
 */
public final class LiveCommandQueue implements AutoCloseable {
    private static final int CAPACITY = 64;
    private static final int FILE_CAPACITY = 8;
    private static final int CALL_IN_FLIGHT_CAPACITY = 2;
    private static final long DEFAULT_TIMEOUT_MILLIS = 10_000;

    @FunctionalInterface
    public interface Sender {
        void write(int sequence, XiaomiProto.Command command) throws Exception;
    }

    private enum Kind { GENERAL, NOTIFICATION, DISMISS, FILE_CONFIRM, CALL }

    private static final class Work {
        final XiaomiProto.Command command;
        final int responseType;
        final int responseSubtype;
        final Kind kind;
        final CompletableFuture<XiaomiProto.Command> result = new CompletableFuture<>();
        int sequence = -1;
        boolean acknowledged;
        XiaomiProto.Command response;
        ScheduledFuture<?> timeout;

        Work(XiaomiProto.Command command, int type, int subtype, Kind kind) {
            this.command = command;
            this.responseType = type;
            this.responseSubtype = subtype;
            this.kind = kind;
        }

        boolean wantsResponse() { return responseType >= 0; }
        boolean ordinary() { return kind != Kind.FILE_CONFIRM && kind != Kind.CALL; }
    }

    private final Object lock = new Object();
    private final Sender sender;
    private final long timeoutMillis;
    private final long quarantineNanos;
    private final ScheduledThreadPoolExecutor worker;
    private final ScheduledThreadPoolExecutor deadlines;
    // Admission order is also the eviction order. Includes queued AND in-flight work.
    private final ArrayList<Work> work = new ArrayList<>(CAPACITY + FILE_CAPACITY + 3);
    private final Work[] pending = new Work[256];
    private final long[] retiredUntil = new long[256];
    private int nextSequence;
    private boolean drainScheduled;
    private ScheduledFuture<?> sequenceWake;
    private boolean closed;

    public LiveCommandQueue(int initialSequence, long timeoutMillis, Sender sender) {
        if (initialSequence < 0 || initialSequence > 255) {
            throw new IllegalArgumentException("LIVE_SEQUENCE_INVALID");
        }
        this.sender = Objects.requireNonNull(sender, "LIVE_SENDER_REQUIRED");
        this.nextSequence = initialSequence;
        this.timeoutMillis = timeoutMillis <= 0
                || timeoutMillis > TimeUnit.NANOSECONDS.toMillis(Long.MAX_VALUE / 4)
                ? DEFAULT_TIMEOUT_MILLIS : timeoutMillis;
        this.quarantineNanos = TimeUnit.MILLISECONDS.toNanos(
                Math.max(DEFAULT_TIMEOUT_MILLIS, this.timeoutMillis));
        worker = scheduler("band-live-command");
        deadlines = scheduler("band-live-deadline");
    }

    private static ScheduledThreadPoolExecutor scheduler(String name) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    public long timeoutMillis() { return timeoutMillis; }

    /** Completes after the matching transport ACK; this does not prove device-side rendering. */
    public CompletionStage<Void> send(XiaomiProto.Command command) {
        return submit(command, -1, -1).thenApply(ignored -> null);
    }

    /** Completes only after both the matching transport ACK and a successful semantic response. */
    public CompletionStage<XiaomiProto.Command> request(
            XiaomiProto.Command command, int type, int subtype) {
        if (type < 0 || subtype < 0) return failed("LIVE_RESPONSE_KEY_INVALID");
        return submit(command, type, subtype);
    }

    private CompletableFuture<XiaomiProto.Command> submit(
            XiaomiProto.Command command, int type, int subtype) {
        if (command == null || !command.isInitialized()) return failed("LIVE_COMMAND_INVALID");
        synchronized (lock) {
            if (closed) return failed("LIVE_QUEUE_CLOSED");
            if (coalescible(command)) {
                for (Work item : work) {
                    if (item.responseType == type && item.responseSubtype == subtype
                            && item.command.equals(command)) return item.result;
                }
            }
            Kind kind = classify(command);
            if (kind == Kind.CALL) {
                // A single latest-state slot; never cancel a call packet already handed to Sender.
                for (int index = work.size() - 1; index >= 0; index--) {
                    Work item = work.get(index);
                    if (item.kind == Kind.CALL && item.sequence < 0) {
                        finishLocked(item, null, failure("LIVE_CALL_SUPERSEDED"));
                    }
                }
            } else if (kind == Kind.FILE_CONFIRM) {
                if (countLocked(Kind.FILE_CONFIRM) >= FILE_CAPACITY) {
                    return failed("LIVE_FILE_CONFIRM_QUEUE_FULL");
                }
            } else {
                int ordinary = 0;
                Work disposable = null;
                for (Work item : work) {
                    if (item.ordinary()) ordinary++;
                    if (disposable == null && item.kind == Kind.NOTIFICATION && item.sequence < 0) {
                        disposable = item;
                    }
                }
                if (ordinary >= CAPACITY) {
                    if (disposable == null) return failed("LIVE_QUEUE_FULL");
                    finishLocked(disposable, null, failure("LIVE_NOTIFICATION_DROPPED"));
                }
            }
            Work item = new Work(command, type, subtype, kind);
            work.add(item);
            item.timeout = deadlines.schedule(() -> expire(item), timeoutMillis, TimeUnit.MILLISECONDS);
            scheduleDrainLocked();
            return item.result;
        }
    }

    public void onAck(int sequence) {
        if (sequence < 0 || sequence > 255) return;
        synchronized (lock) {
            Work item = pending[sequence];
            if (item == null) return;
            item.acknowledged = true;
            if (!item.wantsResponse() || item.response != null) {
                finishLocked(item, item.response, null);
                scheduleDrainLocked();
            }
        }
    }

    public void onCommand(XiaomiProto.Command command) {
        if (command == null) return;
        synchronized (lock) {
            for (Work item : work) {
                if (item.sequence >= 0 && item.wantsResponse()
                        && item.responseType == command.getType()
                        && item.responseSubtype == command.getSubtype()) {
                    if (command.hasStatus() && command.getStatus() != 0) {
                        finishLocked(item, null, failure("LIVE_COMMAND_REJECTED"));
                    } else {
                        if (item.response == null) item.response = command;
                        if (item.acknowledged) finishLocked(item, item.response, null);
                    }
                    scheduleDrainLocked();
                    return;
                }
            }
        }
    }

    /** Cancels only unsent ordinary posts/dismissals, for access revocation or relay shutdown. */
    public void cancelNotifications(Throwable cause) {
        cancelUnsent(false);
    }

    /** Cancels only unsent phone state, for phone permission/feature revocation. */
    public void cancelCall(Throwable cause) {
        cancelUnsent(true);
    }

    private void cancelUnsent(boolean calls) {
        synchronized (lock) {
            for (int index = work.size() - 1; index >= 0; index--) {
                Work item = work.get(index);
                if (item.sequence < 0 && (calls ? item.kind == Kind.CALL
                        : item.kind == Kind.NOTIFICATION || item.kind == Kind.DISMISS)) {
                    finishLocked(item, null, failure(calls
                            ? "LIVE_CALL_CANCELLED" : "LIVE_NOTIFICATION_CANCELLED"));
                }
            }
            scheduleDrainLocked();
        }
    }

    @Override public void close() { close(null); }

    /** Caller/transport exception details are deliberately not propagated into public errors. */
    public void close(Throwable cause) {
        closeWithCode("LIVE_QUEUE_CLOSED");
    }

    private void closeWithCode(String code) {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            if (sequenceWake != null) sequenceWake.cancel(false);
            while (!work.isEmpty()) finishLocked(work.get(work.size() - 1), null, failure(code));
            worker.shutdownNow();
            deadlines.shutdownNow();
        }
    }

    private void scheduleDrainLocked() {
        if (closed || drainScheduled || work.isEmpty()) return;
        drainScheduled = true;
        worker.execute(this::drain);
    }

    private void drain() {
        Work selected;
        synchronized (lock) {
            drainScheduled = false;
            if (closed) return;
            selected = nextWorkLocked();
            if (selected == null) return;
            int sequence = allocateSequenceLocked();
            if (sequence < 0) return;
            // Install BOTH correlation records before Sender may synchronously provoke ingress.
            selected.sequence = sequence;
            pending[sequence] = selected;
        }
        try {
            sender.write(selected.sequence, selected.command);
        } catch (Exception failure) {
            closeWithCode("LIVE_TRANSPORT_WRITE_FAILED");
            return;
        }
        synchronized (lock) {
            scheduleDrainLocked();
        }
    }

    private Work nextWorkLocked() {
        Work selected = null;
        int callsInFlight = 0;
        for (Work item : work) {
            if (item.kind == Kind.CALL && item.sequence >= 0) callsInFlight++;
        }
        for (Work item : work) {
            if (item.sequence >= 0 || (item.kind == Kind.CALL
                    && callsInFlight >= CALL_IN_FLIGHT_CAPACITY)) continue;
            if (item.wantsResponse() && responseBusyLocked(item)) continue;
            if (selected == null || priority(item.kind) < priority(selected.kind)) selected = item;
        }
        return selected;
    }

    private boolean responseBusyLocked(Work candidate) {
        for (Work item : work) {
            if (item.sequence >= 0 && item.wantsResponse()
                    && item.responseType == candidate.responseType
                    && item.responseSubtype == candidate.responseSubtype) return true;
        }
        return false;
    }

    private int allocateSequenceLocked() {
        int sequence = nextSequence;
        if (pending[sequence] != null) return -1;
        long remaining = retiredUntil[sequence] == 0 ? 0 : retiredUntil[sequence] - System.nanoTime();
        if (remaining <= 0) {
            nextSequence = (sequence + 1) & 255;
            return sequence;
        }
        // The peer expects contiguous eight-bit sequence numbers; never skip a quarantined ID.
        if (sequenceWake == null) {
            sequenceWake = worker.schedule(() -> {
                synchronized (lock) {
                    sequenceWake = null;
                    scheduleDrainLocked();
                }
            }, remaining, TimeUnit.NANOSECONDS);
        }
        return -1;
    }

    private void expire(Work item) {
        synchronized (lock) {
            if (!work.contains(item)) return;
            finishLocked(item, null, new TimeoutException("LIVE_COMMAND_TIMEOUT"));
            scheduleDrainLocked();
        }
    }

    private void finishLocked(Work item, XiaomiProto.Command response, Throwable error) {
        work.remove(item);
        if (item.timeout != null) item.timeout.cancel(false);
        if (item.sequence >= 0) {
            pending[item.sequence] = null;
            retiredUntil[item.sequence] = System.nanoTime() + quarantineNanos;
        }
        // Never execute user continuation code under lock, on the reader, or on the writer.
        ForkJoinPool.commonPool().execute(() -> {
            if (error == null) item.result.complete(response);
            else item.result.completeExceptionally(error);
        });
    }

    private int countLocked(Kind kind) {
        int count = 0;
        for (Work item : work) if (item.kind == kind) count++;
        return count;
    }

    private static int priority(Kind kind) {
        return kind == Kind.CALL ? 0 : kind == Kind.FILE_CONFIRM ? 1 : 2;
    }

    private static boolean coalescible(XiaomiProto.Command command) {
        return (command.getType() == 8 && command.getSubtype() != 5)
                || command.getType() == 10
                || (command.getType() == 2 && command.getSubtype() == 1);
    }

    private static Kind classify(XiaomiProto.Command command) {
        if (command.getType() == 8 && command.getSubtype() == 5) return Kind.FILE_CONFIRM;
        if (command.getType() != 7 || !command.hasNotification()) return Kind.GENERAL;
        XiaomiProto.Notification notification = command.getNotification();
        if (command.getSubtype() == 0 && notification.hasNotification2()
                && notification.getNotification2().hasNotification3()) {
            XiaomiProto.Notification3 post = notification.getNotification2().getNotification3();
            if (BandNotificationCommand.isCall(post)) {
                return "phone".equals(post.getPackage()) && post.getId() == 0
                        ? Kind.CALL : Kind.GENERAL;
            }
            return Kind.NOTIFICATION;
        }
        if (command.getSubtype() == 1 && notification.hasNotificationDismiss()) {
            XiaomiProto.NotificationDismiss dismiss = notification.getNotificationDismiss();
            if (dismiss.getNotificationIdCount() == 1) {
                XiaomiProto.NotificationId id = dismiss.getNotificationId(0);
                if ("phone".equals(id.getPackage()) && id.getId() == 0) return Kind.CALL;
            }
            return Kind.DISMISS;
        }
        return Kind.GENERAL;
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    private static CompletableFuture<XiaomiProto.Command> failed(String code) {
        CompletableFuture<XiaomiProto.Command> future = new CompletableFuture<>();
        future.completeExceptionally(failure(code));
        return future;
    }
}
