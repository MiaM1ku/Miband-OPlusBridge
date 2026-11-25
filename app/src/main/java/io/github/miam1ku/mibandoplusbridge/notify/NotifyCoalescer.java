// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/**
 * Coalesces rapid per-key notification bursts (e.g. IM cancel-and-repost sync storms)
 * and spaces consecutive ordinary posts so Band 11's vibrator does not hit cancel/start races.
 * Calls and alarms never pass through this queue.
 */
public final class NotifyCoalescer {
    public static final long COALESCE_MS = 350L;
    public static final long MAX_HOLD_MS = 1_000L;
    public static final long POST_SPACING_MS = 350L;
    private static final int DELIVERED_LIMIT = 128;

    public static final class Entry {
        public final XiaomiProto.Command command;
        public final boolean post;
        private final List<CompletableFuture<Void>> waiters;

        Entry(XiaomiProto.Command command, boolean post, List<CompletableFuture<Void>> waiters) {
            this.command = command;
            this.post = post;
            this.waiters = waiters;
        }

        public void succeed() {
            for (CompletableFuture<Void> waiter : waiters) waiter.complete(null);
        }

        public void fail(Throwable error) {
            for (CompletableFuture<Void> waiter : waiters) waiter.completeExceptionally(error);
        }
    }

    private static final class Pending {
        XiaomiProto.Command command;
        boolean post;
        final long firstAtMs;
        long dueAtMs;
        final List<CompletableFuture<Void>> waiters = new ArrayList<>();

        Pending(XiaomiProto.Command command, boolean post, long firstAtMs, long dueAtMs,
                CompletableFuture<Void> waiter) {
            this.command = command;
            this.post = post;
            this.firstAtMs = firstAtMs;
            this.dueAtMs = dueAtMs;
            this.waiters.add(waiter);
        }
    }

    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, Boolean> delivered = new LinkedHashMap<>();
    private long lastPostSentAtMs = Long.MIN_VALUE;

    /** Returns the per-notification slot key for ordinary posts/dismisses, or empty for calls/other commands. */
    public static String slotKey(XiaomiProto.Command command) {
        if (command == null || command.getType() != 7 || !command.hasNotification()) return "";
        var notification = command.getNotification();
        if (command.getSubtype() == 0 && notification.hasNotification2()
                && notification.getNotification2().hasNotification3()) {
            var item = notification.getNotification2().getNotification3();
            if (BandNotificationCommand.isCall(item)) return "";
            if (item.getPackage().isEmpty() || item.getKey().isEmpty()) return "";
            return item.getPackage() + "\n" + item.getKey() + "\n" + item.getId();
        }
        if (command.getSubtype() == 1 && notification.hasNotificationDismiss()
                && notification.getNotificationDismiss().getNotificationIdCount() == 1) {
            var id = notification.getNotificationDismiss().getNotificationId(0);
            if ("phone".equals(id.getPackage()) && id.getId() == 0) return "";
            if (id.getPackage().isEmpty() || id.getKey().isEmpty()) return "";
            return id.getPackage() + "\n" + id.getKey() + "\n" + id.getId();
        }
        return "";
    }

    public synchronized CompletableFuture<Void> offer(XiaomiProto.Command command, long nowMs) {
        String slot = slotKey(command);
        if (slot.isEmpty()) throw new IllegalArgumentException("COALESCE_ORDINARY_ONLY");
        boolean isPost = command.getSubtype() == 0;
        CompletableFuture<Void> waiter = new CompletableFuture<>();
        Pending existing = pending.get(slot);
        if (!isPost) {
            if (existing != null && existing.post) {
                // Supersede an unsent post on this key.
                for (CompletableFuture<Void> w : existing.waiters) w.complete(null);
                existing.waiters.clear();
                if (!delivered.containsKey(slot)) {
                    // Never transmitted to the band in this session: drop the transient pair.
                    pending.remove(slot);
                    waiter.complete(null);
                    return waiter;
                }
                existing.command = command;
                existing.post = false;
                existing.dueAtMs = Math.min(nowMs + COALESCE_MS, existing.firstAtMs + MAX_HOLD_MS);
                existing.waiters.add(waiter);
                return waiter;
            }
            if (existing != null) {
                existing.command = command;
                existing.waiters.add(waiter);
                return waiter;
            }
            pending.put(slot, new Pending(command, false, nowMs, nowMs + COALESCE_MS, waiter));
            return waiter;
        }
        if (existing != null) {
            // Newer post supersedes an earlier unsent post or an in-flight cancel-and-repost dismiss.
            for (CompletableFuture<Void> w : existing.waiters) w.complete(null);
            existing.waiters.clear();
            existing.command = command;
            existing.post = true;
            existing.dueAtMs = Math.min(nowMs + COALESCE_MS, existing.firstAtMs + MAX_HOLD_MS);
            existing.waiters.add(waiter);
            return waiter;
        }
        pending.put(slot, new Pending(command, true, nowMs, nowMs + COALESCE_MS, waiter));
        return waiter;
    }

    /** Polls the earliest entry eligible for transmission at {@code nowMs}, enforcing post spacing. */
    public synchronized Entry pollNext(long nowMs) {
        String chosenKey = null;
        Pending chosen = null;
        long postGate = lastPostSentAtMs == Long.MIN_VALUE ? Long.MIN_VALUE : lastPostSentAtMs + POST_SPACING_MS;
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            Pending candidate = entry.getValue();
            if (candidate.dueAtMs > nowMs) continue;
            if (candidate.post && nowMs < postGate) continue;
            if (chosen == null || candidate.dueAtMs < chosen.dueAtMs) {
                chosenKey = entry.getKey();
                chosen = candidate;
            }
        }
        if (chosen == null) return null;
        pending.remove(chosenKey);
        if (chosen.post) {
            lastPostSentAtMs = nowMs;
            rememberDelivered(chosenKey);
        } else {
            delivered.remove(chosenKey);
        }
        return new Entry(chosen.command, chosen.post, new ArrayList<>(chosen.waiters));
    }

    /** Earliest elapsed-realtime timestamp when {@link #pollNext(long)} can emit an entry, or {@link Long#MAX_VALUE}. */
    public synchronized long nextDueAtMs() {
        long earliest = Long.MAX_VALUE;
        long postGate = lastPostSentAtMs == Long.MIN_VALUE ? Long.MIN_VALUE : lastPostSentAtMs + POST_SPACING_MS;
        for (Pending item : pending.values()) {
            long effective = item.post && postGate > item.dueAtMs ? postGate : item.dueAtMs;
            if (effective < earliest) earliest = effective;
        }
        return earliest;
    }

    public synchronized List<Entry> drainAll() {
        List<Entry> result = new ArrayList<>(pending.size());
        for (Pending item : pending.values()) {
            result.add(new Entry(item.command, item.post, new ArrayList<>(item.waiters)));
        }
        pending.clear();
        return result;
    }

    public synchronized int failAll(Throwable error) {
        int count = pending.size();
        for (Pending item : pending.values()) {
            for (CompletableFuture<Void> waiter : item.waiters) waiter.completeExceptionally(error);
        }
        pending.clear();
        return count;
    }

    public synchronized void resetSession() {
        delivered.clear();
        lastPostSentAtMs = Long.MIN_VALUE;
    }

    public synchronized int size() { return pending.size(); }

    private void rememberDelivered(String slot) {
        delivered.remove(slot);
        delivered.put(slot, Boolean.TRUE);
        Iterator<String> keys = delivered.keySet().iterator();
        while (delivered.size() > DELIVERED_LIMIT && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }
}
