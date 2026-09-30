// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Notifications that arrived before the band session could send. Oldest past the deadline are dropped. */
public final class NotifyReplay {
    public static final int LIMIT = 16;
    public static final long NOTIFICATION_MS = 60_000;
    public static final long CALL_MS = 15_000;
    private final List<Held> pending = new ArrayList<>();

    public static final class Held {
        public final XiaomiProto.Command command;
        private final List<CompletableFuture<Void>> waiters = new ArrayList<>();
        long atMs;
        final boolean call;

        Held(XiaomiProto.Command command, CompletableFuture<Void> waiter, long atMs, boolean call) {
            this.command = command;
            this.atMs = atMs;
            this.call = call;
            waiters.add(waiter);
        }

        public void succeed() {
            for (CompletableFuture<Void> waiter : waiters) waiter.complete(null);
        }

        public void fail(Throwable error) {
            for (CompletableFuture<Void> waiter : waiters) waiter.completeExceptionally(error);
        }
    }

    public synchronized CompletableFuture<Void> add(XiaomiProto.Command command, long nowMs) {
        expire(nowMs);
        CompletableFuture<Void> waiter = new CompletableFuture<>();
        if (dismiss(command)) {
            String target = packageKey(command);
            boolean cleared = false;
            Iterator<Held> items = pending.iterator();
            while (items.hasNext()) {
                Held held = items.next();
                if (target.equals(packageKey(held.command)) && !dismiss(held.command)) {
                    items.remove();
                    held.succeed();
                    cleared = true;
                }
            }
            if (cleared) {
                waiter.complete(null);
                return waiter;
            }
        }
        String identity = NotifyDedupe.identity(command);
        if (!identity.isEmpty()) {
            for (Held held : pending) {
                if (identity.equals(NotifyDedupe.identity(held.command))) {
                    held.waiters.add(waiter);
                    held.atMs = nowMs;
                    return waiter;
                }
            }
        }
        while (pending.size() >= LIMIT) pending.remove(0).fail(new IllegalStateException("NOTIFY_HOLD_DROPPED"));
        pending.add(new Held(command, waiter, nowMs, shortLived(command)));
        return waiter;
    }

    public synchronized int expire(long nowMs) {
        int expired = 0;
        Iterator<Held> items = pending.iterator();
        while (items.hasNext()) {
            Held held = items.next();
            long age = held.call ? CALL_MS : NOTIFICATION_MS;
            if (nowMs - held.atMs >= age) {
                items.remove();
                held.fail(new IllegalStateException("NOTIFY_HOLD_EXPIRED"));
                expired++;
            }
        }
        return expired;
    }

    public synchronized List<Held> poll(long nowMs) {
        expire(nowMs);
        List<Held> ready = new ArrayList<>(pending);
        pending.clear();
        return ready;
    }

    public synchronized int failAll(Throwable error) {
        int count = pending.size();
        for (Held held : pending) held.fail(error);
        pending.clear();
        return count;
    }

    public synchronized int size() { return pending.size(); }

    private static boolean dismiss(XiaomiProto.Command command) {
        return command.getSubtype() == 1 && command.hasNotification()
                && command.getNotification().hasNotificationDismiss();
    }

    private static boolean shortLived(XiaomiProto.Command command) {
        if (dismiss(command)) return "phone".equals(packageKey(command).split("\n", 2)[0]);
        return command.hasNotification() && command.getNotification().hasNotification2()
                && BandNotificationCommand.isCall(command.getNotification().getNotification2().getNotification3());
    }

    /** Package and key. A call and its end share phone plus an empty key. */
    private static String packageKey(XiaomiProto.Command command) {
        if (dismiss(command) && command.getNotification().getNotificationDismiss().getNotificationIdCount() > 0) {
            var id = command.getNotification().getNotificationDismiss().getNotificationId(0);
            return id.getPackage() + "\n" + id.getKey();
        }
        if (command.hasNotification() && command.getNotification().hasNotification2()
                && command.getNotification().getNotification2().hasNotification3()) {
            var item = command.getNotification().getNotification2().getNotification3();
            return item.getPackage() + "\n" + item.getKey();
        }
        return "\n";
    }
}
