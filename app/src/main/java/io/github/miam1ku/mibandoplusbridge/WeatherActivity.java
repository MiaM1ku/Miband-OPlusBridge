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
    private TextView status;
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
        BridgeScreen screen = BridgeScreen.attach(this, "天气", true);
        LinearLayout statusCard = screen.card();
        status = screen.bodyText(statusCard, "尚未读取 OHealth 天气。");
        screen.caption(statusCard, "使用 OHealth 已有的天气和定位。天气和地点有效后发送到手环。");
        screen.setLastChildMargin(statusCard, 0);
        screen.filled("获取当前位置天气", this::requestWeather);
        send = screen.outlined("发送到手环", this::sendWeather);
        screen.textButton("打开 OHealth", this::openHealth);
        review = screen.textButton("核对手环城市", this::inspectCities);
        clearReview = screen.textButton("撤销城市关联", this::clearAssociation);
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
        status.setText("正在请求当前位置天气。上一份有效天气会保留。");
        BandLiveService.refreshWeather(this);
        worker.execute(() -> {
            try {
                getContentResolver().call(WeatherSnapshotProvider.URI, "requestRefresh", null, null);
            } catch (RuntimeException unavailable) {
                main.post(() -> { if (!isDestroyed()) status.setText("天气源不可用，可手动打开 OHealth 后重试。"); });
            }
            refreshSnapshot();
        });
    }

    private void openHealth() {
        Intent launcher = getPackageManager().getLaunchIntentForPackage("com.heytap.health");
        if (launcher == null) { status.setText("OHealth 未安装，无法获取天气。"); return; }
        try { startActivity(launcher); }
        catch (RuntimeException unavailable) { status.setText("无法打开 OHealth，请从手机桌面打开。"); }
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
                        + " 小时。请核对城市后发送。"
                        + (source.isEmpty() ? "" : "\n" + source + "；目前仍可发送以上有效快照。");
            } catch (Exception invalid) {
                valid = null;
                String stored = storedWeather(response);
                String source = response == null ? sourceStatus() : sourceText(response);
                message = stored.isEmpty() ? "没有可发送的有效天气。" + source + " 请更新后再试。"
                        : stored + (source.isEmpty() ? "" : "\n" + source);
            }
            var delivery = getSharedPreferences("weather-sync", 0);
            String deliveryCode = delivery.getString("status", "");
            if ("WEATHER_TRANSPORT_CONFIRMED".equals(deliveryCode)) {
                message += "\n上次确认传输：" + time(delivery.getLong("confirmedAtMs", 0))
                        + "。仍需在手环核对实际显示。";
            } else if (!deliveryCode.isEmpty() && !"WEATHER_CITIES_READY".equals(deliveryCode)
                    && !"WEATHER_READING_CITIES".equals(deliveryCode)) {
                message += "\n" + weatherError(deliveryCode);
            }
            BandWeatherEncoder.Sample captured = valid;
            String result = message;
            main.post(() -> {
                if (isDestroyed()) return;
                ready = captured;
                send.setEnabled(captured != null && !running);
                review.setEnabled(captured != null && !running);
                clearReview.setEnabled(!running);
                if (!running) status.setText(result);
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
                    + " 小时。这份天气当前不能发送，请重新获取。";
        } catch (Exception ignored) {
            return "手机已有一份天气快照，但当前不能发送。请重新获取。";
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
        status.setText(inspect ? "正在读取手环城市……" : "正在通过现有连接发送天气……");
        BandLiveService.requestWeather(this, sample, inspect).whenComplete((ignored, error) -> main.post(() -> {
            running = false;
            if (isDestroyed()) return;
            send.setEnabled(ready != null);
            review.setEnabled(ready != null);
            clearReview.setEnabled(true);
            String code = failureCode(error);
            if (error == null) {
                status.setText(inspect ? reviewText(sample)
                        : "天气已确认传输。请在手环核对城市、温度和预报；传输确认不代表屏幕验收。"
                                + "\n请以来源快照显示的真实更新时间核对预报。");
            } else status.setText(weatherError(code));
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

    private static String weatherError(String code) {
        return switch (code) {
            case "WEATHER_CITY_CONFIRMATION_REQUIRED", "WEATHER_PREVIEW_CHANGED" -> "城市关联尚未确认或已改变；请重新读取并核对城市。";
            case "WEATHER_CITY_SETUP_REQUIRED" -> "手环没有有效城市，请先在官方应用配置城市，再切回桥接模式。";
            case "WEATHER_CITY_LIST_INVALID" -> "手环城市列表不可用，请在官方应用检查配置。";
            case "WEATHER_SOURCE_STALE", "WEATHER_FORECAST_STALE" -> "天气已过期，已停止发送，请重新获取。";
            case "WEATHER_BAND_REJECTED" -> "手环拒绝本次天气；健康连接保持不变。";
            case "WEATHER_BUSY" -> "天气任务正在处理，请稍后再试。";
            case "WEATHER_DISCONNECTED", "LIVE_SESSION_UNAVAILABLE", "WEATHER_CLOSED", "NATIVE_OWNERSHIP_REQUIRED" -> "请先在配置首页确认手环已添加、已接管且已连接。";
            case "WEATHER_DATA_INVALID" -> "天气数据或天气码不可用。";
            default -> "天气未完成传输，请检查连接和天气源后重试。";
        };
    }

    private static String time(long value) { return DateFormat.getDateTimeInstance().format(new Date(value)); }

    private static String sourceText(Bundle response) {
        if (response.getBoolean("pending", false)) return "正在等待 OHealth 更新天气";
        return switch (response.getString("status", "")) {
            case "" -> "";
            case "OHEALTH_LOCATION_PERMISSION_REQUIRED" -> "OHealth 缺少定位权限";
            case "OHEALTH_LOCATION_DISABLED" -> "网络定位已关闭";
            case "OHEALTH_LOCATION_UNAVAILABLE" -> "未取得足够准确且新鲜的位置";
            case "OHEALTH_WEATHER_CLOUD_UNAVAILABLE" -> "天气网络请求失败";
            case "OHEALTH_WEATHER_TIMEOUT" -> "天气处理超时，可手动打开 OHealth 后重试";
            default -> "天气源暂不可用，可手动打开 OHealth 后重试";
        };
    }

    private String sourceStatus() {
        try {
            Bundle response = getContentResolver().call(WeatherSnapshotProvider.URI, "getSnapshot", null, null);
            return response == null ? "天气源不可用。" : sourceText(response);
        } catch (RuntimeException unavailable) { return "天气源不可用。"; }
    }

    private void showCityChoices(BandWeatherEncoder.Sample sample) {
        var prefs = getSharedPreferences("weather-city-review", 0);
        if (!sample.locationKey().equals(prefs.getString("sourceKey", ""))
                || !sample.cityName().equals(prefs.getString("sourceCity", ""))
                || !sample.locationName().equals(prefs.getString("sourcePlace", ""))) {
            status.setText("来源城市已改变，请重新读取手环城市后核对。");
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
                        status.setText(saved ? "城市关联已确认，可发送天气。每次发送仍会重新核对两端城市。" : "城市关联保存失败，请重试。");
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
        status.setText("已撤销城市关联；未发送天气。");
    }

    @Override protected void onDestroy() {
        getContentResolver().unregisterContentObserver(updates);
        worker.shutdown();
        super.onDestroy();
    }
}
