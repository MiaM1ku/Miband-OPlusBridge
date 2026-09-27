// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import de.robv.android.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.integration.WeatherSnapshotProvider;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Calls OHealth's own weather source; forwards only validated forecast fields to the bridge. */
public final class OHealthWeatherHook {
    private static final String HOST = "com.heytap.health";

    private OHealthWeatherHook() {}

    public static void install(Context context, ClassLoader loader) throws Exception {
        if (!HOST.equals(android.app.Application.getProcessName())) return;
        Class<?> cloud = Class.forName("com.heytap.weather.service.WeatherCloud2", false, loader);
        Class<?> consumerType = Class.forName("io.reactivex.rxjava3.functions.Consumer", false, loader);
        cloud.getDeclaredMethod("getWeatherDetailByCoordinate", String.class, String.class,
                String.class, consumerType, consumerType);
        Object source = cloud.getField("INSTANCE").get(null);
        LocationManager location = context.getSystemService(LocationManager.class);
        HandlerThread worker = new HandlerThread("OplusBandWeatherSource");
        worker.start();
        Handler handler = new Handler(worker.getLooper());
        AtomicReference<String> active = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
        ContentObserver[] requested = new ContentObserver[1];
        requested[0] = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) {
                if (active.get() != null) return;
                String id = null;
                try {
                    Bundle request = context.getContentResolver().call(WeatherSnapshotProvider.URI,
                            "getPendingRequest", null, new Bundle());
                    if (request == null || !"WEATHER_REQUESTED".equals(request.getString("status"))) return;
                    id = request.getString("requestId");
                    if (id == null || id.isBlank()) return;
                    active.set(id);
                    String requestId = id;
                    if (context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED
                            && context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        fail(context, active, requestId, "OHEALTH_LOCATION_PERMISSION_REQUIRED");
                        return;
                    }
                    if (location == null || !location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        fail(context, active, requestId, "OHEALTH_LOCATION_DISABLED");
                        return;
                    }
                    CancellationSignal cancellation = new CancellationSignal();
                    handler.postDelayed(() -> {
                        if (requestId.equals(active.get())) {
                            cancellation.cancel();
                            fail(context, active, requestId, "OHEALTH_WEATHER_TIMEOUT");
                        }
                    }, 70_000);
                    location.getCurrentLocation(LocationManager.NETWORK_PROVIDER, cancellation, handler::post,
                            fix -> {
                                if (!requestId.equals(active.get())) return;
                                if (!usable(fix)) {
                                    fail(context, active, requestId, "OHEALTH_LOCATION_UNAVAILABLE");
                                    return;
                                }
                                try {
                                    Object success = consumer(loader, consumerType, response -> handler.post(() -> {
                                        if (!requestId.equals(active.get())) return;
                                        try {
                                            JSONObject snapshot = extract(response);
                                            Bundle payload = new Bundle();
                                            payload.putString("requestId", requestId);
                                            payload.putString("snapshot", snapshot.toString());
                                            Bundle accepted = context.getContentResolver().call(WeatherSnapshotProvider.URI,
                                                    "publishWeather", null, payload);
                                            if (accepted == null || !"WEATHER_STORED".equals(accepted.getString("status"))) {
                                                fail(context, active, requestId, "OHEALTH_WEATHER_STORAGE_FAILED");
                                            } else active.compareAndSet(requestId, null);
                                        } catch (Throwable invalid) {
                                            fail(context, active, requestId, "OHEALTH_WEATHER_EXTRACT_FAILED");
                                        }
                                    }));
                                    Object error = consumer(loader, consumerType, ignored -> handler.post(() ->
                                            fail(context, active, requestId, "OHEALTH_WEATHER_CLOUD_UNAVAILABLE")));
                                    XposedHelpers.callMethod(source, "getWeatherDetailByCoordinate",
                                            Double.toString(fix.getLongitude()), Double.toString(fix.getLatitude()),
                                            "c", success, error);
                                } catch (Throwable unavailable) {
                                    fail(context, active, requestId, "OHEALTH_WEATHER_REQUEST_FAILED");
                                }
                            });
                } catch (Throwable unavailable) {
                    String message = unavailable.getMessage() == null ? "" : unavailable.getMessage();
                    if (message.contains("Unknown authority") && lookups.getAndIncrement() < 4) {
                        handler.postDelayed(() -> requested[0].onChange(false), 2_000L * lookups.get());
                        return;
                    }
                    if (id != null) fail(context, active, id, unavailable instanceof SecurityException
                            ? "OHEALTH_LOCATION_PERMISSION_REQUIRED" : "OHEALTH_WEATHER_REQUEST_FAILED");
                    else Log.i("OplusBandBridge", "OHEALTH_WEATHER_PROVIDER_UNAVAILABLE");
                }
            }
        };
        context.getContentResolver().registerContentObserver(WeatherSnapshotProvider.URI, false, requested[0]);
        handler.post(() -> requested[0].onChange(false));
    }

    private static boolean usable(Location location) {
        if (location == null || !location.hasAccuracy() || !Float.isFinite(location.getAccuracy())
                || location.getAccuracy() < 0 || location.getAccuracy() > 20_000f) return false;
        long age = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        return age >= 0 && age <= 600_000_000_000L
                && Double.isFinite(location.getLatitude()) && Double.isFinite(location.getLongitude())
                && Math.abs(location.getLatitude()) <= 90 && Math.abs(location.getLongitude()) <= 180;
    }

    private static Object consumer(ClassLoader loader, Class<?> type, java.util.function.Consumer<Object> callback) {
        return Proxy.newProxyInstance(loader, new Class[]{type}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "accept" -> {
                    callback.accept(args != null && args.length == 1 ? args[0] : null);
                    yield null;
                }
                case "toString" -> "OplusBandWeatherConsumer";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("Unsupported weather callback method");
            };
        });
    }

    private static void fail(Context context, AtomicReference<String> active, String id, String reason) {
        if (!active.compareAndSet(id, null)) return;
        Log.i("OplusBandBridge", reason);
        reportFailure(context, id, reason);
    }

    private static void reportFailure(Context context, String request, String reason) {
        try {
            Bundle details = new Bundle();
            details.putString("requestId", request);
            details.putString("status", reason);
            context.getContentResolver().call(WeatherSnapshotProvider.URI, "reportFailure", null, details);
        } catch (RuntimeException ignored) {
            Log.i("OplusBandBridge", "OHEALTH_WEATHER_REPORT_FAILED");
        }
    }

    private static JSONObject extract(Object base) throws Exception {
        Object city = required(base, "getCityVO");
        Object summary = required(base, "getWeatherSummaryVO");
        Object current = required(summary, "getObw");
        Object daily = required(summary, "getDfw");
        Object hourly = required(summary, "getHfw");
        JSONObject snapshot = new JSONObject()
                .put("locationKey", required(city, "getLocationKey"))
                .put("cityName", parentCity(city))
                .put("locationName", required(city, "getCityName"))
                .put("regionName", text(optional(city, "getRegionName")))
                .put("tertiaryName", text(optional(city, "getTertiaryName")))
                .put("secondaryName", text(optional(city, "getSecondaryName")))
                .put("timezone", required(city, "getTimezone"))
                .put("unit", "c") // Verified direct fallback passes unit "c" to WeatherCloud2.
                .put("publishedAtMs", sourceTimeMillis(required(current, "getForecastTime")))
                .put("conditionCode", required(current, "getWeatherCode"))
                .put("temperature", required(current, "getTemp"));
        Object humidity = optional(current, "getHumidity");
        if (humidity instanceof Number percent) {
            long rounded = Math.round(percent.doubleValue());
            if (rounded >= 0 && rounded <= 100) snapshot.put("humidity", rounded);
        }
        Object air = optional(summary, "getAq");
        if (air != null) {
            Object index = optional(air, "getIndex");
            if (index instanceof Number quality && quality.intValue() >= 0 && quality.intValue() <= 500) {
                snapshot.put("aqi", quality.intValue());
            }
        }
        putInt(snapshot, "windPower", optional(current, "getWindPower"), 0, 17);
        putInt(snapshot, "windDegree", optional(current, "getWindDegree"), 0, 360);
        putInt(snapshot, "uvIndex", optional(current, "getUvIndex"), 0, 15);
        Object pressure = optional(current, "getPressure");
        if (pressure instanceof Number value) {
            double hpa = value.doubleValue();
            if (hpa >= 800 && hpa <= 1100 || hpa >= 80_000 && hpa <= 110_000) snapshot.put("pressure", hpa);
        }
        java.time.ZoneId zone = java.time.ZoneId.of(snapshot.getString("timezone"));
        var published = java.time.Instant.ofEpochMilli(snapshot.getLong("publishedAtMs")).atZone(zone);
        long publicationHour = published.truncatedTo(java.time.temporal.ChronoUnit.HOURS).toInstant().toEpochMilli();
        JSONArray days = new JSONArray();
        for (Object day : (List<?>) required(daily, "getData")) {
            if (days.length() == 7) break;
            long dayTime = sourceTimeMillis(required(day, "getTime"));
            if (days.length() == 0 && java.time.Instant.ofEpochMilli(dayTime).atZone(zone).toLocalDate()
                    .isBefore(published.toLocalDate())) continue;
            JSONObject row = new JSONObject()
                    .put("timeMs", dayTime)
                    .put("dayCode", required(day, "getDayCode"))
                    .put("nightCode", required(day, "getNightCode"))
                    .put("minTemp", required(day, "getTempMin"))
                    .put("maxTemp", required(day, "getTempMax"));
            long sunrise = (Long) required(day, "getSunriseTime");
            long sunset = (Long) required(day, "getSunsetTime");
            if (sunrise > 0) row.put("sunriseMs", sourceTimeMillis(sunrise));
            if (sunset > 0) row.put("sunsetMs", sourceTimeMillis(sunset));
            days.put(row);
        }
        JSONArray hours = new JSONArray();
        for (Object hour : (List<?>) required(hourly, "getData")) {
            if (hours.length() == 23) break;
            long hourTime = sourceTimeMillis(required(hour, "getTime"));
            if (hours.length() == 0 && hourTime < publicationHour) continue;
            hours.put(new JSONObject().put("timeMs", hourTime)
                    .put("conditionCode", required(hour, "getWeatherCode"))
                    .put("temperature", required(hour, "getTemp")));
        }
        return snapshot.put("daily", days).put("hourly", hours);
    }

    private static long sourceTimeMillis(Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("WEATHER_TIME_TYPE_INVALID");
        long time = number.longValue();
        if (time >= 1_000_000_000L && time < 10_000_000_000L) return time * 1_000L;
        if (time >= 1_000_000_000_000L && time < 10_000_000_000_000L) return time;
        throw new IllegalArgumentException("WEATHER_TIME_RANGE_INVALID");
    }

    private static String parentCity(Object city) {
        String district = text(optional(city, "getCityName"));
        for (String getter : new String[]{"getTertiaryName", "getSecondaryName", "getRegionName"}) {
            String value = text(optional(city, getter));
            if (value == null || value.equals(district) || value.endsWith("洲") || "中国".equals(value)
                    || value.endsWith("省")) continue;
            if (value.endsWith("市") && value.length() > 1) value = value.substring(0, value.length() - 1);
            return value;
        }
        String secondary = text(optional(city, "getSecondaryName"));
        return secondary == null ? district : secondary;
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        return text;
    }

    private static Object required(Object source, String getter) {
        Object value = XposedHelpers.callMethod(source, getter);
        if (value == null) {
            Log.i("OplusBandBridge", "OHEALTH_WEATHER_FIELD_MISSING " + getter);
            throw new IllegalArgumentException("Missing OHealth weather field");
        }
        return value;
    }

    private static Object optional(Object source, String getter) {
        if (source == null) return null;
        try {
            return XposedHelpers.callMethod(source, getter);
        } catch (Throwable absent) {
            return null;
        }
    }

    private static void putInt(org.json.JSONObject snapshot, String name, Object value, int min, int max) {
        if (value instanceof Number number && number.intValue() >= min && number.intValue() <= max) {
            try { snapshot.put(name, number.intValue()); } catch (org.json.JSONException ignored) { }
        }
    }
}
