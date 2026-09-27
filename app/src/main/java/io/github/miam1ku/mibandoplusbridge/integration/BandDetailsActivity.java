// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import androidx.appcompat.app.AppCompatActivity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen;
import java.text.DateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.BindingStore;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecordStore;
import org.json.JSONObject;

/** Registered-device status and account-isolated saved history, including after removal. */
public final class BandDetailsActivity extends AppCompatActivity {
    private static final String OHEALTH = "com.heytap.health";
    private static final String KERNELSU = "me.weishu.kernelsu";
    private TextView name;
    private TextView connection;
    private TextView battery;
    private TextView updated;
    private TextView history;
    private TextView saved;
    private Button next;
    private Spinner kindPicker;
    private String after;
    private int historyGeneration;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private static final String[] KINDS = {"sleep_interval", "sleep_stage", "heart_rate", "spo2", "stress", "steps_day", "steps_interval"};
    private final ContentObserver historyObserver = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { loadHistory(true); }
    };
    private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String requestedId = getIntent().getStringExtra("device_id");
        if (requestedId != null && !requestedId.equals(
                io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(this, "band-state").getString("deviceId", ""))) {
            finish();
            return;
        }
        BridgeScreen screen = BridgeScreen.attach(this, "设备详情", true);
        LinearLayout statusCard = screen.card();
        name = screen.bodyText(statusCard, "手环状态");
        connection = screen.bodyText(statusCard, "连接状态未读取");
        battery = screen.caption(statusCard, "电量未读取");
        updated = screen.caption(statusCard, "尚无同步记录");
        history = screen.caption(statusCard, "尚无同步记录");
        screen.setLastChildMargin(statusCard, 0);
        LinearLayout links = screen.card();
        screen.navRow(links, "打开 OHealth", () -> openPackage(OHEALTH));
        screen.navRow(links, "模块管理", () -> openPackage(KERNELSU));
        screen.setLastChildMargin(links, 0);
        screen.heading("已保存历史");
        screen.caption("仅显示当前健康账号下这台手环的记录。移除设备后仍会保留。");
        kindPicker = new Spinner(this);
        kindPicker.setMinimumHeight(BridgeScreen.dp(this, 48));
        ArrayAdapter<String> kinds = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"睡眠区间", "睡眠分期", "心率", "血氧", "压力", "每日步数", "步数区间"});
        kinds.setDropDownViewResource(androidx.appcompat.R.layout.support_simple_spinner_dropdown_item);
        kindPicker.setAdapter(kinds);
        int requested = getIntent() == null ? 0 : getIntent().getIntExtra("historyKind", 0);
        if (requested >= 0 && requested < KINDS.length) kindPicker.setSelection(requested);
        LinearLayout.LayoutParams pickerParams = new LinearLayout.LayoutParams(-1, -2);
        pickerParams.bottomMargin = BridgeScreen.dp(this, 12);
        screen.body.addView(kindPicker, pickerParams);
        LinearLayout historyCard = screen.card();
        saved = screen.bodyText(historyCard, "正在读取已保存历史…");
        next = screen.filled("下一页", () -> loadHistory(false));
        kindPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { loadHistory(true); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        getContentResolver().registerContentObserver(DeviceCardProvider.URI, false, observer);
        getContentResolver().registerContentObserver(HealthQueueProvider.RECORDS_URI, true, historyObserver);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        if (connection == null) return;
        refresh();
        reader.execute(() -> {
            io.github.miam1ku.mibandoplusbridge.data.BandStateRepository.refreshStoredSteps(this);
            main.post(() -> { if (!isFinishing() && !isDestroyed()) refresh(); });
        });
        loadHistory(true);
        Thread starter = new Thread(this::ensureLink, "OplusBandDetailsStart");
        starter.setDaemon(true);
        starter.start();
    }

    private void ensureLink() {
        try {
            if (new io.github.miam1ku.mibandoplusbridge.data.BandStateRepository(this).isRegistered()
                    && new io.github.miam1ku.mibandoplusbridge.service.OwnershipController(this).nativeReady()) {
                io.github.miam1ku.mibandoplusbridge.service.BandLiveService.start(this);
            }
        } catch (RuntimeException ignored) { }
    }

    @Override protected void onDestroy() {
        getContentResolver().unregisterContentObserver(observer);
        getContentResolver().unregisterContentObserver(historyObserver);
        historyGeneration++;
        reader.shutdownNow();
        super.onDestroy();
    }

    private void refresh() {
        var state = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(this, "band-state");
        String id = state.getString("deviceId", "");
        String deviceName = state.getString("name", "");
        name.setText(deviceName == null || deviceName.isBlank() ? "手环状态" : deviceName);
        if (id == null || id.isBlank()) {
            connection.setText("尚未获得设备标识");
            connection.setTextColor(0xff475569);
            battery.setText("电量未读取");
            updated.setText("尚无同步记录");
            return;
        }
        boolean connected = state.getBoolean("connected", false);
        connection.setText(connected ? "已连接" : "未连接");
        connection.setTextColor(connected ? 0xff157347 : 0xff475569);
        int level = state.getInt("battery", -1);
        battery.setText(level >= 0 && level <= 100
                ? "电量 " + level + "%" + (state.getBoolean("charging", false) ? " · 充电中" : "")
                : "电量未读取");
        long lastRead = state.getLong("lastUpdateMs", 0);
        updated.setText(lastRead > 0 && lastRead <= System.currentTimeMillis()
                ? "上次读取 " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(new Date(lastRead))
                : "尚无读取记录");
        long sync = state.getLong("lastSyncAtMs", 0);
        long steps = state.getLong("stepsToday", -1);
        int heart = state.getInt("heartRate", -1);
        history.setText((steps >= 0 ? "今日 " + steps + " 步" : "今日步数尚未同步")
                + (heart > 0 && heart <= 250 ? "，最近心率 " + heart + " 次/分" : "")
                + (sync > 0 ? "。最近同步 " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(sync)) : ""));
    }

    private void loadHistory(boolean reset) {
        if (saved == null || isFinishing() || isDestroyed()) return;
        if (reset) after = null;
        String cursor = after;
        String kind = KINDS[kindPicker.getSelectedItemPosition()];
        int generation = ++historyGeneration;
        next.setEnabled(false);
        saved.setText("正在读取已保存历史…");
        reader.execute(() -> {
            String message;
            String last = cursor;
            boolean more = false;
            try (HealthRecordStore store = new HealthRecordStore(this)) {
                JSONObject binding = new BindingStore(this).read();
                if (binding == null) throw new IllegalStateException("BINDING_REQUIRED");
                String device = BandStateRepository.deviceId(binding);
                String account = store.confirmedAccountHash();
                if (account == null) {
                    message = "请先在配置 App 确认当前健康账号，再查看已保存历史。";
                } else {
                    List<HealthRecord> page = new ArrayList<>();
                    try (Cursor rows = store.localMeasurements(device, kind, 0, Long.MAX_VALUE, cursor)) {
                        while (rows.moveToNext()) {
                            HealthRecord record = HealthRecord.fromJson(new JSONObject(rows.getString(2)));
                            if (!device.equals(record.deviceId) || !kind.equals(record.kind)) throw new IllegalStateException("HISTORY_IDENTITY_MISMATCH");
                            page.add(record);
                            last = rows.getString(0);
                        }
                    }
                    if (!account.equals(store.confirmedAccountHash())) throw new IllegalStateException("ACCOUNT_CHANGED");
                    more = page.size() == HealthRecordStore.PAGE_SIZE;
                    StringBuilder text = new StringBuilder();
                    for (HealthRecord record : page) {
                        if (text.length() > 0) text.append("\n\n");
                        appendRecord(text, record);
                    }
                    message = page.isEmpty() ? "这一页没有已保存记录。" : text.toString();
                }
            } catch (Exception unavailable) {
                message = "暂时无法读取已保存历史。请确认已解锁、已导入绑定并确认健康账号后重试。";
            }
            String result = message;
            String nextCursor = last;
            boolean hasMore = more;
            main.post(() -> {
                if (generation != historyGeneration || isFinishing() || isDestroyed()) return;
                saved.setText(result);
                after = nextCursor;
                next.setEnabled(hasMore);
                next.setText(hasMore ? "下一页" : "已到本页末尾");
            });
        });
    }

    private static void appendRecord(StringBuilder text, HealthRecord record) {
        java.time.ZoneId zone = record.timezone == null ? java.time.ZoneId.systemDefault() : java.time.ZoneId.of(record.timezone);
        java.time.format.DateTimeFormatter date = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone);
        text.append(date.format(java.time.Instant.ofEpochMilli(record.startMs)));
        if (record.kind.startsWith("sleep_")) {
            text.append("–").append(date.format(java.time.Instant.ofEpochMilli(record.endMs)));
            text.append("\n区间 ").append((record.endMs - record.startMs) / 60_000).append(" 分钟");
            if (record.stage != null) text.append(" · ").append(switch (record.stage) {
                case 2 -> "深睡"; case 3 -> "浅睡"; case 4 -> "快速眼动（REM）"; case 5 -> "清醒";
                default -> "阶段未知";
            });
            else {
                text.append(record.complete ? " · 完整区间" : " · 未完整区间");
                text.append(record.value == null ? "\n总睡眠时长未知"
                        : "\n已知总睡眠 " + record.value.longValue() / 60_000 + " 分钟");
            }
        } else {
            text.append("\n").append(record.value);
            text.append(switch (record.kind) { case "heart_rate" -> " 次/分"; case "spo2" -> "%"; case "stress" -> " 压力值"; default -> " 步"; });
            if ("manual".equals(record.measurementMode)) text.append(" · 手动测量");
            if ("sleep".equals(record.measurementMode)) text.append(" · 睡眠期间");
        }
        if (record.timezone != null) text.append("\n").append(record.timezone);
    }

    private void openPackage(String target) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(target);
        if (launch == null) {
            Toast.makeText(this, "未找到对应应用", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(launch);
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, "无法打开对应应用", Toast.LENGTH_SHORT).show();
        }
    }
}