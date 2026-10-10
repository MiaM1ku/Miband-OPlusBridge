// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/** Durable CE replay copies and their account-isolated database index, committed before band ACK. */
public final class RawFitnessFileStore {
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final int MAX_FILES = 2048;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final Object FILE_LOCK = new Object();
    private static volatile boolean stepMetricsCurrent;
    private static volatile boolean sleepSummaryCurrent;
    private static volatile boolean sleepBreathMetricsCurrent;
    private final Context context;
    private final File directory;

    public RawFitnessFileStore(Context context) {
        if (context.isDeviceProtectedStorage()) throw new IllegalArgumentException("CE_STORAGE_REQUIRED");
        this.context = context;
        directory = new File(context.getNoBackupFilesDir(), "band-history-files");
    }

    public int storedFiles() {
        synchronized (FILE_LOCK) {
            File[] existing = directory.listFiles((parent, name) -> name.endsWith(".dat"));
            return existing == null ? 0 : existing.length;
        }
    }

    public File persist(BandHistoryParser.FileResult result, String deviceId, String firmware,
            long capturedAtMs) throws Exception {
        synchronized (FILE_LOCK) {
            requireMatchingIdentity(context, deviceId);
            byte[] fileId = result.copyFileId();
            byte[] bytes = result.copyRawFile();
            try {
                if (fileId.length != 7 || bytes.length < 12 || bytes.length > MAX_FILE_BYTES
                        || !Arrays.equals(fileId, Arrays.copyOf(bytes, 7))) {
                    throw new IllegalArgumentException("INVALID_HISTORY_SOURCE_FILE");
                }
                String hash = fileHash(bytes);
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IllegalStateException("HISTORY_DIRECTORY_UNAVAILABLE");
                }
                Os.chmod(directory.getAbsolutePath(), 0700);
                File target = new File(directory, hash + ".dat");
                AtomicFile output = new AtomicFile(target);
                if (target.isFile()) {
                    byte[] existing = readFile(hash);
                    try {
                        if (!Arrays.equals(existing, bytes)) throw new IllegalStateException("HISTORY_REPLAY_CONFLICT");
                    } finally {
                        Arrays.fill(existing, (byte) 0);
                    }
                } else {
                    makeRoom(bytes.length);
                    File[] existing = directory.listFiles((parent, name) -> name.endsWith(".dat"));
                    if (existing == null || existing.length >= MAX_FILES) {
                        throw new IllegalStateException("HISTORY_REPLAY_LIMIT_REACHED");
                    }
                    long occupied = 0;
                    for (File file : existing) occupied += file.length();
                    if (occupied > MAX_TOTAL_BYTES - bytes.length) {
                        throw new IllegalStateException("HISTORY_REPLAY_LIMIT_REACHED");
                    }
                    FileOutputStream stream = null;
                    try {
                        stream = output.startWrite();
                        stream.write(bytes);
                        // AtomicFile logs some sync failures instead of throwing: require a checked sync first.
                        stream.getFD().sync();
                        output.finishWrite(stream);
                        stream = null;
                        Os.chmod(target.getAbsolutePath(), 0600);
                        byte[] committed = readFile(hash);
                        Arrays.fill(committed, (byte) 0);
                    } catch (Exception failure) {
                        if (stream != null) output.failWrite(stream);
                        throw failure;
                    }
                }
                syncDirectory(directory);
                syncDirectory(directory.getParentFile());
                try (HealthRecordStore store = new HealthRecordStore(context)) {
                    store.indexFile(hash, deviceId, firmware, capturedAtMs, result.parseStatus, result.measurements.size());
                }
                return target;
            } finally {
                Arrays.fill(fileId, (byte) 0);
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }
    /** Finished archives already live in the measurement tables. Drop the oldest until one slot fits. */
    private void makeRoom(long incomingBytes) {
        File[] existing = directory.listFiles((parent, name) -> name.endsWith(".dat"));
        int count = existing == null ? 0 : existing.length;
        long occupied = 0;
        if (existing != null) for (File file : existing) occupied += file.length();
        if (count < MAX_FILES && occupied <= MAX_TOTAL_BYTES - incomingBytes) return;
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            for (String hash : store.finishedFileHashes(Math.max(count, 1))) {
                File raw = new File(directory, hash + ".dat");
                boolean present = raw.isFile();
                long size = present ? raw.length() : 0;
                if (!store.dropFinishedFile(hash)) continue;
                if (present && !raw.delete() && raw.isFile()) continue;
                if (present) {
                    count--;
                    occupied -= size;
                }
                if (count < MAX_FILES && occupied <= MAX_TOTAL_BYTES - incomingBytes) return;
            }
            File[] left = directory.listFiles((parent, name) -> name.endsWith(".dat"));
            if (left == null) return;
            count = left.length;
            occupied = 0;
            for (File file : left) occupied += file.length();
            for (File file : left) {
                String name = file.getName();
                if (name.length() != 68) continue;
                String hash = name.substring(0, 64);
                if (store.isFileIndexed(hash)) continue;
                long size = file.length();
                if (!file.delete() && file.isFile()) continue;
                count--;
                occupied -= size;
                if (count < MAX_FILES && occupied <= MAX_TOTAL_BYTES - incomingBytes) return;
            }
        }
    }



    private static void syncDirectory(File directory) throws Exception {
        if (!directory.isDirectory()) throw new IllegalStateException("HISTORY_DIRECTORY_UNAVAILABLE");
        FileDescriptor descriptor = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
        try {
            Os.fsync(descriptor);
        } finally {
            Os.close(descriptor);
        }
    }

    /** Verifies the legacy filename algorithm SHA256(fileId || raw), before parser CRC validation. */
    public byte[] readFile(String hash) throws Exception {
        HealthRecordStore.requireHash(hash);
        synchronized (FILE_LOCK) {
            File target = new File(directory, hash + ".dat");
            if (target.length() < 12 || target.length() > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("INVALID_HISTORY_SOURCE_FILE");
            }
            byte[] bytes = new AtomicFile(target).readFully();
            if (bytes.length < 12 || bytes.length > MAX_FILE_BYTES || !hash.equals(fileHash(bytes))) {
                Arrays.fill(bytes, (byte) 0);
                throw new IllegalArgumentException("HISTORY_REPLAY_HASH_MISMATCH");
            }
            return bytes;
        }
    }

    private static String fileHash(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(bytes, 0, 7);
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    /** No current binding is ever assigned to an old file unless the saved collection identity matches. */
    public int migrateLegacyFiles() throws Exception {
        synchronized (FILE_LOCK) {
            var state = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(context, "band-state");
            String deviceId = state.getString("deviceId", "");
            String identity;
            try {
                identity = requireMatchingIdentity(context, deviceId);
            } catch (SecurityException unverified) {
                return 0;
            }
            File[] existing = directory.listFiles((parent, name) -> name.matches("[0-9a-f]{64}\\.dat"));
            if (existing == null || existing.length == 0) return 0;
            String firmware = state.getString("verifiedFirmware", "");
            if (firmware.isBlank()) throw new IllegalStateException("HISTORY_FIRMWARE_UNCONFIRMED");
            BandHistoryParser parser;
            try {
                parser = new BandHistoryParser(firmware, deviceId, identity);
            } catch (IllegalArgumentException unsupported) {
                return 0;
            }
            int migrated = 0;
            try (HealthRecordStore store = new HealthRecordStore(context)) {
                for (File file : existing) {
                    String hash = file.getName().substring(0, 64);
                    if (store.isFileIndexed(hash)) continue;
                    byte[] bytes;
                    try {
                        bytes = readFile(hash);
                    } catch (IllegalArgumentException invalid) {
                        continue; // Preserve damaged/unidentified originals, but never claim or ACK them.
                    }
                    try {
                        BandHistoryParser.FileResult result;
                        try {
                            result = parser.parseFile(bytes);
                        } catch (IllegalArgumentException invalid) {
                            continue;
                        }
                        store.indexFile(hash, deviceId, firmware, file.lastModified(), result.parseStatus, result.measurements.size());
                        migrated++;
                    } finally {
                        Arrays.fill(bytes, (byte) 0);
                    }
                }
            }
            return migrated;
        }
    }

    /** One pass so step rows saved before calories and stand hours were parsed pick them up. */
    public void refreshStepMetrics() {
        if (stepMetricsCurrent) return;
        synchronized (FILE_LOCK) {
            if (stepMetricsCurrent) return;
            SharedPreferences prefs = context.getSharedPreferences("oplusband-history", Context.MODE_PRIVATE);
            if (prefs.getBoolean("step-metrics-v1", false)) {
                stepMetricsCurrent = true;
                return;
            }
            try {
                rewriteStepMetrics();
                if (!prefs.edit().putBoolean("step-metrics-v1", true).commit()) {
                    throw new IllegalStateException("STEP_METRIC_MEMORY_FAILED");
                }
                stepMetricsCurrent = true;
            } catch (Exception ignored) {
                // Identity or a file can be unready. The next step read tries again.
            }
        }
    }

    /** One pass so sleep breath rows parsed from existing files are enqueued. */
    public int refreshSleepBreathMetrics() {
        if (sleepBreathMetricsCurrent) return 0;
        synchronized (FILE_LOCK) {
            if (sleepBreathMetricsCurrent) return 0;
            SharedPreferences prefs = context.getSharedPreferences("oplusband-history", Context.MODE_PRIVATE);
            if (prefs.getBoolean("sleep-breath-v1", false)) {
                sleepBreathMetricsCurrent = true;
                return 0;
            }
            try {
                int enqueued = rewriteSleepBreathMetrics();
                if (!prefs.edit().putBoolean("sleep-breath-v1", true).commit()) {
                    throw new IllegalStateException("SLEEP_BREATH_MEMORY_FAILED");
                }
                sleepBreathMetricsCurrent = true;
                if (enqueued > 0) {
                    context.getContentResolver().notifyChange(
                            io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider.URI, null);
                    context.getContentResolver().notifyChange(
                            io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider.RECORDS_URI, null);
                }
                return enqueued;
            } catch (Exception ignored) {
                // Identity or a file can be unready. The next sleep read tries again.
                return 0;
            }
        }
    }

    /** One pass over files rejected before sleep summary version 4 could be parsed. */
    public int promoteRejectedSleep() {
        if (sleepSummaryCurrent) return 0;
        synchronized (FILE_LOCK) {
            if (sleepSummaryCurrent) return 0;
            SharedPreferences prefs = context.getSharedPreferences("oplusband-history", Context.MODE_PRIVATE);
            if (prefs.getBoolean("sleep-summary-v4", false)) {
                sleepSummaryCurrent = true;
                return 0;
            }
            try {
                int promoted = rewriteRejectedSleep();
                if (!prefs.edit().putBoolean("sleep-summary-v4", true).commit()) {
                    throw new IllegalStateException("SLEEP_SUMMARY_MEMORY_FAILED");
                }
                sleepSummaryCurrent = true;
                return promoted;
            } catch (Exception ignored) {
                return 0;
            }
        }
    }

    private int rewriteRejectedSleep() throws Exception {
        var state = LocalPrefs.open(context, "band-state");
        String deviceId = state.getString("deviceId", "");
        String identity = requireMatchingIdentity(context, deviceId);
        String firmware = state.getString("verifiedFirmware", "");
        if (firmware.isBlank()) throw new IllegalStateException("HISTORY_FIRMWARE_UNCONFIRMED");
        BandHistoryParser parser = new BandHistoryParser(firmware, deviceId, identity);
        File[] existing = directory.listFiles((parent, name) -> name.matches("[0-9a-f]{64}\\.dat"));
        if (existing == null) return 0;
        int promoted = 0;
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            for (File file : existing) {
                String hash = file.getName().substring(0, 64);
                if (!store.isFileIndexed(hash)) continue;
                byte[] bytes;
                try {
                    bytes = readFile(hash);
                } catch (IllegalArgumentException invalid) {
                    continue;
                }
                try {
                    int descriptor = bytes.length > 6 ? bytes[6] & 0xff : 0;
                    if (((descriptor & 0x7f) >>> 2) != 8 || (descriptor & 3) != 1) continue;
                    BandHistoryParser.FileResult result = parser.parseFile(bytes);
                    if (!"PARSED".equals(result.parseStatus)) continue;
                    boolean sleep = false;
                    for (BandHistoryParser.Measurement measurement : result.measurements) {
                        if ("sleep_interval".equals(measurement.kind) || "sleep_stage".equals(measurement.kind)) {
                            sleep = true;
                            break;
                        }
                    }
                    if (sleep && store.promoteRejectedFile(hash, result.measurements.size())) promoted++;
                } catch (IllegalArgumentException invalid) {
                    continue;
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        }
        return promoted;
    }

    private void rewriteStepMetrics() throws Exception {
        var state = LocalPrefs.open(context, "band-state");
        String deviceId = state.getString("deviceId", "");
        String identity = requireMatchingIdentity(context, deviceId);
        String firmware = state.getString("verifiedFirmware", "");
        if (firmware.isBlank()) throw new IllegalStateException("HISTORY_FIRMWARE_UNCONFIRMED");
        BandHistoryParser parser = new BandHistoryParser(firmware, deviceId, identity);
        File[] existing = directory.listFiles((parent, name) -> name.matches("[0-9a-f]{64}\\.dat"));
        if (existing == null) return;
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            for (File file : existing) {
                String hash = file.getName().substring(0, 64);
                if (!store.isFileIndexed(hash)) continue;
                byte[] bytes;
                try {
                    bytes = readFile(hash);
                } catch (IllegalArgumentException invalid) {
                    continue;
                }
                try {
                    int descriptor = bytes.length > 6 ? bytes[6] & 0xff : 0;
                    if (((descriptor & 0x7f) >>> 2) != 0) continue;
                    BandHistoryParser.FileResult result = parser.parseFile(bytes);
                    if (!"PARSED".equals(result.parseStatus)) continue;
                    for (BandHistoryParser.Measurement measurement : result.measurements) {
                        if (!"steps_day".equals(measurement.kind) && !"steps_interval".equals(measurement.kind)) {
                            continue;
                        }
                        store.enqueueMeasurement(measurement);
                    }
                } catch (IllegalArgumentException invalid) {
                    continue;
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        }
    }

    private int rewriteSleepBreathMetrics() throws Exception {
        var state = LocalPrefs.open(context, "band-state");
        String deviceId = state.getString("deviceId", "");
        String identity = requireMatchingIdentity(context, deviceId);
        String firmware = state.getString("verifiedFirmware", "");
        if (firmware.isBlank()) throw new IllegalStateException("HISTORY_FIRMWARE_UNCONFIRMED");
        BandHistoryParser parser = new BandHistoryParser(firmware, deviceId, identity);
        File[] existing = directory.listFiles((parent, name) -> name.matches("[0-9a-f]{64}\\.dat"));
        if (existing == null) return 0;
        int enqueued = 0;
        try (HealthRecordStore store = new HealthRecordStore(context)) {
            for (File file : existing) {
                String hash = file.getName().substring(0, 64);
                if (!store.isFileIndexed(hash)) continue;
                byte[] bytes;
                try {
                    bytes = readFile(hash);
                } catch (IllegalArgumentException invalid) {
                    continue;
                }
                try {
                    int descriptor = bytes.length > 6 ? bytes[6] & 0xff : 0;
                    if (((descriptor & 0x7f) >>> 2) != 8 || (descriptor & 3) != 1) continue;
                    BandHistoryParser.FileResult result = parser.parseFile(bytes);
                    if (!"PARSED".equals(result.parseStatus)) continue;
                    for (BandHistoryParser.Measurement measurement : result.measurements) {
                        if (!"sleep_breath".equals(measurement.kind)) {
                            continue;
                        }
                        if (store.enqueueMeasurement(measurement).added()) {
                            enqueued++;
                        }
                    }
                } catch (IllegalArgumentException invalid) {
                    continue;
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        }
        return enqueued;
    }

    /** A persistent identity check, deliberately independent of registration, connection and ownership mode. */
    public static String requireMatchingIdentity(Context context, String deviceId) {
        try {
            var state = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(context, "band-state");
            BindingStore.Identity binding = new BindingStore(context).readIdentity();
            if (binding == null || deviceId == null || deviceId.isBlank()
                    || !deviceId.equals(state.getString("deviceId", ""))
                    || !binding.address().equals(state.getString("mac", ""))
                    || !binding.model().equals(state.getString("modelId", ""))) {
                throw new SecurityException("HEALTH_DEVICE_IDENTITY_UNCONFIRMED");
            }
            String did = binding.did();
            String address = binding.address();
            if (!address.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) {
                throw new SecurityException("HEALTH_DEVICE_IDENTITY_UNCONFIRMED");
            }
            String source = did.isBlank() ? "verifiedMac" : "did";
            if (!source.equals(state.getString("identitySource", ""))) {
                throw new SecurityException("HEALTH_DEVICE_IDENTITY_UNCONFIRMED");
            }
            String identity = did.isBlank() ? address.replace(":", "") : did;
            String expected = "miband11_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
            if (!expected.equals(deviceId)) throw new SecurityException("HEALTH_DEVICE_IDENTITY_UNCONFIRMED");
            return identity;
        } catch (SecurityException rejected) {
            throw rejected;
        } catch (Exception unavailable) {
            throw new SecurityException("HEALTH_DEVICE_IDENTITY_UNCONFIRMED", unavailable);
        }
    }
}
