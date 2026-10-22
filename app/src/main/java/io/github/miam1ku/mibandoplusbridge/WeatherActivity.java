// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen;
import io.github.miam1ku.mibandoplusbridge.ui.Copy;
import io.github.miam1ku.mibandoplusbridge.integration.WeatherSnapshotProvider;
import io.github.miam1ku.mibandoplusbridge.protocol.BandWeatherEncoder;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import io.github.miam1ku.mibandoplusbridge.service.WeatherSync;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.text.DateFormat;
import java.util.Date;

/** Reviews source weather and submits delivery through the shared live service. */
public final class WeatherActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BridgeScreen screen;
    private TextView status;
    private TextView delivery;
    private TextView association;
    private Button send;
    private Button review;
    private Button clearReview;
    private volatile BandWeatherEncoder.Sample ready;
    private boolean running;
    private final ContentObserver updates = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refreshSnapshot(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        screen = BridgeScreen.attach(this, "天气同步", true);
        LinearLayout source = screen.section("OHealth 天气");
        status = screen.bodyText(source, "正在读取 OHealth 天气…");
        screen.caption(source, "使用 OHealth 已有的天气和定位，天气和地点有效后即可发送到手环。");
        screen.filled(source, "获取当前位置天气", this::requestWeather);
        screen.textButton(source, "打开 OHealth", this::openHealth);
        LinearLayout band = screen.section("手环");
        delivery = screen.bodyText(band, "发送后，在手环上看一眼城市、温度和预报。");
        send = screen.filled(band, "发送到手环", this::sendWeather);
        review = screen.outlined(band, "核对手环城市", this::inspectCities);
        LinearLayout city = screen.section("城市关联");
        association = screen.bodyText(city, "");
        clearReview = screen.textButton(city, "解除城市关联", this::clearAssociation);
        refreshAssociation();
        review.setEnabled(false);
        clearReview.setEnabled(false);
        send.setEnabled(false);
        getContentResolver().registerContentObserver(WeatherSnapshotProvider.UPDATED, false, updates);
        refreshSnapshot();
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) refreshSnapshot();
    }

    private void requestWeather() {
        if (worker.isShutdown()) return;
        status.setText("正在获取当前位置天气，上一份有效天气会继续保留。");
        BandLiveService.refreshWeather(this);
        worker.execute(() -> {
            try {
                getContentResolver().call(WeatherSnapshotProvider.URI, "requestRefresh", null, null);
            } catch (RuntimeException unavailable) {
                main.post(() -> { if (!isDestroyed()) status.setText("打开一次 OHealth，再点「获取当前位置天气」。"); });
            }
            refreshSnapshot();
        });
    }

    private void openHealth() {
        Intent launcher = getPackageManager().getLaunchIntentForPackage("com.heytap.health");
        if (launcher == null) { status.setText("安装 OPPO 健康后即可获取天气。"); return; }
        try { startActivity(launcher); }
        catch (RuntimeException unavailable) { status.setText("从手机桌面打开 OPPO 健康即可。"); }
    }

    private void refreshSnapshot() {
        if (worker.isShutdown()) return;
        worker.execute(() -> {
            BandWeatherEncoder.Sample valid = null;
            String message;
            Bundle response = null;
            try {
                response = getContentResolver().call(WeatherSnapshotProvider.URI, "getSnapshot", null, null);
                if (response == null) throw new IllegalStateException("WEATHER_SOURCE_UNAVAILABLE");
                String source = sourceText(response);
                valid = WeatherSync.freshSample(response);
                message = "城市：" + valid.cityName() + " / " + valid.locationName()
                        + "\n来源更新时间：" + time(response.getLong("updatedAtMs", 0))
                        + "\n天气发布时间：" + time(valid.publishedAtMs())
                        + "\n逐日：" + valid.daily().size() + " 天，逐时：" + valid.hourly().size()
                        + " 小时，可以发送。"
                        + (source.isEmpty() ? "" : "\n" + source + "；以上快照仍可发送。");
            } catch (Exception invalid) {
                valid = null;
                String stored = storedWeather(response);
                String source = response == null ? sourceStatus() : sourceText(response);
                message = stored.isEmpty() ? "点「获取当前位置天气」取一份天气。" + (source.isEmpty() ? "" : "\n" + source + "。")
                        : stored + (source.isEmpty() ? "" : "\n" + source + "。");
            }
            var sync = getSharedPreferences("weather-sync", 0);
            String deliveryCode = sync.getString("status", "");
            String deliveryText = "发送后，在手环上看一眼城市、温度和预报。";
            if ("WEATHER_TRANSPORT_CONFIRMED".equals(deliveryCode)) {
                deliveryText = "上次送达：" + time(sync.getLong("confirmedAtMs", 0))
                        + "\n在手环上看一眼城市、温度和预报。";
            } else if (!deliveryCode.isEmpty() && !"WEATHER_CITIES_READY".equals(deliveryCode)
                    && !"WEATHER_READING_CITIES".equals(deliveryCode)) {
                deliveryText = Copy.weatherError(deliveryCode);
            }
            BandWeatherEncoder.Sample captured = valid;
            String result = message;
            String deliveryResult = deliveryText;
            main.post(() -> {
                if (isDestroyed()) return;
                ready = captured;
                send.setEnabled(captured != null && !running);
                review.setEnabled(captured != null && !running);
                clearReview.setEnabled(!running);
                if (!running) {
                    status.setText(result);
                    delivery.setText(deliveryResult);
                }
                refreshAssociation();
            });
        });
    }

    private static String storedWeather(Bundle response) {
        if (response == null) return "";
        String raw = response.getString("snapshot", "");
        if (raw == null || raw.isBlank()) return "";
        try {
            BandWeatherEncoder.Sample sample = BandWeatherEncoder.parse(new org.json.JSONObject(raw));
            return "手机已保存天气：" + sample.cityName() + " / " + sample.locationName()
                    + "\n发布时间：" + time(sample.publishedAtMs())
                    + "\n逐日：" + sample.daily().size() + " 天，逐时：" + sample.hourly().size()
                    + " 小时。点「获取当前位置天气」刷新后即可发送。";
        } catch (Exception ignored) {
            return "手机保存着一份旧天气，点「获取当前位置天气」刷新后即可发送。";
        }
    }

    private void sendWeather() { submitWeather(false); }

    private void inspectCities() { submitWeather(true); }

    private void submitWeather(boolean inspect) {
        BandWeatherEncoder.Sample sample = ready;
        if (sample == null || running) return;
        running = true;
        send.setEnabled(false);
        review.setEnabled(false);
        clearReview.setEnabled(false);
        delivery.setText(inspect ? "正在读取手环城市…" : "正在通过现有连接发送天气…");
        BandLiveService.requestWeather(this, sample, inspect).whenComplete((ignored, error) -> main.post(() -> {
            running = false;
            if (isDestroyed()) return;
            send.setEnabled(ready != null);
            review.setEnabled(ready != null);
            clearReview.setEnabled(true);
            String code = failureCode(error);
            if (error == null) {
                delivery.setText(inspect ? reviewText(sample)
                        : "天气已送达手环。在手环上看一眼城市、温度和预报，预报以上方的发布时间为准。");
            } else delivery.setText(Copy.weatherError(code));
            refreshAssociation();
            if ((error == null && inspect) || "WEATHER_CITY_CONFIRMATION_REQUIRED".equals(code)) showCityChoices(sample);
        }));
    }

    private static String failureCode(Throwable error) {
        if (error == null) return "";
        while ((error instanceof java.util.concurrent.CompletionException
                || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) error = error.getCause();
        if (error instanceof WeatherSync.Failure failure) return failure.code;
        String message = error.getMessage();
        return message != null && message.matches("[A-Z_]{1,80}") ? message : "WEATHER_UNAVAILABLE";
    }

    private static String time(long value) { return DateFormat.getDateTimeInstance().format(new Date(value)); }

    private static String sourceText(Bundle response) {
        return Copy.weatherSource(response.getString("status", ""), response.getBoolean("pending", false));
    }

    private String sourceStatus() {
        try {
            Bundle response = getContentResolver().call(WeatherSnapshotProvider.URI, "getSnapshot", null, null);
            return response == null ? Copy.weatherSource("UNAVAILABLE", false) : sourceText(response);
        } catch (RuntimeException unavailable) { return Copy.weatherSource("UNAVAILABLE", false); }
    }

    private void showCityChoices(BandWeatherEncoder.Sample sample) {
        var prefs = getSharedPreferences("weather-city-review", 0);
        if (!sample.locationKey().equals(prefs.getString("sourceKey", ""))
                || !sample.cityName().equals(prefs.getString("sourceCity", ""))
                || !sample.locationName().equals(prefs.getString("sourcePlace", ""))) {
            delivery.setText("OHealth 的城市有变化，点「核对手环城市」重新关联。");
            return;
        }
        int count = prefs.getInt("bandCount", 0);
        if (count == 0) return;
        String[] choices = new String[count];
        String[] codes = new String[count];
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            codes[i] = prefs.getString("bandCode" + i, "");
            names[i] = prefs.getString("bandName" + i, "");
            choices[i] = names[i] + "\n" + codes[i];
        }
        new MaterialAlertDialogBuilder(this).setTitle("选择手环城市").setItems(choices, (dialog, index) -> {
            String code = codes[index];
            String name = names[index];
            new MaterialAlertDialogBuilder(this).setTitle("确认城市关联？")
                    .setMessage("OHealth：" + sample.cityName() + " / " + sample.locationName()
                            + "\n编号：" + sample.locationKey() + "\n手环城市：" + name + "\n编号：" + code
                            + "\n温度：" + sample.temperature() + ("f".equals(sample.unit()) ? "℉" : "℃")
                            + "\n时区：" + sample.timezone()
                            + "\n逐日：" + sample.daily().size() + " 天，逐时：" + sample.hourly().size() + " 小时")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认关联", (choice, ignored) -> {
                        boolean saved = prefs.edit().putString("confirmedSourceKey", sample.locationKey())
                                .putString("confirmedSourceCity", sample.cityName())
                                .putString("confirmedSourcePlace", sample.locationName())
                                .putString("confirmedBandCode", code).putString("confirmedBandName", name).commit();
                        delivery.setText(saved ? "城市关联已确认，可以发送天气。每次发送都会再核对两端城市。" : "城市关联保存中断，再确认一次即可。");
                        refreshAssociation();
                    })
                    .show();
        }).show();
    }

    private String reviewText(BandWeatherEncoder.Sample sample) {
        var prefs = getSharedPreferences("weather-city-review", 0);
        var text = new StringBuilder("OHealth：").append(sample.cityName()).append(" / ")
                .append(sample.locationName()).append("\n编号：").append(sample.locationKey());
        for (int i = 0; i < prefs.getInt("bandCount", 0); i++) {
            text.append("\n手环城市：").append(prefs.getString("bandName" + i, ""))
                    .append("\n编号：").append(prefs.getString("bandCode" + i, ""));
        }
        return text.toString();
    }


    private void clearAssociation() {
        getSharedPreferences("weather-city-review", 0).edit()
                .remove("confirmedSourceKey").remove("confirmedSourceCity").remove("confirmedSourcePlace")
                .remove("confirmedBandCode").remove("confirmedBandName").commit();
        delivery.setText("城市关联已解除。下次发送时会重新核对城市。");
        refreshAssociation();
    }

    private void refreshAssociation() {
        var prefs = getSharedPreferences("weather-city-review", 0);
        String bandName = prefs.getString("confirmedBandName", "");
        String sourceCity = prefs.getString("confirmedSourceCity", "");
        boolean linked = !bandName.isEmpty();
        association.setText(linked ? "已关联：OHealth " + sourceCity + " → 手环 " + bandName
                : "发送时会自动核对两端城市。手环有多个城市时，点「核对手环城市」选一个关联。");
        clearReview.setVisibility(linked ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    @Override protected void onDestroy() {
        getContentResolver().unregisterContentObserver(updates);
        worker.shutdown();
        super.onDestroy();
    }
}
