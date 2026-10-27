// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** A normalized measurement. Diagnostic logs carry the kind and the time, and leave source ids and values in the record. */
public final class HealthRecord {
    private static final Set<String> FIELDS = Set.of("recordId", "deviceId", "kind", "startMs",
            "endMs", "value", "stage", "revision", "timezone", "measurementMode", "complete",
            "calories", "distance", "moveAbout", "reps", "heart", "duration", "bestPace", "track");
    private static final Set<Integer> SPORT_MODES = Set.of(1, 2, 3, 7, 10, 19, 35, 36, 37, 506, 809, 908);

    public final String recordId;
    public final String deviceId;
    public final String kind;
    public final long startMs;
    public final long endMs;
    public final Number value;
    public final Integer stage;
    public final int revision;
    public final String timezone;
    public final String measurementMode;
    public final boolean complete;
    /** Activity kilocalories. Null means this record has no calorie field. */
    public final Integer calories;
    /** Meters. Null means this record has no distance field. */
    public final Integer distance;
    /** Stand-hours in the daily report. Null except on a parsed steps_day. */
    public final Integer moveAbout;
    /** Steps, or jumps for rope skipping. Null except on a sport summary. */
    public final Integer reps;
    /** Average heart rate from a sport summary. */
    public final Integer heart;
    /** Moving time in seconds. Null when the summary did not carry one. */
    public final Integer duration;
    /** Fastest pace in seconds per kilometre. Null except on a sport summary. */
    public final Integer bestPace;
    /** OPPO band track payload. Null except on a GPS file. */
    public final String track;

    public HealthRecord(String recordId, String deviceId, String kind, long startMs, long endMs,
            Number value, Integer stage, int revision, String timezone,
            String measurementMode, boolean complete) {
        this(recordId, deviceId, kind, startMs, endMs, value, stage, revision, timezone,
                measurementMode, complete, null, null, null, null, null, null, null, null);
    }

    public HealthRecord(String recordId, String deviceId, String kind, long startMs, long endMs,
            Number value, Integer stage, int revision, String timezone,
            String measurementMode, boolean complete, Integer calories, Integer distance,
            Integer moveAbout) {
        this(recordId, deviceId, kind, startMs, endMs, value, stage, revision, timezone,
                measurementMode, complete, calories, distance, moveAbout, null, null, null, null, null);
    }

    public HealthRecord(String recordId, String deviceId, String kind, long startMs, long endMs,
            Number value, Integer stage, int revision, String timezone,
            String measurementMode, boolean complete, Integer calories, Integer distance,
            Integer moveAbout, Integer reps, Integer heart) {
        this(recordId, deviceId, kind, startMs, endMs, value, stage, revision, timezone,
                measurementMode, complete, calories, distance, moveAbout, reps, heart, null, null, null);
    }

    public HealthRecord(String recordId, String deviceId, String kind, long startMs, long endMs,
            Number value, Integer stage, int revision, String timezone,
            String measurementMode, boolean complete, Integer calories, Integer distance,
            Integer moveAbout, Integer reps, Integer heart, Integer duration, Integer bestPace,
            String track) {
        if (recordId == null || recordId.isBlank() || deviceId == null || deviceId.isBlank()
                || startMs < 0 || endMs <= startMs || revision <= 0) {
            throw new IllegalArgumentException("INVALID_HEALTH_RECORD");
        }
        boolean sleepStage = "sleep_stage".equals(kind);
        boolean sleepInterval = "sleep_interval".equals(kind);
        boolean sleepBreath = "sleep_breath".equals(kind);
        boolean steps = "steps_interval".equals(kind) || "steps_day".equals(kind);
        boolean heartRate = "heart_rate".equals(kind);
        boolean oxygen = "spo2".equals(kind);
        boolean stress = "stress".equals(kind);
        boolean sport = "sport_session".equals(kind);
        boolean trackFile = "sport_track".equals(kind);
        if (!(sleepStage || sleepInterval || sleepBreath || steps || heartRate || oxygen || stress || sport || trackFile)) {
            throw new IllegalArgumentException("UNSUPPORTED_HEALTH_KIND");
        }
        // Resting and walking heart rate are one daily value each. Health only derives them from
        // points typed 1 and 4, so they stay their own modes instead of a continuous minute.
        boolean dailyHeart = heartRate && ("resting".equals(measurementMode) || "walking".equals(measurementMode));
        // Database v2 migrates every old source, including sleep_stage, to continuous.
        if (!("continuous".equals(measurementMode) || "manual".equals(measurementMode)
                || "sleep".equals(measurementMode) || dailyHeart)
                || steps && !"continuous".equals(measurementMode)
                || sport && !"continuous".equals(measurementMode)
                || trackFile && !"continuous".equals(measurementMode)
                || sleepInterval && !"sleep".equals(measurementMode)
                || sleepStage && "manual".equals(measurementMode)
                || sleepBreath && !"sleep".equals(measurementMode)
                || complete && !sleepInterval
                || (calories != null || distance != null || moveAbout != null) && !steps && !sport
                || (reps != null || heart != null || duration != null || bestPace != null) && !sport
                || duration != null && (duration < 1 || duration > 48 * 3600)
                || bestPace != null && (bestPace < 30 || bestPace > 7200)
                || track != null && !trackFile
                || trackFile && (track == null || !track.startsWith("5,") || track.length() > 200_000)
                || calories != null && (calories < 0 || calories > 100_000)
                || distance != null && (distance < 0 || distance > 1_000_000)
                || reps != null && reps > 1_000_000
                || heart != null && (heart < 1 || heart > 250)
                || moveAbout != null && (moveAbout < 0 || moveAbout > 24 || !reportDay(kind))) {
            throw new IllegalArgumentException("INVALID_MEASUREMENT_MODE");
        }
        if (sleepStage ? stage == null || value != null
                : stage != null || !sleepInterval && value == null) {
            throw new IllegalArgumentException("INVALID_HEALTH_MEASUREMENT");
        }
        // 6 is a sleep duration the band did not classify. It is not light sleep.
        if (sleepStage && (stage < 2 || stage > 6)) {
            throw new IllegalArgumentException("INVALID_SLEEP_STAGE");
        }
        if (value != null) {
            double measured = value.doubleValue();
            if (!Double.isFinite(measured) || measured < 0 || measured != Math.rint(measured)
                    || heartRate && (measured < 1 || measured > 250)
                    || sport && (measured > Integer.MAX_VALUE || !SPORT_MODES.contains(value.intValue()))
                    || trackFile && (measured < 2 || measured > 1500)
                    || oxygen && (measured < 1 || measured > 100)
                    || stress && measured > 100 || steps && measured > 0xffff_ffffL
                    || sleepInterval && measured > endMs - startMs
                    || sleepBreath && (measured < 60 || measured > 500)) {
                throw new IllegalArgumentException("INVALID_HEALTH_MEASUREMENT");
            }
        }
        if ((heartRate || oxygen || stress) && endMs - startMs
                != ("continuous".equals(measurementMode) ? 60_000L : 1L)) {
            throw new IllegalArgumentException("INVALID_MEASUREMENT_INTERVAL");
        }
        if ((sport || trackFile) && endMs - startMs > 48L * 3_600_000L) {
            throw new IllegalArgumentException("INVALID_MEASUREMENT_INTERVAL");
        }
        if (sleepBreath && endMs - startMs != 60_000L) {
            throw new IllegalArgumentException("INVALID_MEASUREMENT_INTERVAL");
        }
        if (timezone != null) {
            try {
                ZoneId.of(timezone);
            } catch (DateTimeException e) {
                throw new IllegalArgumentException("INVALID_HEALTH_TIMEZONE", e);
            }
        }
        this.recordId = recordId;
        this.deviceId = deviceId;
        this.kind = kind;
        this.startMs = startMs;
        this.endMs = endMs;
        this.value = value;
        this.stage = stage;
        this.revision = revision;
        this.timezone = timezone;
        this.measurementMode = measurementMode;
        this.complete = complete;
        this.calories = calories;
        this.distance = distance;
        this.moveAbout = moveAbout;
        this.reps = reps;
        this.heart = heart;
        this.duration = duration;
        this.bestPace = bestPace;
        this.track = track;
    }
    /** OHealth 6.9.37 drops these values and still reports insert success. */
    public boolean hostAccepts() {
        if (value == null) return false;
        int measured = value.intValue();
        return switch (kind) {
            case "heart_rate" -> measured >= 40 && measured <= 220;
            case "spo2" -> measured >= 60 && measured <= 100;
            case "stress" -> measured >= 1 && measured <= 100;
            default -> false;
        };
    }


    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("recordId", recordId);
            json.put("deviceId", deviceId);
            json.put("kind", kind);
            json.put("startMs", startMs);
            json.put("endMs", endMs);
            json.put("value", value == null ? JSONObject.NULL : value);
            json.put("stage", stage == null ? JSONObject.NULL : stage);
            json.put("revision", revision);
            json.put("measurementMode", measurementMode);
            json.put("complete", complete);
            if (timezone != null) json.put("timezone", timezone);
            if (calories != null) json.put("calories", calories);
            if (distance != null) json.put("distance", distance);
            if (moveAbout != null) json.put("moveAbout", moveAbout);
            if (reps != null) json.put("reps", reps);
            if (heart != null) json.put("heart", heart);
            if (duration != null) json.put("duration", duration);
            if (bestPace != null) json.put("bestPace", bestPace);
            if (track != null) json.put("track", track);
        } catch (JSONException e) {
            throw new IllegalStateException("HEALTH_RECORD_SERIALIZATION_FAILED", e);
        }
        return json;
    }

    public static HealthRecord fromJson(JSONObject json) throws JSONException {
        if (json == null) throw new IllegalArgumentException("INVALID_HEALTH_RECORD");
        for (Iterator<String> it = json.keys(); it.hasNext();) {
            if (!FIELDS.contains(it.next())) throw new IllegalArgumentException("UNKNOWN_HEALTH_FIELD");
        }
        Object value = json.get("value");
        Object stage = json.get("stage");
        if (!(json.get("measurementMode") instanceof String)
                || !(json.get("complete") instanceof Boolean)) {
            throw new IllegalArgumentException("INVALID_HEALTH_MEASUREMENT");
        }
        if (value != JSONObject.NULL && !(value instanceof Number)
                || stage != JSONObject.NULL && !(stage instanceof Integer)) {
            throw new IllegalArgumentException("INVALID_HEALTH_MEASUREMENT");
        }
        return new HealthRecord(json.getString("recordId"), json.getString("deviceId"),
                json.getString("kind"), json.getLong("startMs"), json.getLong("endMs"),
                value == JSONObject.NULL ? null : (Number) value,
                stage == JSONObject.NULL ? null : (Integer) stage, json.getInt("revision"),
                json.has("timezone") ? json.getString("timezone") : null,
                json.getString("measurementMode"), json.getBoolean("complete"),
                optional(json, "calories"), optional(json, "distance"), optional(json, "moveAbout"),
                optional(json, "reps"), optional(json, "heart"), optional(json, "duration"),
                optional(json, "bestPace"),
                json.has("track") && !json.isNull("track") ? json.getString("track") : null);
    }

    private static boolean reportDay(String kind) { return "steps_day".equals(kind); }

    private static Integer optional(JSONObject json, String name) throws JSONException {
        if (!json.has(name) || json.isNull(name)) return null;
        Object value = json.get(name);
        if (!(value instanceof Number) || value instanceof Double || value instanceof Float) {
            throw new IllegalArgumentException("INVALID_HEALTH_MEASUREMENT");
        }
        long number = ((Number) value).longValue();
        if (number < 0 || number > Integer.MAX_VALUE) throw new IllegalArgumentException("INVALID_HEALTH_MEASUREMENT");
        return (int) number;
    }
}
