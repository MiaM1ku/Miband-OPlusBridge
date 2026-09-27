// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.ContentProvider;
import android.content.Intent;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import org.json.JSONArray;
import org.json.JSONObject;

/** A signed OHealth-only weather handoff; location data never enters public device cards. */
public final class WeatherSnapshotProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://io.github.miam1ku.mibandoplusbridge.weather/request");
    public static final Uri UPDATED = Uri.parse("content://io.github.miam1ku.mibandoplusbridge.weather/snapshot");
    private static final String HEALTH = "com.heytap.health";
    private static final long WINDOW_MS = 90_000;

    @Override public boolean onCreate() { return true; }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        boolean self = Binder.getCallingUid() == Process.myUid();
        if (!self) {
            HostIdentity.requireCaller(getContext(), HEALTH);
        }
        try {
            var prefs = getContext().getSharedPreferences("weather-bridge", 0);
            return switch (method) {
                case "requestRefresh" -> {
                    requireSelf(self);
                    // No weather polling or sending while the official app owns the band.
                    if (!"NATIVE".equals(io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(getContext(), "ownership")
                            .getString("mode", "OFFICIAL"))) yield status("OFFICIAL_MODE");
                    getContext().grantUriPermission(HEALTH, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    if (isPending(prefs)) {
                        Bundle existing = status("WEATHER_REQUESTED");
                        existing.putString("requestId", prefs.getString("request", ""));
                        yield existing;
                    }
                    String request = java.util.UUID.randomUUID().toString();
                    long expiry = System.currentTimeMillis() + WINDOW_MS;
                    if (!prefs.edit().putString("request", request).putString("error", "")
                            .putLong("expiryWallMs", expiry)
                            .putLong("expiryElapsedMs", SystemClock.elapsedRealtime() + WINDOW_MS).commit()) {
                        yield status("WEATHER_REQUEST_STORAGE_FAILED");
                    }
                    getContext().getContentResolver().notifyChange(URI, null);
                    Bundle response = status("WEATHER_REQUESTED");
                    response.putString("requestId", request);
                    yield response;
                }
                case "getPendingRequest" -> {
                    if (self) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                    boolean pending = isPending(prefs);
                    if (!pending) getContext().revokeUriPermission(HEALTH, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Bundle response = status(pending ? "WEATHER_REQUESTED" : "NO_PENDING_REQUEST");
                    if (pending) response.putString("requestId", prefs.getString("request", ""));
                    yield response;
                }
                case "publishWeather" -> {
                    if (self || extras == null) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                    String request = extras.getString("requestId");
                    if (!isPending(prefs) || !prefs.getString("request", "").equals(request)) {
                        throw new SecurityException("WEATHER_REQUEST_EXPIRED");
                    }
                    String json = extras.getString("snapshot");
                    if (json == null || json.length() > 131_072) throw new IllegalArgumentException("WEATHER_SNAPSHOT_SIZE_INVALID");
                    JSONObject snapshot = new JSONObject(json);
                    validate(snapshot);
                    if (!prefs.edit().putString("snapshot", snapshot.toString()).putString("request", "")
                            .putString("error", "").putLong("updatedAtMs", System.currentTimeMillis()).commit()) {
                        yield status("WEATHER_STORAGE_FAILED");
                    }
                    getContext().revokeUriPermission(HEALTH, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    getContext().getContentResolver().notifyChange(UPDATED, null);
                    yield status("WEATHER_STORED");
                }
                case "reportFailure" -> {
                    if (self || extras == null || !isPending(prefs)
                            || !prefs.getString("request", "").equals(extras.getString("requestId"))) {
                        throw new SecurityException("WEATHER_REQUEST_EXPIRED");
                    }
                    String error = sourceFailure(extras.getString("status", ""));
                    if (!prefs.edit().putString("request", "").putString("error", error).commit()) {
                        yield status("WEATHER_STORAGE_FAILED");
                    }
                    getContext().revokeUriPermission(HEALTH, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    getContext().getContentResolver().notifyChange(UPDATED, null);
                    yield status(error);
                }
                case "getSnapshot" -> {
                    requireSelf(self);
                    boolean pending = isPending(prefs);
                    String error = prefs.getString("error", "");
                    if (!pending && !prefs.getString("request", "").isEmpty()) error = "OHEALTH_WEATHER_TIMEOUT";
                    Bundle response = status(error);
                    response.putString("snapshot", prefs.getString("snapshot", ""));
                    response.putLong("updatedAtMs", prefs.getLong("updatedAtMs", 0));
                    response.putBoolean("pending", pending);
                    response.putLong("remainingMs", pending ? Math.max(0, Math.min(
                            prefs.getLong("expiryWallMs", 0) - System.currentTimeMillis(),
                            prefs.getLong("expiryElapsedMs", 0) - SystemClock.elapsedRealtime())) : 0);
                    yield response;
                }
                default -> throw new IllegalArgumentException("UNSUPPORTED_WEATHER_OPERATION");
            };
        } catch (SecurityException | IllegalArgumentException rejected) {
            if (rejected instanceof IllegalArgumentException && "publishWeather".equals(method)) {
                Log.i("OplusBandBridge", "OHEALTH_WEATHER_REJECTED " + rejected.getMessage());
            }
            throw rejected;
        } catch (Exception failure) {
            return status("WEATHER_PROVIDER_UNAVAILABLE");
        }
    }

    private static void validate(JSONObject value) throws Exception {
        String key = value.getString("locationKey");
        String city = value.getString("cityName");
        String place = value.getString("locationName");
        String zone = value.getString("timezone");
        String unit = value.getString("unit");
        long published = value.getLong("publishedAtMs");
        int code = value.getInt("conditionCode");
        double temp = value.getDouble("temperature");
        if (!key.matches("[A-Za-z0-9:_-]{1,64}")) {
            throw new IllegalArgumentException("WEATHER_LOCATION_KEY_INVALID");
        }
        if (city.isBlank() || city.length() > 80 || place.isBlank() || place.length() > 80) {
            throw new IllegalArgumentException("WEATHER_CITY_INVALID");
        }
        if (zone.isBlank() || zone.length() > 64) throw new IllegalArgumentException("WEATHER_TIMEZONE_INVALID");
        if (!("c".equals(unit) || "f".equals(unit))) throw new IllegalArgumentException("WEATHER_UNIT_INVALID");
        if (published < 1_000_000_000_000L || published > System.currentTimeMillis() + 3_600_000L) {
            Log.i("OplusBandBridge", "OHEALTH_PUBLICATION_SHAPE epochSeconds="
                    + (published >= 1_000_000_000L && published < 10_000_000_000L)
                    + " future=" + (published > System.currentTimeMillis() + 3_600_000L));
            throw new IllegalArgumentException("WEATHER_PUBLICATION_TIME_INVALID");
        }
        if (code < 0 || code > 71) throw new IllegalArgumentException("WEATHER_CONDITION_INVALID");
        if (!Double.isFinite(temp) || temp < -130 || temp > 140) {
            throw new IllegalArgumentException("WEATHER_TEMPERATURE_INVALID");
        }
        JSONArray daily = value.getJSONArray("daily");
        JSONArray hourly = value.getJSONArray("hourly");
        if (daily.length() > 7 || hourly.length() > 23) {
            throw new IllegalArgumentException("WEATHER_FORECAST_INCOMPLETE");
        }
        for (int i = 0; i < daily.length(); i++) {
            JSONObject day = daily.getJSONObject(i);
            if (!day.has("timeMs") || !day.has("dayCode") || !day.has("nightCode")
                    || !day.has("maxTemp") || !day.has("minTemp")) throw new IllegalArgumentException("WEATHER_DAY_INCOMPLETE");
        }
        for (int i = 0; i < hourly.length(); i++) {
            JSONObject hour = hourly.getJSONObject(i);
            if (!hour.has("timeMs") || !hour.has("conditionCode") || !hour.has("temperature")) {
                throw new IllegalArgumentException("WEATHER_HOUR_INCOMPLETE");
            }
        }
    }

    private static String sourceFailure(String code) {
        return switch (code) {
            case "OHEALTH_LOCATION_PERMISSION_REQUIRED", "OHEALTH_LOCATION_DISABLED",
                    "OHEALTH_LOCATION_UNAVAILABLE", "OHEALTH_WEATHER_TIMEOUT",
                    "OHEALTH_WEATHER_CLOUD_UNAVAILABLE", "OHEALTH_WEATHER_STORAGE_FAILED",
                    "OHEALTH_WEATHER_EXTRACT_FAILED", "OHEALTH_WEATHER_REQUEST_FAILED" -> code;
            default -> "OHEALTH_WEATHER_UNAVAILABLE";
        };
    }

    private static boolean isPending(android.content.SharedPreferences prefs) {
        return !prefs.getString("request", "").isEmpty()
                && System.currentTimeMillis() < prefs.getLong("expiryWallMs", 0)
                && SystemClock.elapsedRealtime() < prefs.getLong("expiryElapsedMs", 0);
    }
    private static void requireSelf(boolean self) {
        if (!self) throw new SecurityException("OWNER_ONLY");
    }
    private static Bundle status(String code) {
        Bundle result = new Bundle();
        result.putString("status", code);
        return result;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        throw new SecurityException("WEATHER_QUERY_FORBIDDEN");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("WEATHER_WRITE_FORBIDDEN"); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
        throw new SecurityException("WEATHER_WRITE_FORBIDDEN");
    }
    @Override public int delete(Uri uri, String where, String[] args) { throw new SecurityException("WEATHER_DELETE_FORBIDDEN"); }
}
