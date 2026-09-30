// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser.Measurement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/** CE measurement history, revision ledger, bounded outbox and durable archive ownership. */
public final class HealthRecordStore extends SQLiteOpenHelper {
    public static final int PAGE_SIZE = 200;
    private static final int MAX_RECORDS = 32768;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_RECORD_BYTES = 8192;

    public HealthRecordStore(Context context) {
        super(requireCredentialProtected(context), "health-outbox.db", null, 2);
    }
    private static Context requireCredentialProtected(Context context) {
        if (context.isDeviceProtectedStorage()) throw new IllegalArgumentException("CE_STORAGE_REQUIRED");
        return context;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE binding (id INTEGER PRIMARY KEY CHECK(id=1), account_hash TEXT NOT NULL, paused INTEGER NOT NULL)");
        createOutbox(db);
        db.execSQL("CREATE TABLE revisions (account_hash TEXT NOT NULL, record_id TEXT NOT NULL, "
                + "device_id TEXT NOT NULL, last_fingerprint TEXT NOT NULL, last_revision INTEGER NOT NULL, "
                + "PRIMARY KEY(account_hash, record_id))");
        createHistory(db);
    }

    private static void createOutbox(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records (record_id TEXT NOT NULL, revision INTEGER NOT NULL, "
                + "device_id TEXT NOT NULL, account_hash TEXT NOT NULL, payload TEXT NOT NULL, "
                + "byte_size INTEGER NOT NULL, PRIMARY KEY(account_hash, record_id))");
    }

    private static void createHistory(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE measurements (account_hash TEXT NOT NULL, record_id TEXT NOT NULL, "
                + "revision INTEGER NOT NULL, device_id TEXT NOT NULL, kind TEXT NOT NULL, "
                + "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, payload TEXT NOT NULL, "
                + "PRIMARY KEY(account_hash, record_id))");
        db.execSQL("CREATE INDEX measurements_window ON measurements(account_hash, device_id, kind, start_ms)");
        db.execSQL("CREATE TABLE files (file_hash TEXT PRIMARY KEY NOT NULL, device_id TEXT NOT NULL, "
                + "firmware TEXT, captured_at_ms INTEGER NOT NULL, account_hash TEXT, "
                + "next_record_index INTEGER NOT NULL DEFAULT 0, parse_status TEXT NOT NULL, "
                + "record_count INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX files_replay ON files(account_hash, parse_status, captured_at_ms)");
    }

    /** SQLiteOpenHelper runs this entire migration and version change in its upgrade transaction. */
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion != 1 || newVersion != 2) throw new IllegalStateException("HEALTH_OUTBOX_MIGRATION_REQUIRED");
        db.execSQL("ALTER TABLE records RENAME TO records_v1");
        createOutbox(db);
        createHistory(db);
        try (Cursor rows = db.rawQuery("SELECT r.account_hash, r.record_id, r.revision, r.device_id, r.payload "
                + "FROM records_v1 r WHERE r.revision=(SELECT MAX(v.revision) FROM records_v1 v "
                + "WHERE v.account_hash=r.account_hash AND v.record_id=r.record_id)", null)) {
            while (rows.moveToNext()) {
                JSONObject json = new JSONObject(rows.getString(4));
                String id = rows.getString(1) + ":continuous";
                json.put("recordId", id);
                json.put("measurementMode", "continuous");
                json.put("complete", false);
                String payload = json.toString();
                String kind = json.getString("kind");
                ContentValues measurement = measurementRow(rows.getString(0), id, rows.getInt(2),
                        rows.getString(3), kind, json.getLong("startMs"), json.getLong("endMs"), payload);
                db.insertOrThrow("measurements", null, measurement);
                if (isHostKind(kind)) {
                    db.insertOrThrow("records", null, outboxRow(rows.getString(0), id, rows.getInt(2),
                            rows.getString(3), payload, payload.getBytes(StandardCharsets.UTF_8).length));
                }
            }
        } catch (JSONException invalid) {
            throw new IllegalStateException("HEALTH_OUTBOX_MIGRATION_INVALID_PAYLOAD", invalid);
        }
        db.execSQL("UPDATE revisions SET record_id=record_id || ':continuous'");
        db.execSQL("DROP TABLE records_v1");
    }

    public static String hashAccount(String account) {
        if (account == null || account.isBlank() || account.length() > 512) {
            throw new IllegalArgumentException("ACCOUNT_REQUIRED");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(account.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("ACCOUNT_HASH_UNAVAILABLE", impossible);
        }
    }

    /** Unsent archives and the outbox follow the current account. Stored measurements stay put. */
    public synchronized void confirmAccountHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("ACCOUNT_HASH_INVALID");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues(3);
            values.put("id", 1);
            values.put("account_hash", hash);
            values.put("paused", 0);
            if (db.insertWithOnConflict("binding", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
                throw new IllegalStateException("HEALTH_ACCOUNT_WRITE_FAILED");
            }
            ContentValues owner = new ContentValues(1);
            owner.put("account_hash", hash);
            db.update("files", owner,
                    "account_hash IS NULL OR (account_hash!=? AND next_record_index<record_count)",
                    new String[]{hash});
            db.execSQL("DELETE FROM records WHERE account_hash!=? AND EXISTS ("
                    + "SELECT 1 FROM records existing WHERE existing.account_hash=? "
                    + "AND existing.record_id=records.record_id)", new Object[]{hash, hash});
            db.update("records", owner, "account_hash IS NULL OR account_hash!=?", new String[]{hash});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized String accountState() {
        try (Cursor binding = getReadableDatabase().rawQuery("SELECT paused FROM binding WHERE id=1", null)) {
            if (!binding.moveToFirst()) return "ACCOUNT_UNCONFIRMED";
            return binding.getInt(0) == 0 ? "ACCOUNT_CONFIRMED" : "OHEALTH_ACCOUNT_CHANGED";
        }
    }

    public synchronized String confirmedAccountHash() {
        return confirmedAccountHash(getReadableDatabase());
    }

    private static String confirmedAccountHash(SQLiteDatabase db) {
        try (Cursor binding = db.rawQuery("SELECT account_hash FROM binding WHERE id=1 AND paused=0", null)) {
            return binding.moveToFirst() ? binding.getString(0) : null;
        }
    }

    private static String requireConfirmedAccount(SQLiteDatabase db) {
        String account = confirmedAccountHash(db);
        if (account == null) throw new IllegalStateException("ACCOUNT_CONFIRMATION_REQUIRED");
        return account;
    }

    public record EnqueueResult(int revision, boolean added) {}
    public record ArchivedFile(String fileHash, String deviceId, String firmware, long capturedAtMs,
            String accountHash, int nextRecordIndex, String parseStatus, int recordCount) {}

    public synchronized EnqueueResult enqueueMeasurement(Measurement measurement) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            EnqueueResult result = enqueue(db, requireConfirmedAccount(db), measurement);
            db.setTransactionSuccessful();
            return result;
        } finally {
            db.endTransaction();
        }
    }

    /** One transaction covers deduplication, history, ledger, replacement outbox and replay cursor. */
    public synchronized EnqueueResult enqueueArchivedMeasurement(String fileHash, int expectedIndex,
            Measurement measurement) {
        requireHash(fileHash);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String account = requireConfirmedAccount(db);
            try (Cursor file = db.rawQuery("SELECT device_id, account_hash, next_record_index, record_count, parse_status "
                    + "FROM files WHERE file_hash=?", new String[]{fileHash})) {
                if (!file.moveToFirst() || !account.equals(file.getString(1))
                        || measurement == null || !measurement.deviceId.equals(file.getString(0))) {
                    throw new SecurityException("HEALTH_ARCHIVE_OWNERSHIP_REQUIRED");
                }
                if (expectedIndex < 0 || expectedIndex != file.getInt(2) || expectedIndex >= file.getInt(3)
                        || !"PARSED".equals(file.getString(4))) {
                    throw new IllegalStateException("HEALTH_ARCHIVE_CURSOR_CHANGED");
                }
            }
            EnqueueResult result = enqueue(db, account, measurement);
            ContentValues cursor = new ContentValues(1);
            cursor.put("next_record_index", expectedIndex + 1);
            if (db.update("files", cursor, "file_hash=? AND account_hash=? AND next_record_index=?",
                    new String[]{fileHash, account, Integer.toString(expectedIndex)}) != 1) {
                throw new IllegalStateException("HEALTH_ARCHIVE_CURSOR_CHANGED");
            }
            db.setTransactionSuccessful();
            return result;
        } finally {
            db.endTransaction();
        }
    }

    private static EnqueueResult enqueue(SQLiteDatabase db, String account, Measurement measurement) {
        if (measurement == null || measurement.recordId.length() > 256 || measurement.deviceId.length() > 128
                || !measurement.sourceFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("INVALID_HEALTH_MEASUREMENT_KEY");
        }
        int revision = 1;
        try (Cursor current = db.rawQuery("SELECT device_id, last_fingerprint, last_revision FROM revisions "
                + "WHERE account_hash=? AND record_id=?", new String[]{account, measurement.recordId})) {
            if (current.moveToFirst()) {
                if (!measurement.deviceId.equals(current.getString(0))) {
                    throw new IllegalStateException("HEALTH_RECORD_IDENTITY_CONFLICT");
                }
                if (measurement.sourceFingerprint.equals(current.getString(1))) return new EnqueueResult(current.getInt(2), false);
                if (current.getInt(2) == Integer.MAX_VALUE) throw new IllegalStateException("HEALTH_REVISION_EXHAUSTED");
                revision = current.getInt(2) + 1;
            }
        }
        HealthRecord record = measurement.toRecord(revision);
        String payload = record.toJson().toString();
        int size = payload.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_RECORD_BYTES) throw new IllegalArgumentException("HEALTH_RECORD_TOO_LARGE");
        String[] key = {account, record.recordId};
        // Delete inside the transaction: a failed capacity check restores the previous revision.
        db.delete("records", "account_hash=? AND record_id=?", key);
        if (isHostKind(record.kind)) {
            try (Cursor totals = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(byte_size), 0) FROM records", null)) {
                totals.moveToFirst();
                if (totals.getInt(0) >= MAX_RECORDS || totals.getLong(1) + size > MAX_BYTES) {
                    throw new IllegalStateException("HEALTH_OUTBOX_FULL");
                }
            }
            db.insertOrThrow("records", null, outboxRow(account, record.recordId, revision, record.deviceId, payload, size));
        }
        if (db.insertWithOnConflict("measurements", null, measurementRow(account, record.recordId, revision,
                record.deviceId, record.kind, record.startMs, record.endMs, payload), SQLiteDatabase.CONFLICT_REPLACE) < 0) {
            throw new IllegalStateException("HEALTH_MEASUREMENT_WRITE_FAILED");
        }
        ContentValues version = new ContentValues(5);
        version.put("account_hash", account);
        version.put("record_id", record.recordId);
        version.put("device_id", record.deviceId);
        version.put("last_fingerprint", measurement.sourceFingerprint);
        version.put("last_revision", revision);
        if (db.insertWithOnConflict("revisions", null, version, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
            throw new IllegalStateException("HEALTH_REVISION_WRITE_FAILED");
        }
        return new EnqueueResult(revision, true);
    }

    private static boolean isHostKind(String kind) {
        return "heart_rate".equals(kind) || "spo2".equals(kind) || "stress".equals(kind);
    }

    private static ContentValues outboxRow(String account, String id, int revision, String device, String payload, int size) {
        ContentValues row = new ContentValues(6);
        row.put("account_hash", account);
        row.put("record_id", id);
        row.put("revision", revision);
        row.put("device_id", device);
        row.put("payload", payload);
        row.put("byte_size", size);
        return row;
    }

    private static ContentValues measurementRow(String account, String id, int revision, String device,
            String kind, long start, long end, String payload) {
        ContentValues row = new ContentValues(8);
        row.put("account_hash", account);
        row.put("record_id", id);
        row.put("revision", revision);
        row.put("device_id", device);
        row.put("kind", kind);
        row.put("start_ms", start);
        row.put("end_ms", end);
        row.put("payload", payload);
        return row;
    }

    /** Any real OHealth account becomes the write target. A blank or tourist id does not pause collection. */
    public synchronized boolean authorizedAccount(String account) {
        if (account == null || account.isBlank() || account.length() > 512 || "com.heytap.health".equals(account)) {
            return false;
        }
        String hash = hashAccount(account);
        try (Cursor binding = getReadableDatabase().rawQuery(
                "SELECT account_hash, paused FROM binding WHERE id=1", null)) {
            if (binding.moveToFirst() && hash.equals(binding.getString(0)) && binding.getInt(1) == 0) {
                return true;
            }
        }
        confirmAccountHash(hash);
        return true;
    }

    public synchronized Cursor pending(String account) {
        if (!authorizedAccount(account)) throw new SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED");
        return getReadableDatabase().rawQuery("SELECT record_id AS recordId, revision, payload AS record "
                + "FROM records WHERE account_hash=? AND account_hash=(SELECT account_hash FROM binding WHERE id=1 AND paused=0) "
                + "ORDER BY rowid LIMIT " + PAGE_SIZE, new String[]{hashAccount(account)});
    }

    public synchronized int acknowledge(String account, String recordId, int revision) {
        if (!authorizedAccount(account)) throw new SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED");
        if (recordId == null || recordId.isBlank() || recordId.length() > 256 || revision <= 0) {
            throw new IllegalArgumentException("INVALID_HEALTH_RECEIPT");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String hash = hashAccount(account);
            if (!hash.equals(requireConfirmedAccount(db))) throw new SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED");
            int removed = db.delete("records", "account_hash=? AND record_id=? AND revision=?",
                    new String[]{hash, recordId, Integer.toString(revision)});
            db.setTransactionSuccessful();
            return removed;
        } finally {
            db.endTransaction();
        }
    }
    public synchronized int releaseUnsupported(String account, String recordId, int revision) throws JSONException {
        if (!authorizedAccount(account)) throw new SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED");
        if (recordId == null || recordId.isBlank() || recordId.length() > 256 || revision <= 0) {
            throw new IllegalArgumentException("INVALID_HEALTH_RECEIPT");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String hash = hashAccount(account);
            if (!hash.equals(requireConfirmedAccount(db))) throw new SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED");
            String payload;
            try (Cursor cursor = db.rawQuery("SELECT payload FROM records WHERE account_hash=? AND record_id=? AND revision=?",
                    new String[]{hash, recordId, Integer.toString(revision)})) {
                if (!cursor.moveToFirst()) return 0;
                payload = cursor.getString(0);
            }
            if (HealthRecord.fromJson(new JSONObject(payload)).hostAccepts()) {
                throw new IllegalArgumentException("HOST_VALUE_IS_STORABLE");
            }
            int removed = db.delete("records", "account_hash=? AND record_id=? AND revision=?",
                    new String[]{hash, recordId, Integer.toString(revision)});
            db.setTransactionSuccessful();
            return removed;
        } finally {
            db.endTransaction();
        }
    }


    public synchronized Cursor records(String account, String deviceId, String kind, long startMs, long endMs, String after) {
        if (!authorizedAccount(account)) throw new SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED");
        if (isDeviceSleep(kind)) return deviceSleep(deviceId, kind, startMs, endMs, after);
        return measurements(hashAccount(account), deviceId, kind, startMs, endMs, after);
    }

    public synchronized Cursor localMeasurements(String deviceId, String kind, long startMs, long endMs, String after) {
        requireConfirmedAccount(getReadableDatabase());
        if (isDeviceSleep(kind)) return deviceSleep(deviceId, kind, startMs, endMs, after);
        return measurements(requireConfirmedAccount(getReadableDatabase()), deviceId, kind, startMs, endMs, after);
    }
    public synchronized List<Measurement> storedSteps(String deviceId, long dayStart, long dayEnd) throws JSONException {
        String account = requireConfirmedAccount(getReadableDatabase());
        ArrayList<Measurement> found = new ArrayList<>();
        for (String kind : new String[] {"steps_day", "steps_interval"}) {
            String after = "";
            while (found.size() <= 4_000) {
                int count = 0;
                try (Cursor rows = measurements(account, deviceId, kind, dayStart, dayEnd, after)) {
                    while (rows.moveToNext()) {
                        count++;
                        after = rows.getString(0);
                        found.add(Measurement.fromJson(new JSONObject(rows.getString(2))));
                    }
                }
                if (count < PAGE_SIZE) break;
            }
        }
        return found;
    }


    /** Sleep stays on the device and is not imported, so a later account confirmation can still read it. */
    private static boolean isDeviceSleep(String kind) {
        return "sleep_interval".equals(kind) || "sleep_stage".equals(kind);
    }

    private Cursor deviceSleep(String deviceId, String kind, long startMs, long endMs, String after) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 128 || !isDeviceSleep(kind)
                || startMs < 0 || endMs <= startMs || after != null && after.length() > 256) {
            throw new IllegalArgumentException("INVALID_HEALTH_WINDOW");
        }
        return getReadableDatabase().rawQuery("SELECT record_id AS recordId, revision, payload AS record "
                + "FROM measurements WHERE device_id=? AND kind=? AND start_ms<? AND end_ms>? AND record_id>? "
                + "ORDER BY record_id LIMIT " + PAGE_SIZE,
                new String[]{deviceId, kind, Long.toString(endMs), Long.toString(startMs), after == null ? "" : after});
    }

    private Cursor measurements(String account, String deviceId, String kind, long startMs, long endMs, String after) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 128 || kind == null || kind.isBlank()
                || startMs < 0 || endMs <= startMs || after != null && after.length() > 256) {
            throw new IllegalArgumentException("INVALID_HEALTH_WINDOW");
        }
        return getReadableDatabase().rawQuery("SELECT record_id AS recordId, revision, payload AS record "
                + "FROM measurements WHERE account_hash=? AND device_id=? AND kind=? AND start_ms<? AND end_ms>? "
                + "AND record_id>? AND account_hash=(SELECT account_hash FROM binding WHERE id=1 AND paused=0) "
                + "ORDER BY record_id LIMIT " + PAGE_SIZE,
                new String[]{account, deviceId, kind, Long.toString(endMs), Long.toString(startMs), after == null ? "" : after});
    }

    public synchronized boolean hasCapacity() {
        try (Cursor totals = getReadableDatabase().rawQuery("SELECT COUNT(*), COALESCE(SUM(byte_size), 0) FROM records", null)) {
            totals.moveToFirst();
            return totals.getInt(0) < MAX_RECORDS && totals.getLong(1) < MAX_BYTES;
        }
    }

    public synchronized int count() {
        String account = confirmedAccountHash();
        if (account == null) return 0;
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM records WHERE account_hash=?",
                new String[]{account})) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    static void requireHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("INVALID_HISTORY_FILE_HASH");
    }

    /** RawFitnessFileStore calls this only after the raw bytes have been durably committed. */
    synchronized void indexFile(String hash, String deviceId, String firmware, long capturedAtMs,
            String parseStatus, int recordCount) {
        requireHash(hash);
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 128 || capturedAtMs < 0
                || parseStatus == null || !parseStatus.matches("[A-Z0-9_]{1,128}") || recordCount < 0
                || !"PARSED".equals(parseStatus) && recordCount != 0) throw new IllegalArgumentException("INVALID_HISTORY_INDEX");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor old = db.rawQuery("SELECT device_id FROM files WHERE file_hash=?", new String[]{hash})) {
                if (old.moveToFirst()) {
                    if (!deviceId.equals(old.getString(0))) throw new SecurityException("HEALTH_ARCHIVE_IDENTITY_CONFLICT");
                    db.setTransactionSuccessful();
                    return; // Never transfer existing files to a new account or reset a replay cursor.
                }
            }
            ContentValues row = new ContentValues(8);
            row.put("file_hash", hash);
            row.put("device_id", deviceId);
            row.put("firmware", firmware);
            row.put("captured_at_ms", capturedAtMs);
            // Unsent files are claimed by the next real account. Do not wait out a paused binding.
            try (Cursor binding = db.rawQuery("SELECT account_hash FROM binding WHERE id=1", null)) {
                row.put("account_hash", binding.moveToFirst() ? binding.getString(0) : null);
            }
            row.put("next_record_index", 0);
            row.put("parse_status", parseStatus);
            row.put("record_count", recordCount);
            db.insertOrThrow("files", null, row);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** A rejected file gained a parser. Does not rewind a cursor that already moved. */
    synchronized boolean promoteRejectedFile(String hash, int recordCount) {
        requireHash(hash);
        if (recordCount < 1) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor row = db.rawQuery("SELECT parse_status, record_count, next_record_index FROM files WHERE file_hash=?",
                    new String[]{hash})) {
                if (!row.moveToFirst() || "PARSED".equals(row.getString(0))
                        || row.getInt(1) != 0 || row.getInt(2) != 0) {
                    db.setTransactionSuccessful();
                    return false;
                }
            }
            ContentValues values = new ContentValues(2);
            values.put("parse_status", "PARSED");
            values.put("record_count", recordCount);
            int updated = db.update("files", values,
                    "file_hash=? AND parse_status!='PARSED' AND record_count=0 AND next_record_index=0",
                    new String[]{hash});
            db.setTransactionSuccessful();
            return updated == 1;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Write this file's stages for {@code owned} intervals, then drop any other stages there.
     * Caller passes only intervals the newest analysis still owns.
     */
    public synchronized int retainSleepStages(String deviceId, List<Measurement> parsed,
            List<SleepStageAlign.Interval> owned) {
        if (deviceId == null || deviceId.isBlank() || parsed == null || owned == null || owned.isEmpty()) return 0;
        List<Measurement> stages = new ArrayList<>();
        for (Measurement measurement : parsed) {
            if (measurement != null && deviceId.equals(measurement.deviceId) && "sleep_stage".equals(measurement.kind)) {
                stages.add(measurement);
            }
        }
        if (stages.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        String account = requireConfirmedAccount(db);
        int changed = 0;
        db.beginTransaction();
        try {
            for (Measurement stage : stages) {
                if (!SleepStageAlign.overlapsAny(stage.startMs, stage.endMs, owned)) continue;
                if (enqueue(db, account, stage).added()) changed++;
            }
            for (SleepStageAlign.Interval interval : owned) {
                List<String> keep = new ArrayList<>();
                for (Measurement stage : stages) {
                    if (stage.startMs < interval.endMs() && stage.endMs > interval.startMs()) keep.add(stage.recordId);
                }
                if (keep.isEmpty()) continue;
                List<String> drop = new ArrayList<>();
                try (Cursor rows = db.rawQuery("SELECT record_id FROM measurements WHERE account_hash=? "
                        + "AND device_id=? AND kind='sleep_stage' AND start_ms<? AND end_ms>?",
                        new String[]{account, deviceId, Long.toString(interval.endMs()),
                                Long.toString(interval.startMs())})) {
                    while (rows.moveToNext()) {
                        String id = rows.getString(0);
                        if (!keep.contains(id)) drop.add(id);
                    }
                }
                for (String id : drop) {
                    String[] key = {account, id};
                    db.delete("measurements", "account_hash=? AND record_id=?", key);
                    db.delete("revisions", "account_hash=? AND record_id=?", key);
                    changed++;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return changed;
    }

    public record StressGap(String deviceId, long startMs, long endMs) {}

    /** Continuous stress 0 is an empty minute, not a reading. Ranges are [start, end). */
    public synchronized List<StressGap> unmeasuredStress(String account) {
        if (!authorizedAccount(account)) throw new SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED");
        String hash = hashAccount(account);
        if (!hash.equals(requireConfirmedAccount(getReadableDatabase()))) {
            throw new SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED");
        }
        List<StressGap> gaps = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT device_id, start_ms, end_ms FROM measurements "
                + "WHERE account_hash=? AND kind='stress' AND json_extract(payload,'$.measurementMode')='continuous' "
                + "AND json_extract(payload,'$.value')=0 ORDER BY device_id, start_ms", new String[]{hash})) {
            String device = null;
            long runStart = -1;
            long runEnd = -1;
            while (rows.moveToNext()) {
                String nextDevice = rows.getString(0);
                long start = rows.getLong(1);
                long end = rows.getLong(2);
                if (device != null && device.equals(nextDevice) && runEnd == start) {
                    runEnd = end;
                    continue;
                }
                if (device != null) gaps.add(new StressGap(device, runStart, runEnd));
                device = nextDevice;
                runStart = start;
                runEnd = end;
            }
            if (device != null) gaps.add(new StressGap(device, runStart, runEnd));
        }
        return gaps;
    }

    public synchronized int forgetUnmeasuredStress(String account) {
        if (!authorizedAccount(account)) throw new SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED");
        SQLiteDatabase db = getWritableDatabase();
        String hash = hashAccount(account);
        if (!hash.equals(requireConfirmedAccount(db))) throw new SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED");
        db.beginTransaction();
        try {
            List<String> ids = new ArrayList<>();
            try (Cursor rows = db.rawQuery("SELECT record_id FROM measurements WHERE account_hash=? AND kind='stress' "
                    + "AND json_extract(payload,'$.measurementMode')='continuous' AND json_extract(payload,'$.value')=0",
                    new String[]{hash})) {
                while (rows.moveToNext()) ids.add(rows.getString(0));
            }
            for (String id : ids) {
                String[] key = {hash, id};
                db.delete("records", "account_hash=? AND record_id=?", key);
                db.delete("revisions", "account_hash=? AND record_id=?", key);
                db.delete("measurements", "account_hash=? AND record_id=?", key);
            }
            db.setTransactionSuccessful();
            return ids.size();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<ArchivedFile> completedFiles() {
        List<ArchivedFile> files = new ArrayList<>();
        String account = confirmedAccountHash();
        if (account == null) return files;
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT file_hash, device_id, firmware, captured_at_ms, "
                + "account_hash, next_record_index, parse_status, record_count FROM files "
                + "WHERE account_hash=? AND parse_status='PARSED' AND next_record_index>=record_count "
                + "ORDER BY captured_at_ms, file_hash", new String[]{account})) {
            while (rows.moveToNext()) files.add(new ArchivedFile(rows.getString(0), rows.getString(1), rows.getString(2),
                    rows.getLong(3), rows.getString(4), rows.getInt(5), rows.getString(6), rows.getInt(7)));
        }
        return files;
    }

    public synchronized List<ArchivedFile> replayFiles(int limit) {
        if (limit < 1 || limit > PAGE_SIZE) throw new IllegalArgumentException("INVALID_HISTORY_PAGE_SIZE");
        List<ArchivedFile> files = new ArrayList<>(limit);
        String account = confirmedAccountHash();
        if (account == null) return files;
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT file_hash, device_id, firmware, captured_at_ms, "
                + "account_hash, next_record_index, parse_status, record_count FROM files "
                + "WHERE account_hash=? AND parse_status='PARSED' AND next_record_index<record_count "
                + "ORDER BY captured_at_ms, file_hash LIMIT ?", new String[]{account, Integer.toString(limit)})) {
            while (rows.moveToNext()) files.add(new ArchivedFile(rows.getString(0), rows.getString(1), rows.getString(2),
                    rows.getLong(3), rows.getString(4), rows.getInt(5), rows.getString(6), rows.getInt(7)));
        }
        return files;
    }

    synchronized boolean isFileIndexed(String hash) {
        requireHash(hash);
        try (Cursor row = getReadableDatabase().rawQuery("SELECT 1 FROM files WHERE file_hash=?", new String[]{hash})) {
            return row.moveToFirst();
        }
    }

    public synchronized boolean ownsDeviceArchive(String deviceId) {
        String account = confirmedAccountHash();
        if (account == null) return false;
        try (Cursor row = getReadableDatabase().rawQuery("SELECT 1 FROM files WHERE device_id=? AND account_hash=? LIMIT 1",
                new String[]{deviceId, account})) {
            return row.moveToFirst();
        }
    }
}
