// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.content.Context;
import android.database.ContentObserver;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.data.BindingStore;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecordStore;
import io.github.miam1ku.mibandoplusbridge.data.RawFitnessFileStore;
import io.github.miam1ku.mibandoplusbridge.data.SleepStageAlign;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Replays durable device/account-owned files without occupying the socket or command coordinator. */
final class HealthReplay implements AutoCloseable {
    private final Context context;
    private final HealthRecordStore records;
    private final RawFitnessFileStore raw;
    private final Consumer<String> status;
    private final Runnable capacityChanged;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "OplusBandHealthReplay"));
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean closed;
    private boolean legacyIndexed;
    private boolean sleepStagesAligned;
    private final ContentObserver observer = new ContentObserver(null) {
        @Override public void onChange(boolean selfChange) { request(); }
    };

    HealthReplay(Context context, Consumer<String> status, Runnable capacityChanged) {
        this.context = context.getApplicationContext();
        this.records = new HealthRecordStore(this.context);
        this.raw = new RawFitnessFileStore(this.context);
        this.status = status;
        this.capacityChanged = capacityChanged;
        this.context.getContentResolver().registerContentObserver(HealthQueueProvider.URI, false, observer);
    }

    boolean hasCapacity() {
        if (closed) return false;
        try { return records.hasCapacity(); }
        catch (RuntimeException unavailable) {
            status.accept("HEALTH_STORAGE_UNAVAILABLE");
            throw unavailable;
        }
    }

    void request() {
        if (closed) return;
        requested.set(true);
        if (!running.compareAndSet(false, true)) return;
        try { worker.execute(this::drain); }
        catch (RejectedExecutionException stopped) { running.set(false); }
    }

    private void drain() {
        try {
            while (!closed && requested.getAndSet(false)) replay();
        } catch (Exception failure) {
            String code = failure.getMessage();
            status.accept("HEALTH_OUTBOX_FULL".equals(code) ? "HEALTH_OUTBOX_FULL"
                    : "ACCOUNT_CONFIRMATION_REQUIRED".equals(code) ? "ACCOUNT_CONFIRMATION_REQUIRED"
                    : "HEALTH_REPLAY_PAUSED");
        } finally {
            running.set(false);
            if (!closed) {
                capacityChanged.run();
                if (requested.get()) request();
            }
        }
    }

    private void replay() throws Exception {
        if (!context.getSystemService(UserManager.class).isUserUnlocked()) return;
        if (!legacyIndexed) {
            raw.migrateLegacyFiles();
            legacyIndexed = true;
        }
        if (records.confirmedAccountHash() == null) {
            status.accept("ACCOUNT_CONFIRMATION_REQUIRED");
            return;
        }
        int reparsed = raw.promoteRejectedSleep();
        if (reparsed > 0) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "history sleep reparsed " + reparsed);
        }
        var binding = new BindingStore(context).readIdentity();
        String identity = binding.did();
        if (identity.isBlank()) identity = binding.address().replace(":", "");
        String deviceId = binding.deviceId();
        while (!closed) {
            var files = records.replayFiles(4);
            if (files.isEmpty()) break;
            boolean added = false;
            boolean sawSleep = false;
            try {
                for (var file : files) {
                    if (closed) return;
                    if (!deviceId.equals(file.deviceId())) throw new IllegalStateException("HEALTH_COLLECTION_IDENTITY_CHANGED");
                    var parsed = new BandHistoryParser(file.firmware(), file.deviceId(), identity)
                            .parseFile(raw.readFile(file.fileHash()));
                    if (!"PARSED".equals(parsed.parseStatus) || parsed.measurements.size() != file.recordCount()) {
                        throw new IllegalStateException("HEALTH_ARCHIVE_PARSE_CHANGED");
                    }
                    for (int index = file.nextRecordIndex(); index < parsed.measurements.size(); index++) {
                        if (closed) return;
                        var measurement = parsed.measurements.get(index);
                        var result = records.enqueueArchivedMeasurement(file.fileHash(), index, measurement);
                        added |= result.added();
                        if ("sleep_interval".equals(measurement.kind) || "sleep_stage".equals(measurement.kind)) {
                            sawSleep = true;
                        }
                    }
                }
            } finally {
                if (added) {
                    context.getContentResolver().notifyChange(HealthQueueProvider.URI, null);
                    context.getContentResolver().notifyChange(HealthQueueProvider.RECORDS_URI, null);
                }
            }
            if (sawSleep) sleepStagesAligned = false;
        }
        alignStoredSleep(identity);
        if (!closed) BandStateRepository.refreshStoredSteps(context);
        status.accept("HEALTH_LOCAL_RECORDS_READY");
    }

    /** Newest file that still has stages owns the interval. Older files must not delete it. */
    private void alignStoredSleep(String identity) throws Exception {
        if (sleepStagesAligned || closed) return;
        boolean added = false;
        var files = records.completedFiles();
        Set<String> blocked = new HashSet<>();
        var claimed = new java.util.HashMap<String, List<SleepStageAlign.Interval>>();
        for (int i = files.size() - 1; i >= 0; i--) {
            if (closed) return;
            var file = files.get(i);
            if (blocked.contains(file.deviceId())) continue;
            BandHistoryParser.FileResult parsed;
            try {
                parsed = new BandHistoryParser(file.firmware(), file.deviceId(), identity)
                        .parseFile(raw.readFile(file.fileHash()));
            } catch (RuntimeException unreadable) {
                blocked.add(file.deviceId());
                continue;
            }
            if (!"PARSED".equals(parsed.parseStatus)) {
                blocked.add(file.deviceId());
                continue;
            }
            var intervals = spans(parsed.measurements, file.deviceId(), "sleep_interval");
            var stages = spans(parsed.measurements, file.deviceId(), "sleep_stage");
            var owned = claimed.computeIfAbsent(file.deviceId(), key -> new ArrayList<>());
            var covered = SleepStageAlign.withStages(SleepStageAlign.claim(intervals, owned), stages);
            if (covered.isEmpty()) continue;
            if (records.retainSleepStages(file.deviceId(), parsed.measurements, covered) > 0) added = true;
            owned.addAll(covered);
        }
        sleepStagesAligned = true;
        if (added) {
            context.getContentResolver().notifyChange(HealthQueueProvider.URI, null);
            context.getContentResolver().notifyChange(HealthQueueProvider.RECORDS_URI, null);
        }
    }

    private static List<SleepStageAlign.Interval> spans(
            List<BandHistoryParser.Measurement> parsed, String deviceId, String kind) {
        List<SleepStageAlign.Interval> found = new ArrayList<>();
        if (parsed == null) return found;
        for (var measurement : parsed) {
            if (measurement != null && deviceId.equals(measurement.deviceId) && kind.equals(measurement.kind)) {
                found.add(new SleepStageAlign.Interval(measurement.startMs, measurement.endMs));
            }
        }
        return found;
    }

    @Override public void close() {
        closed = true;
        context.getContentResolver().unregisterContentObserver(observer);
        worker.execute(records::close);
        worker.shutdown();
    }
}
