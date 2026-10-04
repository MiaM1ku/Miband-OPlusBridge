// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import io.github.miam1ku.mibandoplusbridge.protocol.LiveCommandQueue;
import com.google.protobuf.ByteString;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** One list/download round at a time; only durably saved and acknowledged files advance a batch. */
final class LiveHistorySync {
    private final LiveCommandQueue commands;
    private final ScheduledExecutorService worker;
    private final Runnable completed;
    private final Consumer<String> failed;
    private final java.util.function.BooleanSupplier capacity;
    private final ArrayDeque<byte[]> remaining = new ArrayDeque<>();
    private final Set<String> pending = new HashSet<>();
    /** File ids already stored this session. The band keeps listing them; do not download again. */
    private final Set<String> stored = new HashSet<>();
    private boolean busy;
    private boolean includeHistory;
    private boolean historical;
    private boolean listing;
    private long generation;
    private java.util.concurrent.ScheduledFuture<?> batchDeadline;

    LiveHistorySync(LiveCommandQueue commands, ScheduledExecutorService worker, Runnable completed, Consumer<String> failed,
            java.util.function.BooleanSupplier capacity) {
        this.commands = commands;
        this.worker = worker;
        this.completed = completed;
        this.failed = failed;
        this.capacity = capacity;
    }

    // Phone-initiated, matching the official app: 8/1 lists today, then 8/2 lists older days.
    // The band's own step total arrives separately as an unsolicited 8/47 after 8/45.
    void request(boolean history) {
        includeHistory |= history;
        if (busy) return;
        busy = true;
        historical = false;
        generation++;
        list(1, generation);
    }

    private void list(int subtype, long round) {
        listing = true;
        XiaomiProto.Command.Builder command = XiaomiProto.Command.newBuilder().setType(8).setSubtype(subtype);
        if (subtype == 1) command.setHealth(XiaomiProto.Health.newBuilder().setActivitySyncRequestToday(
                XiaomiProto.ActivitySyncRequestToday.newBuilder().setUnknown1(0)));
        commands.request(command.build(), 8, subtype).whenCompleteAsync((response, error) -> {
            if (!busy || round != generation) return;
            listing = false;
            if (error != null) { fail("HEALTH_LIST_FAILED"); return; }
            byte[] ids = response.hasHealth() && response.getHealth().hasActivityRequestFileIds()
                    ? response.getHealth().getActivityRequestFileIds().toByteArray() : new byte[0];
            if (ids.length % 7 != 0) { fail("HEALTH_FILE_LIST_INVALID"); return; }
            Set<String> unique = new HashSet<>();
            for (int offset = 0; offset < ids.length; offset += 7) {
                byte[] id = java.util.Arrays.copyOfRange(ids, offset, offset + 7);
                String hex = HexFormat.of().formatHex(id);
                if (stored.contains(hex) || !unique.add(hex)) continue;
                remaining.addLast(id);
            }
            nextBatch(round);
        }, worker);
    }

    private void nextBatch(long round) {
        if (!busy || listing || !pending.isEmpty()) return;
        if (remaining.isEmpty()) {
            if (!historical && includeHistory) {
                historical = true;
                includeHistory = false;
                list(2, round);
            } else {
                busy = false;
                includeHistory = false;
                completed.run();
            }
            return;
        }
        try {
            if (!capacity.getAsBoolean()) return;
        } catch (RuntimeException unavailable) {
            fail("HEALTH_STORAGE_UNAVAILABLE");
            return;
        }
        int count = Math.min(4, remaining.size());
        byte[] ids = new byte[count * 7];
        for (int i = 0; i < count; i++) {
            byte[] id = remaining.removeFirst();
            System.arraycopy(id, 0, ids, i * 7, 7);
            pending.add(HexFormat.of().formatHex(id));
        }
        progress();
        commands.send(XiaomiProto.Command.newBuilder().setType(8).setSubtype(3)
                .setHealth(XiaomiProto.Health.newBuilder().setActivityRequestFileIds(ByteString.copyFrom(ids))).build())
                .whenCompleteAsync((ignored, error) -> {
                    if (busy && round == generation && error != null) fail("HEALTH_DOWNLOAD_REQUEST_FAILED");
                }, worker);
    }

    void saved(byte[] fileId) {
        String hex = HexFormat.of().formatHex(fileId);
        if (busy && pending.remove(hex)) {
            stored.add(hex);
            if (pending.isEmpty()) {
                cancelDeadline();
                nextBatch(generation);
            } else progress();
        }
    }

    void resume() { nextBatch(generation); }

    void rejected(String reason) { if (busy) fail(reason); }

    void onCommand(XiaomiProto.Command command) {
        if (busy && command.getType() == 8 && command.hasStatus() && command.getStatus() != 0
                && (command.getSubtype() == 3 || command.getSubtype() == 4)) fail("HEALTH_DOWNLOAD_REJECTED");
    }

    void progress() {
        if (!busy || pending.isEmpty()) return;
        cancelDeadline();
        long round = generation;
        batchDeadline = worker.schedule(() -> {
            if (busy && round == generation && !pending.isEmpty()) fail("HEALTH_DOWNLOAD_TIMEOUT");
        }, commands.timeoutMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void cancelDeadline() {
        if (batchDeadline != null) {
            batchDeadline.cancel(false);
            batchDeadline = null;
        }
    }

    void close() {
        cancelDeadline();
        generation++;
        busy = false;
        listing = false;
        includeHistory = false;
        remaining.clear();
        pending.clear();
    }

    private void fail(String reason) {
        cancelDeadline();
        busy = false;
        listing = false;
        includeHistory = false;
        remaining.clear();
        pending.clear();
        failed.accept(reason);
    }
}
