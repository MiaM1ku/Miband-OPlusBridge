// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Complete historical evidence for a user-confirmed aggregate review. Authorization belongs to the caller. */
public record StepAggregateReview(String fingerprint, String accountHash, String queuedDevice,
        String nativeDevice, int date, int expectedSteps, int combinedSteps, int phoneSteps) {
    private static final long MINUTE_MS = 60_000L;
    private static final long DAY_MS = 86_400_000L;
    private static final int MINUTES = 1_440;
    private static final Set<String> FIELDS = Set.of("fingerprint", "accountHash", "queuedDevice",
            "nativeDevice", "date", "expectedSteps", "combinedSteps", "phoneSteps");

    /** Source calories are whole kilocalories; every minute, including a zero minute, is evidence. */
    public record SourceMinute(String device, long startMs, long endMs, long steps, long calories,
            long distance, String timezone) { }
    /** Saved calories use the host's millicalorie scale. */
    public record SavedMinute(String account, String device, long startMs, long endMs, long steps,
            long calories, long distance, int mode, int display, String timezone) { }
    /** Daily calories use the host's millicalorie scale. */
    public record Daily(String account, String device, int date, int mode, long steps,
            long calories, long distance) { }

    public StepAggregateReview {
        if (!hash(fingerprint) || !hash(accountHash) || !identity(queuedDevice) || !identity(nativeDevice)
                || expectedSteps <= 0 || combinedSteps < 0 || phoneSteps < 0
                || combinedSteps <= expectedSteps && phoneSteps <= expectedSteps) {
            throw invalid();
        }
        localDate(date);
    }

    /** Returns a report only after complete source, saved-minute and device-day agreement. */
    public static StepAggregateReview assess(String account, String queuedDevice, String nativeDevice,
            int date, long dayStart, String timezone, long daySteps, int minuteMode, long nowMs,
            List<SourceMinute> sources, List<SavedMinute> saved, List<Daily> days) {
        try {
            if (!identity(account) || !identity(queuedDevice) || !identity(nativeDevice)
                    || !positiveInt(daySteps) || (minuteMode != 1 && minuteMode != -1) || dayStart < 0
                    || sources == null || sources.size() != MINUTES
                    || saved == null || saved.size() > MINUTES || days == null
                    || days.size() < 3 || days.size() > 4) return null;
            LocalDate day = localDate(date);
            ZoneId zone = ZoneId.of(timezone);
            long end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            if (!day.atStartOfDay(zone).toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                    || day.atStartOfDay(zone).toInstant().toEpochMilli() != dayStart
                    || Math.subtractExact(end, dayStart) != DAY_MS || nowMs < end) return null;
            List<SourceMinute> sourceCopy = List.copyOf(sources);
            List<SavedMinute> savedCopy = List.copyOf(saved);
            List<Daily> dayCopy = List.copyOf(days);
            SourceMinute[] sourceByMinute = new SourceMinute[MINUTES];
            long steps = 0, calories = 0, distance = 0;
            for (SourceMinute source : sourceCopy) {
                int index = minuteIndex(source.startMs, source.endMs, dayStart, end);
                if (index < 0 || sourceByMinute[index] != null || !queuedDevice.equals(source.device)
                        || !sameOffset(source.timezone, zone, source.startMs)
                        || source.steps < 0 || source.steps > 350
                        || source.calories < 0 || source.calories > 65
                        || source.distance < 0 || source.distance > 1_050) return null;
                sourceByMinute[index] = source;
                steps = Math.addExact(steps, source.steps);
                calories = Math.addExact(calories, Math.multiplyExact(source.calories, 1_000L));
                distance = Math.addExact(distance, source.distance);
            }
            if (steps != daySteps || !positiveInt(steps) || !nonnegativeInt(calories)
                    || !nonnegativeInt(distance)) return null;
            SavedMinute[] savedByMinute = new SavedMinute[MINUTES];
            for (SavedMinute row : savedCopy) {
                int index = minuteIndex(row.startMs, row.endMs, dayStart, end);
                if (index < 0 || savedByMinute[index] != null || !account.equals(row.account)
                        || !nativeDevice.equals(row.device) || row.mode != minuteMode || row.display != 1
                        || !sameOffset(row.timezone, zone, row.startMs)) return null;
                SourceMinute source = sourceByMinute[index];
                if (row.steps != source.steps || row.calories != source.calories * 1_000L
                        || row.distance != source.distance) return null;
                savedByMinute[index] = row;
            }
            for (int i = 0; i < MINUTES; i++) {
                SourceMinute source = sourceByMinute[i];
                if (source == null || savedByMinute[i] == null
                        && (source.steps != 0 || source.calories != 0 || source.distance != 0)) return null;
            }
            Daily deviceDay = null, deviceMinutes = null, combined = null, phone = null;
            for (Daily row : dayCopy) {
                if (!account.equals(row.account) || row.date != date || !nonnegativeInt(row.steps)
                        || !nonnegativeInt(row.calories) || !nonnegativeInt(row.distance)) return null;
                if (row.device == null || row.device.isEmpty()) {
                    if (row.mode == -2 && combined == null) combined = row;
                    else if (row.mode == -4 && phone == null) phone = row;
                    else return null;
                } else {
                    if (!nativeDevice.equals(row.device) || row.steps != steps || row.calories != calories
                            || row.distance != distance) return null;
                    if (row.mode == -3 && deviceDay == null) deviceDay = row;
                    else if (row.mode == minuteMode && deviceMinutes == null) deviceMinutes = row;
                    else return null;
                }
            }
            if (deviceDay == null || combined == null || phone == null
                    || combined.steps <= steps && phone.steps <= steps) return null;
            String accountHash = digest(account.getBytes(StandardCharsets.UTF_8));
            String fingerprint = fingerprint(accountHash, queuedDevice, nativeDevice, date, dayStart,
                    timezone, minuteMode, sourceByMinute, savedByMinute, dayCopy);
            return new StepAggregateReview(fingerprint, accountHash, queuedDevice, nativeDevice, date,
                    (int) steps, (int) combined.steps, (int) phone.steps);
        } catch (RuntimeException invalidEvidence) {
            return null;
        }
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("fingerprint", fingerprint).put("accountHash", accountHash)
                    .put("queuedDevice", queuedDevice).put("nativeDevice", nativeDevice).put("date", date)
                    .put("expectedSteps", expectedSteps).put("combinedSteps", combinedSteps).put("phoneSteps", phoneSteps);
        } catch (JSONException failure) {
            throw new IllegalStateException("STEP_REVIEW_SERIALIZATION_FAILED", failure);
        }
    }

    public static StepAggregateReview fromJson(JSONObject json) throws JSONException {
        if (json == null || json.length() != FIELDS.size()) throw invalid();
        for (Iterator<String> keys = json.keys(); keys.hasNext();) {
            if (!FIELDS.contains(keys.next())) throw invalid();
        }
        return new StepAggregateReview(string(json, "fingerprint"), string(json, "accountHash"),
                string(json, "queuedDevice"), string(json, "nativeDevice"), integer(json, "date"),
                integer(json, "expectedSteps"), integer(json, "combinedSteps"), integer(json, "phoneSteps"));
    }

    private static int minuteIndex(long start, long end, long dayStart, long dayEnd) {
        if (start < dayStart || start >= dayEnd || end != Math.addExact(start, MINUTE_MS)
                || end > dayEnd || (start - dayStart) % MINUTE_MS != 0) return -1;
        return (int) ((start - dayStart) / MINUTE_MS);
    }

    private static boolean sameOffset(String timezone, ZoneId zone, long at) {
        Instant instant = Instant.ofEpochMilli(at);
        return ZoneId.of(timezone).getRules().getOffset(instant).equals(zone.getRules().getOffset(instant));
    }

    private static LocalDate localDate(int date) {
        if (date < 19700101 || date > 99991231) throw invalid();
        try { return LocalDate.of(date / 10_000, date / 100 % 100, date % 100); }
        catch (java.time.DateTimeException malformed) { throw invalid(); }
    }
    private static boolean identity(String value) { return value != null && !value.isBlank() && value.length() <= 512; }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean nonnegativeInt(long value) { return value >= 0 && value <= Integer.MAX_VALUE; }
    private static boolean positiveInt(long value) { return value > 0 && value <= Integer.MAX_VALUE; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("STEP_REVIEW_INVALID"); }

    private static String string(JSONObject json, String name) throws JSONException {
        Object value = json.get(name);
        if (!(value instanceof String)) throw invalid();
        return (String) value;
    }
    private static int integer(JSONObject json, String name) throws JSONException {
        Object value = json.get(name);
        if (!(value instanceof Integer || value instanceof Long) || !nonnegativeInt(((Number) value).longValue())) {
            throw invalid();
        }
        return ((Number) value).intValue();
    }

    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static void text(DataOutputStream output, String value) throws java.io.IOException {
        if (value == null) { output.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
    private static String fingerprint(String accountHash, String queued, String nativeDevice, int date,
            long dayStart, String timezone, int minuteMode, SourceMinute[] sources, SavedMinute[] saved,
            List<Daily> days) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            text(out, "step-aggregate-review-v1");
            text(out, accountHash); text(out, queued); text(out, nativeDevice);
            out.writeInt(date); out.writeLong(dayStart); text(out, timezone); out.writeInt(minuteMode);
            for (SourceMinute row : sources) {
                text(out, row.device); out.writeLong(row.startMs); out.writeLong(row.endMs);
                out.writeLong(row.steps); out.writeLong(row.calories); out.writeLong(row.distance); text(out, row.timezone);
            }
            for (int i = 0; i < saved.length; i++) {
                SavedMinute row = saved[i];
                out.writeBoolean(row != null);
                if (row == null) continue;
                text(out, accountHash); text(out, row.device); out.writeLong(row.startMs); out.writeLong(row.endMs);
                out.writeLong(row.steps); out.writeLong(row.calories); out.writeLong(row.distance);
                out.writeInt(row.mode); out.writeInt(row.display); text(out, row.timezone);
            }
            List<Daily> ordered = new ArrayList<>(days);
            ordered.sort(Comparator.comparingInt(Daily::mode));
            out.writeInt(ordered.size());
            for (Daily row : ordered) {
                text(out, accountHash); text(out, row.device); out.writeInt(row.date); out.writeInt(row.mode);
                out.writeLong(row.steps); out.writeLong(row.calories); out.writeLong(row.distance);
            }
            out.flush();
            return digest(bytes.toByteArray());
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("STEP_REVIEW_FINGERPRINT_FAILED", impossible);
        }
    }
}
