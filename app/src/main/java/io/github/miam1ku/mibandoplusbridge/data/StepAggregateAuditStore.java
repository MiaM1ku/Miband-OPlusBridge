// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Private CE review state. Callers publish state changes after a confirmed write. */
public final class StepAggregateAuditStore {
    private static final Object LOCK = new Object();
    private static final String NAME = "step-aggregate-audits.json";
    private static final int MAX_ENTRIES = 93;
    private static final int MAX_BYTES = 512 * 1024;
    private static final Set<String> ENTRY_FIELDS = Set.of("request", "review", "approvedFingerprint");
    private final File file;

    public StepAggregateAuditStore(Context context) {
        if (context == null || context.isDeviceProtectedStorage()) {
            throw new IllegalArgumentException("CE_STORAGE_REQUIRED");
        }
        Context app = context.getApplicationContext();
        if (app != null) context = app;
        if (context.isDeviceProtectedStorage()) throw new IllegalArgumentException("CE_STORAGE_REQUIRED");
        file = new File(context.getNoBackupFilesDir(), NAME);
    }

    /** File-backed tests exercise the same strict parser and atomic LocalPrefs writer. */
    StepAggregateAuditStore(File file) { this.file = java.util.Objects.requireNonNull(file); }

    public record Request(String account, String queuedDevice, String nativeDevice, int date,
            long startMs, String timezone, long daySteps, int minuteMode) {
        private static final Set<String> FIELDS = Set.of("account", "queuedDevice", "nativeDevice", "date",
                "startMs", "timezone", "daySteps", "minuteMode");

        public Request {
            if (!identity(account) || !identity(queuedDevice) || !identity(nativeDevice)
                    || timezone == null || timezone.isBlank() || timezone.length() > 128
                    || date < 19700101 || date > 99991231 || daySteps < 1 || daySteps > Integer.MAX_VALUE
                    || minuteMode != 1 && minuteMode != -1) throw invalidRequest();
            try {
                LocalDate day = LocalDate.of(date / 10_000, date / 100 % 100, date % 100);
                ZoneId zone = ZoneId.of(timezone);
                var start = day.atStartOfDay(zone);
                var end = day.plusDays(1).atStartOfDay(zone);
                if (!start.toLocalDate().equals(day) || !start.toLocalTime().equals(LocalTime.MIDNIGHT)
                        || !end.toLocalTime().equals(LocalTime.MIDNIGHT)
                        || start.toInstant().toEpochMilli() != startMs
                        || Math.subtractExact(end.toInstant().toEpochMilli(), startMs) != 86_400_000L) {
                    throw invalidRequest();
                }
            } catch (java.time.DateTimeException | ArithmeticException invalid) {
                throw invalidRequest();
            }
        }

        public String accountHash() {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(account.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("STEP_AUDIT_HASH_UNAVAILABLE");
            }
        }

        public String key() { return accountHash() + ":" + queuedDevice + ":" + date; }

        public JSONObject toJson() {
            try {
                return new JSONObject().put("account", account).put("queuedDevice", queuedDevice)
                        .put("nativeDevice", nativeDevice).put("date", date).put("startMs", startMs)
                        .put("timezone", timezone).put("daySteps", daySteps).put("minuteMode", minuteMode);
            } catch (JSONException failure) {
                throw new IllegalStateException("STEP_AUDIT_SERIALIZATION_FAILED");
            }
        }

        public static Request fromJson(JSONObject json) {
            try {
                requireFields(json, FIELDS);
                long date = integer(json, "date");
                long mode = integer(json, "minuteMode");
                if (date < Integer.MIN_VALUE || date > Integer.MAX_VALUE
                        || mode < Integer.MIN_VALUE || mode > Integer.MAX_VALUE) throw invalidRequest();
                return new Request(string(json, "account"), string(json, "queuedDevice"),
                        string(json, "nativeDevice"), (int) date, integer(json, "startMs"),
                        string(json, "timezone"), integer(json, "daySteps"), (int) mode);
            } catch (JSONException failure) {
                throw invalidRequest();
            }
        }

        @Override public String toString() {
            return "StepAggregateRequest[accountHash=" + accountHash() + ", date=" + date + "]";
        }
    }

    public record Entry(Request request, StepAggregateReview review, boolean approved) { }
    public record ReportResult(boolean approved, boolean changed) { }
    private record Stored(Request request, StepAggregateReview review, String approvedFingerprint) {
        Entry entry() { return new Entry(request, review, approvedFingerprint != null); }
    }

    public synchronized boolean schedule(List<Request> requests) {
        if (requests == null || requests.size() > 4_096) throw invalidRequest();
        List<Request> incoming;
        try { incoming = List.copyOf(requests); }
        catch (NullPointerException invalid) { throw invalidRequest(); }
        synchronized (LOCK) {
            Map<String, Stored> current = read();
            Map<String, Stored> next = new HashMap<>(current);
            Map<String, Request> seen = new HashMap<>();
            for (Request request : incoming) {
                Request duplicate = seen.put(request.key(), request);
                if (duplicate != null && !duplicate.equals(request)) {
                    throw new IllegalArgumentException("STEP_AUDIT_REQUEST_CONFLICT");
                }
                Stored old = next.get(request.key());
                if (old == null || !old.request.equals(request)) {
                    next.put(request.key(), new Stored(request, null, null));
                }
            }
            List<Stored> newest = new ArrayList<>(next.values());
            newest.sort(order());
            if (newest.size() > MAX_ENTRIES) {
                for (int i = MAX_ENTRIES; i < newest.size(); i++) next.remove(newest.get(i).request.key());
            }
            if (next.equals(current)) return false;
            commit(next);
            return true;
        }
    }

    public List<Entry> entries(String accountHash, String queuedDevice) {
        requireScope(accountHash, queuedDevice);
        synchronized (LOCK) {
            List<Stored> selected = new ArrayList<>();
            for (Stored item : read().values()) {
                if (item.request.accountHash().equals(accountHash) && item.request.queuedDevice.equals(queuedDevice)) {
                    selected.add(item);
                }
            }
            selected.sort(order());
            List<Entry> result = new ArrayList<>();
            for (Stored item : selected) result.add(item.entry());
            return List.copyOf(result);
        }
    }

    public List<StepAggregateReview> pending(String accountHash, String queuedDevice) {
        List<StepAggregateReview> result = new ArrayList<>();
        for (Entry item : entries(accountHash, queuedDevice)) if (item.review != null) result.add(item.review);
        return List.copyOf(result);
    }

    public ReportResult report(Request request, StepAggregateReview review) {
        if (request == null) throw invalidRequest();
        synchronized (LOCK) {
            Map<String, Stored> state = read();
            Stored old = state.get(request.key());
            if (old == null || !old.request.equals(request)) return new ReportResult(false, false);
            if (review != null && !matches(request, review)) {
                throw new IllegalArgumentException("STEP_AUDIT_REVIEW_MISMATCH");
            }
            String approval = review != null && review.fingerprint().equals(old.approvedFingerprint)
                    ? old.approvedFingerprint : null;
            Stored next = new Stored(request, review, approval);
            if (next.equals(old)) return new ReportResult(approval != null, false);
            state.put(request.key(), next);
            commit(state);
            return new ReportResult(approval != null, true);
        }
    }

    public void approve(String accountHash, String queuedDevice, String fingerprint) {
        requireScope(accountHash, queuedDevice);
        if (!hash(fingerprint)) throw new IllegalArgumentException("STEP_AUDIT_CHANGED");
        synchronized (LOCK) {
            Map<String, Stored> state = read();
            Stored selected = null;
            for (Stored item : state.values()) {
                if (item.review != null && item.request.accountHash().equals(accountHash)
                        && item.request.queuedDevice.equals(queuedDevice)
                        && item.review.fingerprint().equals(fingerprint)) {
                    if (selected != null) throw new IllegalStateException("STEP_AUDIT_CHANGED");
                    selected = item;
                }
            }
            if (selected == null) throw new IllegalStateException("STEP_AUDIT_CHANGED");
            state.put(selected.request.key(), new Stored(selected.request, selected.review, fingerprint));
            commit(state);
        }
    }

    public boolean complete(Request request, StepAggregateReview review) {
        synchronized (LOCK) {
            Map<String, Stored> state = read();
            if (request == null || review == null) return false;
            Stored old = state.get(request.key());
            if (old == null || !old.request.equals(request) || old.review == null
                    || !matches(request, review) || !old.review.fingerprint().equals(review.fingerprint())
                    || !review.fingerprint().equals(old.approvedFingerprint)) return false;
            state.put(request.key(), new Stored(request, null, null));
            commit(state);
            return true;
        }
    }

    private Map<String, Stored> read() {
        if (!file.exists()) return new HashMap<>();
        try {
            if (!file.isFile() || file.length() < 2 || file.length() > MAX_BYTES) throw corrupt();
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length < 2 || bytes.length > MAX_BYTES) throw corrupt();
            String encoded = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            JSONTokener tokens = new JSONTokener(encoded);
            Object decoded = tokens.nextValue();
            if (!(decoded instanceof JSONObject json) || tokens.nextClean() != 0) throw corrupt();
            if (json.length() > MAX_ENTRIES) throw corrupt();
            Map<String, Stored> result = new HashMap<>();
            for (Iterator<String> keys = json.keys(); keys.hasNext();) {
                String key = keys.next();
                Object value = json.get(key);
                if (!(value instanceof JSONObject entry)) throw corrupt();
                requireFields(entry, ENTRY_FIELDS);
                Object rawRequest = entry.get("request");
                if (!(rawRequest instanceof JSONObject)) throw corrupt();
                Request request = Request.fromJson((JSONObject) rawRequest);
                if (!request.key().equals(key)) throw corrupt();
                Object rawReview = entry.get("review");
                StepAggregateReview review = rawReview == JSONObject.NULL ? null
                        : rawReview instanceof JSONObject ? StepAggregateReview.fromJson((JSONObject) rawReview) : invalidReview();
                if (review != null && !matches(request, review)) throw corrupt();
                Object rawApproval = entry.get("approvedFingerprint");
                String approval = rawApproval == JSONObject.NULL ? null : rawApproval instanceof String ? (String) rawApproval : invalidApproval();
                if (approval != null && (review == null || !hash(approval) || !approval.equals(review.fingerprint()))) throw corrupt();
                result.put(key, new Stored(request, review, approval));
            }
            return result;
        } catch (Exception failure) {
            throw corrupt();
        }
    }

    private void commit(Map<String, Stored> entries) {
        JSONObject json = new JSONObject();
        try {
            for (Stored item : entries.values()) {
                json.put(item.request.key(), new JSONObject().put("request", item.request.toJson())
                        .put("review", item.review == null ? JSONObject.NULL : item.review.toJson())
                        .put("approvedFingerprint", item.approvedFingerprint == null ? JSONObject.NULL : item.approvedFingerprint));
            }
        } catch (JSONException failure) {
            throw new IllegalStateException("STEP_AUDIT_SERIALIZATION_FAILED");
        }
        if (entries.size() > MAX_ENTRIES || json.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalStateException("STEP_AUDIT_LIMIT_REACHED");
        }
        if (!LocalPrefs.writeJson(file, json)) throw new IllegalStateException("STEP_AUDIT_STORAGE_FAILED");
    }

    private static Comparator<Stored> order() {
        return Comparator.comparingInt((Stored item) -> item.request.date).reversed()
                .thenComparing(item -> item.request.key());
    }
    private static boolean matches(Request request, StepAggregateReview review) {
        return request.accountHash().equals(review.accountHash()) && request.queuedDevice.equals(review.queuedDevice())
                && request.nativeDevice.equals(review.nativeDevice()) && request.date == review.date()
                && request.daySteps == review.expectedSteps();
    }
    private static void requireScope(String accountHash, String queuedDevice) {
        if (!hash(accountHash) || !identity(queuedDevice)) throw new IllegalArgumentException("STEP_AUDIT_SCOPE_INVALID");
    }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean identity(String value) {
        if (value == null || value.isBlank() || value.length() > 512 || !value.equals(value.strip())) return false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isISOControl(character)) return false;
            if (Character.isHighSurrogate(character)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(character)) return false;
        }
        return true;
    }
    private static void requireFields(JSONObject json, Set<String> fields) {
        if (json == null || json.length() != fields.size()) throw invalidRequest();
        for (Iterator<String> keys = json.keys(); keys.hasNext();) if (!fields.contains(keys.next())) throw invalidRequest();
    }
    private static String string(JSONObject json, String name) throws JSONException {
        Object value = json.get(name);
        if (!(value instanceof String)) throw invalidRequest();
        return (String) value;
    }
    private static long integer(JSONObject json, String name) throws JSONException {
        Object value = json.get(name);
        if (!(value instanceof Integer || value instanceof Long)) throw invalidRequest();
        return ((Number) value).longValue();
    }
    private static StepAggregateReview invalidReview() { throw corrupt(); }
    private static String invalidApproval() { throw corrupt(); }
    private static IllegalArgumentException invalidRequest() { return new IllegalArgumentException("STEP_AUDIT_REQUEST_INVALID"); }
    private static IllegalStateException corrupt() { return new IllegalStateException("STEP_AUDIT_CORRUPT"); }
}
