// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.integration.WeatherSnapshotProvider;
import io.github.miam1ku.mibandoplusbridge.protocol.BandWeatherEncoder;
import io.github.miam1ku.mibandoplusbridge.protocol.LiveCommandQueue;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Weather transactions on the service's single-thread coordinator; never owns a socket. */
public final class WeatherSync implements AutoCloseable {
    public static final class Failure extends IllegalStateException {
        public final String code;
        public Failure(String code) { super(code); this.code = code; }
    }

    private enum Operation { REFRESH, INSPECT, SEND }
    private final Context context;
    private final Supplier<LiveCommandQueue> queue;
    private final ScheduledExecutorService coordinator;
    private final ContentObserver observer = new ContentObserver(null) {
        @Override public void onChange(boolean selfChange) {
            dispatch(() -> { if (waitingSnapshot) readRefreshResult(); });
        }
    };
    private volatile boolean closed;
    private volatile boolean awaitingCities;
    private boolean observing;
    private boolean waitingSnapshot;
    private CompletableFuture<Void> active;
    private Operation operation;
    private ScheduledFuture<?> expiry;
    private LiveCommandQueue transactionQueue;

    public WeatherSync(Context context, Supplier<LiveCommandQueue> queue,
                       ScheduledExecutorService coordinator) {
        this.context = context.getApplicationContext();
        this.queue = queue;
        this.coordinator = coordinator;
        dispatch(() -> {
            if (closed) return;
            try {
                this.context.getContentResolver().registerContentObserver(
                        WeatherSnapshotProvider.UPDATED, false, observer);
                observing = true;
            } catch (RuntimeException unavailable) {
                record("WEATHER_SOURCE_UNAVAILABLE");
            }
        });
    }

    public CompletionStage<Void> refreshAndSend() { return submit(Operation.REFRESH, null); }
    public CompletionStage<Void> inspectCities(BandWeatherEncoder.Sample sample) {
        return submit(Operation.INSPECT, sample);
    }
    public CompletionStage<Void> send(BandWeatherEncoder.Sample sample) { return submit(Operation.SEND, sample); }

    private CompletionStage<Void> submit(Operation requested, BandWeatherEncoder.Sample sample) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            coordinator.execute(() -> {
                if (closed) { result.completeExceptionally(new Failure("WEATHER_CLOSED")); return; }
                if (active != null) {
                    if (operation == requested) relay(active, result);
                    else result.completeExceptionally(new Failure("WEATHER_BUSY"));
                    return;
                }
                active = result;
                operation = requested;
                transactionQueue = queue.get();
                if (transactionQueue == null) { finish("WEATHER_DISCONNECTED"); return; }
                try {
                    if (requested == Operation.REFRESH) {
                        Bundle response;
                        try {
                            response = context.getContentResolver().call(WeatherSnapshotProvider.URI,
                                    "requestRefresh", null, null);
                        } catch (RuntimeException unavailable) {
                            response = null;
                        }
                        if (response == null || !"WEATHER_REQUESTED".equals(response.getString("status"))) {
                            // A source failure does not invalidate a previously fresh snapshot.
                            queryCities(freshSnapshot(null));
                            return;
                        }
                        waitingSnapshot = true;
                        readRefreshResult();
                    } else queryCities(freshSnapshot(sample));
                } catch (Exception failure) { finish(code(failure)); }
            });
        } catch (RejectedExecutionException stopped) {
            result.completeExceptionally(new Failure("WEATHER_CLOSED"));
        }
        return result;
    }

    private static void relay(CompletionStage<Void> source, CompletableFuture<Void> target) {
        source.whenComplete((ignored, error) -> {
            if (error == null) target.complete(null); else target.completeExceptionally(error);
        });
    }

    private Bundle snapshot() {
        Bundle response = context.getContentResolver().call(WeatherSnapshotProvider.URI, "getSnapshot", null, null);
        if (response == null) throw new Failure("WEATHER_SOURCE_UNAVAILABLE");
        return response;
    }

    /** Pending or failed refreshes preserve usable older data and its original timestamps. */
    public static BandWeatherEncoder.Sample freshSample(Bundle response) throws Exception {
        if (response == null) throw new Failure("WEATHER_SOURCE_UNAVAILABLE");
        long now = System.currentTimeMillis();
        long updated = response.getLong("updatedAtMs", 0);
        if (updated <= 0 || updated > now || now - updated > 3_600_000L) {
            throw new Failure("WEATHER_SOURCE_STALE");
        }
        BandWeatherEncoder.Sample sample = BandWeatherEncoder.parse(new JSONObject(response.getString("snapshot", "")));
        if (sample.publishedAtMs() > now + 3_600_000L
                || now - sample.publishedAtMs() > 6 * 3_600_000L) throw new Failure("WEATHER_FORECAST_STALE");
        BandWeatherEncoder.validateForecast(sample);
        return sample;
    }

    private BandWeatherEncoder.Sample freshSnapshot(BandWeatherEncoder.Sample displayed) throws Exception {
        Bundle response = snapshot();
        BandWeatherEncoder.Sample sample = freshSample(response);
        if (displayed != null && (!sample.locationKey().equals(displayed.locationKey())
                || !sample.cityName().equals(displayed.cityName())
                || !sample.locationName().equals(displayed.locationName()))) throw new Failure("WEATHER_PREVIEW_CHANGED");
        return sample;
    }

    private void readRefreshResult() {
        if (!waitingSnapshot || active == null) return;
        try {
            Bundle response = snapshot();
            if (response.getBoolean("pending", false)) {
                if (expiry == null) expiry = coordinator.schedule(() -> {
                    expiry = null;
                    readRefreshResult();
                }, Math.max(1, response.getLong("remainingMs", 90_000L)), TimeUnit.MILLISECONDS);
                return;
            }
            waitingSnapshot = false;
            cancelExpiry();
            queryCities(freshSample(response));
        } catch (Exception failure) { finish(code(failure)); }
    }

    private void queryCities(BandWeatherEncoder.Sample sample) {
        CompletableFuture<Void> transaction = active;
        awaitingCities = true;
        record("WEATHER_READING_CITIES");
        transactionQueue.request(XiaomiProto.Command.newBuilder().setType(10).setSubtype(5).build(), 10, 5)
                .whenComplete((response, failure) -> dispatch(() -> {
                    if (active != transaction) return;
                    awaitingCities = false;
                    if (failure != null) { finish("WEATHER_TRANSPORT_FAILED"); return; }
                    if (response.hasStatus() && response.getStatus() != 0) { finish("WEATHER_BAND_REJECTED"); return; }
                    if (!response.hasWeather() || !response.getWeather().hasLocations()) {
                        finish("WEATHER_CITY_LIST_INVALID"); return;
                    }
                    try {
                        BandWeatherEncoder.Sample fresh = freshSnapshot(sample);
                        XiaomiProto.WeatherLocations cities = response.getWeather().getLocations();
                        saveReview(fresh, cities);
                        if (operation == Operation.INSPECT) { finish(null); return; }
                        publish(transaction, fresh, cities);
                    } catch (Exception invalid) { finish(code(invalid)); }
                }));
    }

    private void saveReview(BandWeatherEncoder.Sample sample, XiaomiProto.WeatherLocations cities) {
        var prefs = context.getSharedPreferences("weather-city-review", 0);
        var edit = prefs.edit().putString("sourceKey", sample.locationKey())
                .putString("sourceCity", sample.cityName()).putString("sourcePlace", sample.locationName());
        int count = 0;
        for (var city : cities.getLocationList()) {
            if (!city.getCode().matches("weathercn:[0-9]{9}") || !city.hasName()
                    || city.getName().isBlank() || city.getName().length() > 80) continue;
            edit.putString("bandCode" + count, city.getCode()).putString("bandName" + count, city.getName());
            count++;
        }
        for (int i = count; i < prefs.getInt("bandCount", 0); i++) edit.remove("bandCode" + i).remove("bandName" + i);
        if (!edit.putInt("bandCount", count).commit()) throw new Failure("WEATHER_CITY_STORAGE_FAILED");

    }
    private void publish(CompletableFuture<Void> transaction, BandWeatherEncoder.Sample fresh,
                         XiaomiProto.WeatherLocations cities) {
        var review = context.getSharedPreferences("weather-city-review", 0);
        try {
            XiaomiProto.Command city = BandWeatherEncoder.echoLocations(cities);
            BandWeatherEncoder.Sample bound = BandWeatherEncoder.bindObservedCity(fresh, cities,
                    review.getString("confirmedBandCode", ""), review.getString("confirmedBandName", ""));
            transactionQueue.request(city, 10, 6).whenComplete((set, setFailure) -> dispatch(() -> {
                if (active != transaction) return;
                if (setFailure != null) { finish("WEATHER_TRANSPORT_FAILED"); return; }
                if (set != null && set.hasStatus() && set.getStatus() != 0) {
                    finish("WEATHER_BAND_REJECTED");
                    return;
                }
                sendFrame(transaction, fresh, cities, BandWeatherEncoder.encode(bound), 0);
            }));
        } catch (IllegalArgumentException missing) {
            if (!"WEATHER_CITY_SETUP_REQUIRED".equals(missing.getMessage())) {
                finish(code(missing));
                return;
            }
            sendFrame(transaction, fresh, cities, BandWeatherEncoder.encode(fresh), 0);
        }
    }

    private BandWeatherEncoder.Sample bind(BandWeatherEncoder.Sample sample, XiaomiProto.WeatherLocations cities) {
        var prefs = context.getSharedPreferences("weather-city-review", 0);
        try {
            return BandWeatherEncoder.bindObservedCity(sample, cities,
                    prefs.getString("confirmedBandCode", ""), prefs.getString("confirmedBandName", ""));
        } catch (IllegalArgumentException changed) { throw new Failure(changed.getMessage()); }
    }

    private void sendFrame(CompletableFuture<Void> transaction, BandWeatherEncoder.Sample sample,
                           XiaomiProto.WeatherLocations cities, List<XiaomiProto.Command> frames, int index) {
        if (active != transaction) return;
        if (index == frames.size()) { finish(null); return; }
        try {
            bind(freshSnapshot(sample), cities);
            if (transactionQueue != queue.get()) throw new Failure("WEATHER_DISCONNECTED");
            transactionQueue.send(frames.get(index)).whenComplete((ignored, failure) -> dispatch(() -> {
                if (active != transaction) return;
                if (failure != null) finish("WEATHER_TRANSPORT_FAILED");
                else sendFrame(transaction, sample, cities, frames, index + 1);
            }));
        } catch (Exception failure) { finish(code(failure)); }
    }

    /** Called before queue.onCommand by the sole socket reader; only schedules work. */
    public void onCommand(XiaomiProto.Command command) {
        if (closed || command.getType() != 10) return;
        boolean ownCityResponse = command.getSubtype() == 5 && awaitingCities;
        dispatch(() -> {
            if (closed) return;
            if (command.hasStatus() && command.getStatus() != 0) {
                if (active != null) finish("WEATHER_BAND_REJECTED");
                else record("WEATHER_BAND_REJECTED");
                return;
            }
            if (command.getSubtype() == 5 && !ownCityResponse) refreshAndSend();
            else if (command.getSubtype() == 3 && command.hasWeather() && command.getWeather().hasLocation()) {
                String bound = context.getSharedPreferences("weather-city-review", 0).getString("confirmedBandCode", "");
                if (!bound.isEmpty() && bound.equals(command.getWeather().getLocation().getCode())) refreshAndSend();
            }
        });
    }

    public void onDisconnected() { dispatch(() -> finish("WEATHER_DISCONNECTED")); }

    @Override public void close() {
        closed = true;
        dispatch(() -> {
            finish("WEATHER_CLOSED");
            if (observing) {
                context.getContentResolver().unregisterContentObserver(observer);
                observing = false;
            }
        });
    }

    private void cancelExpiry() { if (expiry != null) { expiry.cancel(false); expiry = null; } }

    private void finish(String error) {
        if (active == null) return;
        CompletableFuture<Void> result = active;
        Operation completed = operation;
        active = null;
        awaitingCities = false;
        waitingSnapshot = false;
        transactionQueue = null;
        cancelExpiry();
        record(error == null ? completed == Operation.INSPECT ? "WEATHER_CITIES_READY" : "WEATHER_TRANSPORT_CONFIRMED" : error);
        if (error == null) result.complete(null); else result.completeExceptionally(new Failure(error));
    }

    private void record(String status) {
        try {
            var edit = context.getSharedPreferences("weather-sync", 0).edit().putString("status", status);
            if ("WEATHER_TRANSPORT_CONFIRMED".equals(status)) edit.putLong("confirmedAtMs", System.currentTimeMillis());
            edit.apply();
            context.getContentResolver().notifyChange(WeatherSnapshotProvider.UPDATED, null);
        } catch (RuntimeException unavailable) {
            // Status persistence cannot strand a completed transport future.
        }
    }

    private static String code(Exception error) {
        if (error instanceof Failure failure) return failure.code;
        String message = error.getMessage();
        return message != null && message.startsWith("WEATHER_") ? message : "WEATHER_DATA_INVALID";
    }

    private void dispatch(Runnable work) {
        try { coordinator.execute(work); } catch (RejectedExecutionException stopped) { /* Service has stopped. */ }
    }
}
