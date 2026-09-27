// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** A normalized measurement; source identifiers and values never enter diagnostic logs. */
public final class HealthRecord {
    private static final Set<String> FIELDS = Set.of("recordId", "deviceId", "kind", "startMs",
            "endMs", "value", "stage", "revision", "timezone", "measurementMode", "complete");

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

    public HealthRecord(String recordId, String deviceId, String kind, long startMs, long endMs,
            Number value, Integer stage, int revision, String timezone,
            String measurementMode, boolean complete) {
        if (recordId == null || recordId.isBlank() || deviceId == null || deviceId.isBlank()
                || startMs < 0 || endMs <= startMs || revision <= 0) {
            throw new IllegalArgumentException("INVALID_HEALTH_RECORD");
        }
        boolean sleepStage = "sleep_stage".equals(kind);
        boolean sleepInterval = "sleep_interval".equals(kind);
        boolean steps = "steps_interval".equals(kind) || "steps_day".equals(kind);
        boolean heart = "heart_rate".equals(kind);
        boolean oxygen = "spo2".equals(kind);
        boolean stress = "stress".equals(kind);
        if (!(sleepStage || sleepInterval || steps || heart || oxygen || stress)) {
            throw new IllegalArgumentException("UNSUPPORTED_HEALTH_KIND");
        }
        // Database v2 migrates every old source, including sleep_stage, to continuous.
        if (!("continuous".equals(measurementMode) || "manual".equals(measurementMode)
                || "sleep".equals(measurementMode))
                || steps && !"continuous".equals(measurementMode)
                || sleepInterval && !"sleep".equals(measurementMode)
                || sleepStage && "manual".equals(measurementMode)
                || complete && !sleepInterval) {
            throw new IllegalArgumentException("INVALID_MEASUREMENT_MODE");
        }
        if (sleepStage ? stage == null || value != null
                : stage != null || !sleepInterval && value == null) {
            throw new IllegalArgumentException("INVALID_HEALTH_MEASUREMENT");
        }
        if (sleepStage && (stage < 2 || stage > 5)) {
            throw new IllegalArgumentException("INVALID_SLEEP_STAGE");
        }
        if (value != null) {
            double measured = value.doubleValue();
            if (!Double.isFinite(measured) || measured < 0 || measured != Math.rint(measured)
                    || heart && (measured < 1 || measured > 250)
                    || oxygen && (measured < 1 || measured > 100)
                    || stress && measured > 100 || steps && measured > 0xffff_ffffL
                    || sleepInterval && measured > endMs - startMs) {
                throw new IllegalArgumentException("INVALID_HEALTH_MEASUREMENT");
            }
        }
        if ((heart || oxygen || stress) && endMs - startMs
                != ("continuous".equals(measurementMode) ? 60_000L : 1L)) {
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
                json.getString("measurementMode"), json.getBoolean("complete"));
    }
}
