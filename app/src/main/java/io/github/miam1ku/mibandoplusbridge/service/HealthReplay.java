// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.content.Context;
import android.database.ContentObserver;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.data.BindingStore;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecordStore;
import io.github.miam1ku.mibandoplusbridge.data.RawFitnessFileStore;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser;
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
        catch (RuntimeException unavailable) { status.accept("HEALTH_STORAGE_UNAVAILABLE"); return false; }
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
        var binding = new BindingStore(context).readIdentity();
        String identity = binding.did();
        if (identity.isBlank()) identity = binding.address().replace(":", "");
        String deviceId = binding.deviceId();
        while (!closed) {
            var files = records.replayFiles(4);
            if (files.isEmpty()) break;
            boolean added = false;
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
                        var result = records.enqueueArchivedMeasurement(file.fileHash(), index, parsed.measurements.get(index));
                        added |= result.added();
                    }
                }
            } finally {
                if (added) {
                    context.getContentResolver().notifyChange(HealthQueueProvider.URI, null);
                    context.getContentResolver().notifyChange(HealthQueueProvider.RECORDS_URI, null);
                }
            }
        }
        if (!closed) BandStateRepository.refreshStoredSteps(context);
        status.accept("HEALTH_LOCAL_RECORDS_READY");
    }

    @Override public void close() {
        closed = true;
        context.getContentResolver().unregisterContentObserver(observer);
        worker.execute(records::close);
        worker.shutdown();
    }
}
