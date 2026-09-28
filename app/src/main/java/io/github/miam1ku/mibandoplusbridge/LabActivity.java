// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;
import io.github.miam1ku.mibandoplusbridge.notify.FindPhone;
import io.github.miam1ku.mibandoplusbridge.protocol.BandMusicCommand;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground-only protocol tests. Does not log MAC, token, or health values. */
public final class LabActivity extends AppCompatActivity {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BridgeScreen screen;
    private TextView info;
    private TextView session;
    private TextView result;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        screen = BridgeScreen.attach(this, "协议测试", true);
        LinearLayout device = screen.card();
        screen.overline(device, "设备信息");
        info = screen.bodyText(device, "正在读取…");
        screen.setLastChildMargin(device, 0);
        screen.outlined("刷新设备信息", this::refresh);

        LinearLayout live = screen.card();
        screen.overline(live, "会话");
        session = screen.bodyText(live, "正在检查连接会话…");
        screen.setLastChildMargin(live, 0);
        screen.outlined("立即同步", () -> {
            try {
                BandLiveService.start(this);
                setResultText("已请求同步。等手环连上后再发通知或来电。");
            } catch (RuntimeException failure) {
                setResultText("无法启动会话：" + failure.getClass().getSimpleName());
            }
            refresh();
        });

        LinearLayout notify = screen.card();
        screen.overline(notify, "消息通知");
        screen.caption(notify, "经当前 SPP 会话发一条测试通知到这只手环。不经过系统通知监听。");
        screen.setLastChildMargin(notify, 0);
        screen.filled("发送测试通知", () -> send("测试通知", BandNotificationCommand.post(
                getPackageName(), "桥接测试", "lab-notify", 1,
                "测试通知", "这是一条发往手环的测试消息。", Instant.now(), ZoneId.systemDefault()), false));
        screen.outlined("撤回测试通知", () -> send("撤回通知",
                BandNotificationCommand.dismiss(getPackageName(), "lab-notify", 1), false));

        LinearLayout call = screen.card();
        screen.overline(call, "来电");
        screen.caption(call, "手环应显示来电界面。点结束来电可关掉。");
        screen.setLastChildMargin(call, 0);
        screen.filled("发送测试来电", () -> send("测试来电",
                BandNotificationCommand.incomingCall("测试来电", Instant.now(), ZoneId.systemDefault()), false));
        screen.outlined("结束测试来电", () -> send("来电结束", BandNotificationCommand.endCall(), false));

        LinearLayout find = screen.card();
        screen.overline(find, "查找手机");
        screen.caption(find, "调用 OPPO 健康自己的查找手机响铃，不向手环发命令。");
        screen.setLastChildMargin(find, 0);
        screen.filled("测试查找手机", () -> FindPhone.start(this));
        screen.outlined("停止查找", () -> FindPhone.stop(this));

        LinearLayout music = screen.card();
        screen.overline(music, "音乐");
        screen.caption(music, "把手环上的播放信息刷成一条测试曲目。真正播放时会自动同步，手环按键会控制手机。");
        screen.setLastChildMargin(music, 0);
        screen.filled("发送测试音乐", () -> send("测试音乐", BandMusicCommand.playback(
                50, "测试音乐", "桥接", 12, 180, true), true));

        LinearLayout extra = screen.card();
        screen.overline(extra, "其他");
        screen.setLastChildMargin(extra, 0);
        screen.navRow(extra, "独立鉴权与电量", () -> startActivity(new Intent(this, DiagnosticActivity.class)));
        screen.navRow(extra, "检查更新", this::checkUpdate);
        result = screen.bodyText("等待操作。");
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        LocalPrefs state = LocalPrefs.open(this, "band-state");
        boolean registered = new BandStateRepository(this).isRegistered();
        boolean connected = registered && state.getBoolean("connected", false);
        boolean ready = BandLiveService.notificationSessionReady(this);
        int battery = state.getInt("battery", -1);
        info.setText(deviceText(state, registered, connected, battery));
        session.setText(ready
                ? "会话就绪，可以发通知、来电和音乐。"
                : (connected ? "已连接，会话尚未就绪。点立即同步。" : "未连接。点立即同步后再测。"));
    }

    private static String deviceText(LocalPrefs state, boolean registered, boolean connected, int battery) {
        if (!registered) return "未登记手环。请先在首页导入并添加到健康。";
        StringBuilder text = new StringBuilder();
        text.append("名称：").append(blank(state.getString("name", ""), "未命名")).append('\n');
        text.append("型号：").append(blank(state.getString("modelId", ""), "未知")).append('\n');
        String firmware = state.getString("verifiedFirmware", "");
        if (firmware.isBlank()) firmware = state.getString("firmware", "");
        text.append("固件：").append(blank(firmware, "未读取")).append('\n');
        text.append("硬件：").append(blank(state.getString("verifiedHardware", ""), "未读取")).append('\n');
        text.append("连接：").append(connected ? "已连接" : "未连接").append('\n');
        text.append("电量：").append(battery >= 0 && battery <= 100 ? battery + "%" : "未读取");
        if (state.getBoolean("charging", false)) text.append("（充电中）");
        text.append('\n');
        long sync = state.getLong("lastSyncAtMs", 0);
        text.append("上次同步：").append(sync > 0 ? TIME.format(Instant.ofEpochMilli(sync)) : "无").append('\n');
        String deviceId = state.getString("deviceId", "");
        text.append("设备号：").append(deviceId.length() >= 16 ? deviceId.substring(0, 16) + "…" : blank(deviceId, "无")).append('\n');
        text.append("MAC：").append(tailMac(state.getString("mac", "")));
        return text.toString();
    }

    private static String tailMac(String mac) {
        if (mac == null || mac.length() < 5) return "未保存";
        return "…" + mac.substring(mac.length() - 5).replace(":", "");
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void send(String label, nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command,
                      boolean sessionCommand) {
        if (!BandLiveService.notificationSessionReady(this)) {
            try { BandLiveService.start(this); } catch (RuntimeException ignored) { }
            setResultText("会话未就绪，无法发送" + label + "。请先点立即同步，等手环连上后再试。");
            refresh();
            return;
        }
        setResultText("正在发送" + label + "…");
        var pending = sessionCommand
                ? BandLiveService.sendSessionCommand(command)
                : BandLiveService.forwardHostNotification(this, command);
        pending.whenComplete((ignored, error) -> main.post(() -> {
            if (isDestroyed()) return;
            String detail = error == null ? "已确认送达手环。"
                    : ("发送失败：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            Log.i("OplusBandBridge", "LAB " + label + " " + (error == null ? "ok" : error.getClass().getSimpleName()));
            setResultText(label + detail);
            refresh();
        }));
    }

    private void checkUpdate() {
        setResultText("正在检查更新…");
        worker.execute(() -> {
            String message;
            String url = null;
            try {
                UpdateChecker.Result latest = UpdateChecker.check(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME);
                if (latest.newer()) {
                    message = "有新版本 " + latest.versionName() + "（" + latest.versionCode() + "）。当前 "
                            + BuildConfig.VERSION_NAME + "。";
                    if (!latest.notes().isBlank()) message += "\n" + latest.notes();
                    url = latest.htmlUrl();
                } else {
                    message = "已是最新：" + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）。";
                }
            } catch (Exception failure) {
                message = "检查更新失败：" + failure.getClass().getSimpleName();
            }
            String open = url;
            String text = message;
            main.post(() -> {
                if (isDestroyed()) return;
                setResultText(text);
                if (open != null) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(open)));
                    } catch (RuntimeException ignored) { }
                }
            });
        });
    }

    private void setResultText(String value) {
        if (result != null) result.setText(value);
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
