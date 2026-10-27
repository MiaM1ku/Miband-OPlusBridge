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
import io.github.miam1ku.mibandoplusbridge.data.BandCatalog;
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
    private int kindIndex;
    private BridgeScreen screen;
    private TextView statusChip;
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
        if (!panelRequest(getIntent())) setTheme(io.github.miam1ku.mibandoplusbridge.R.style.Theme_OplusBand);
        super.onCreate(state);
        if (openNativePanelIfRequested()) return;
        String requestedId = getIntent().getStringExtra("device_id");
        if (requestedId != null && !requestedId.equals(
                io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(this, "band-state").getString("deviceId", ""))) {
            finish();
            return;
        }
        screen = BridgeScreen.attach(this, "设备详情", true);
        LinearLayout statusCard = screen.card();
        name = screen.title(statusCard, "小米手环");
        statusChip = screen.chip(statusCard, "正在读取", BridgeScreen.Tone.NEUTRAL);
        connection = screen.caption(statusCard, "");
        connection.setVisibility(View.GONE);
        battery = screen.bodyText(statusCard, "电量待读取");
        updated = screen.caption(statusCard, "");
        history = screen.caption(statusCard, "");
        LinearLayout links = screen.card();
        screen.navRow(links, "打开 OPPO 健康", () -> openPackage(OHEALTH));
        screen.navRow(links, "打开 KernelSU", () -> openPackage(KERNELSU));
        screen.heading("已保存历史");
        screen.caption("当前 OHealth 账号下这只手环的记录。移除设备后记录照样保留。");
        int requested = getIntent() == null ? 0 : getIntent().getIntExtra("historyKind", 0);
        kindIndex = requested >= 0 && requested < KINDS.length ? requested : 0;
        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        com.google.android.material.chip.ChipGroup kinds = new com.google.android.material.chip.ChipGroup(this);
        kinds.setSingleSelection(true);
        kinds.setSelectionRequired(true);
        kinds.setSingleLine(true);
        String[] labels = {"睡眠区间", "睡眠分期", "心率", "血氧", "压力", "每日步数", "步数区间"};
        for (int index = 0; index < labels.length; index++) {
            com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this, null,
                    com.google.android.material.R.attr.chipStyle);
            chip.setText(labels[index]);
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setId(View.generateViewId());
            chip.setTag(index);
            kinds.addView(chip);
            if (index == kindIndex) chip.setChecked(true);
        }
        kinds.setOnCheckedStateChangeListener((group, checked) -> {
            if (checked.isEmpty()) return;
            Object tag = group.findViewById(checked.get(0)).getTag();
            if (tag instanceof Integer chosen && chosen != kindIndex) {
                kindIndex = chosen;
                loadHistory(true);
            }
        });
        chipScroll.addView(kinds);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-1, -2);
        chipParams.bottomMargin = BridgeScreen.dp(this, 8);
        screen.body.addView(chipScroll, chipParams);
        LinearLayout historyCard = screen.card();
        saved = screen.bodyText(historyCard, "正在读取已保存历史…");
        next = screen.outlined(historyCard, "加载更多", () -> loadHistory(false));
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
        String deviceName = BandCatalog.displayName(state.getString("modelId", ""), state.getString("name", ""));
        name.setText(deviceName);
        if (id == null || id.isBlank()) {
            screen.setChip(statusChip, "待添加", BridgeScreen.Tone.NEUTRAL);
            battery.setText("添加手环后显示电量和同步信息。");
            updated.setVisibility(View.GONE);
            history.setVisibility(View.GONE);
            return;
        }
        boolean connected = state.getBoolean("connected", false);
        screen.setChip(statusChip, connected ? "已连接" : "等待连接",
                connected ? BridgeScreen.Tone.POSITIVE : BridgeScreen.Tone.NEUTRAL);
        int level = state.getInt("battery", -1);
        battery.setText(level >= 0 && level <= 100
                ? "电量 " + level + "%" + (state.getBoolean("charging", false) ? " · 充电中" : "")
                : "电量待读取");
        long lastRead = state.getLong("lastUpdateMs", 0);
        updated.setVisibility(View.VISIBLE);
        updated.setText(lastRead > 0 && lastRead <= System.currentTimeMillis()
                ? "读取于 " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(new Date(lastRead))
                : "连上后显示读取时间");
        long sync = state.getLong("lastSyncAtMs", 0);
        long steps = state.getLong("stepsToday", -1);
        int heart = state.getInt("heartRate", -1);
        history.setVisibility(View.VISIBLE);
        history.setText((steps >= 0 ? "今日 " + steps + " 步" : "今日步数等待同步")
                + (heart > 0 && heart <= 250 ? " · 最近心率 " + heart + " 次/分" : "")
                + (sync > 0 ? "\n同步于 " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(sync)) : ""));
    }

    private void loadHistory(boolean reset) {
        if (saved == null || isFinishing() || isDestroyed()) return;
        if (reset) after = null;
        String cursor = after;
        String kind = KINDS[kindIndex];
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
                    message = "健康记录写入当前 OHealth 账号后会显示在这里。";
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
                    message = page.isEmpty()
                            ? (cursor == null ? "这一类还在等待记录，手环同步后会出现在这里。" : "已显示全部记录。")
                            : text.toString();
                }
            } catch (Exception unavailable) {
                message = "解锁手机并导入绑定后即可查看历史。";
            }
            String result = message;
            String nextCursor = last;
            boolean hasMore = more;
            main.post(() -> {
                if (generation != historyGeneration || isFinishing() || isDestroyed()) return;
                saved.setText(result);
                after = nextCursor;
                next.setEnabled(hasMore);
                next.setText(hasMore ? "加载更多" : "已显示全部记录");
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
            if (record.stage != null) text.append(" · ").append(io.github.miam1ku.mibandoplusbridge.ui.Copy.stageName(record.stage));
            else {
                text.append(record.complete ? " · 完整区间" : " · 部分区间");
                text.append(record.value == null ? "\n总睡眠时长待同步"
                        : "\n已知总睡眠 " + record.value.longValue() / 60_000 + " 分钟");
            }
        } else {
            text.append("\n").append(record.value);
            text.append(switch (record.kind) { case "heart_rate" -> " 次/分"; case "spo2" -> "%"; case "stress" -> " 压力值"; default -> " 步"; });
            if ("manual".equals(record.measurementMode)) text.append(" · 手动测量");
            if ("sleep".equals(record.measurementMode)) text.append(" · 睡眠期间");
            if ("resting".equals(record.measurementMode)) text.append(" · 当日静息");
            if ("walking".equals(record.measurementMode)) text.append(" · 当日步行平均");
        }
        if (record.timezone != null) text.append("\n").append(record.timezone);
    }

    private static boolean panelRequest(Intent incoming) {
        return incoming != null && NativePanel.panelAction(incoming.getAction());
    }

    private boolean openNativePanelIfRequested() {
        Intent incoming = getIntent();
        if (!panelRequest(incoming)) return false;
        Intent panel = new Intent(incoming);
        if (!NativePanel.redirect(this, panel)) return false;
        try {
            startActivity(panel);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
        finish();
        return true;
    }

    private void openPackage(String target) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(target);
        if (launch == null) {
            Toast.makeText(this, "安装对应应用后即可打开", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(launch);
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, "从手机桌面打开对应应用即可", Toast.LENGTH_SHORT).show();
        }
    }
}