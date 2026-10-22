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
import io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd;
import io.github.miam1ku.mibandoplusbridge.protocol.BandAlarmCommand;
import io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand;
import io.github.miam1ku.mibandoplusbridge.protocol.BandMusicCommand;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground protocol tests. The log keeps protocol fields; MAC, token, and health values stay in memory. */
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
        LinearLayout live = screen.section("会话与最近结果");
        session = screen.bodyText(live, "正在检查连接会话…");
        result = screen.caption(live, "选择下方任一测试开始。");
        screen.filled(live, "立即同步", () -> {
            try {
                BandLiveService.start(this);
                setResultText("已请求同步，手环连上后就可以发通知和来电。");
            } catch (RuntimeException failure) {
                setResultText("会话启动中断：" + failure.getClass().getSimpleName() + "。回首页点「立即同步」再试一次。");
            }
            refresh();
        });

        LinearLayout device = screen.section("设备信息");
        info = screen.bodyText(device, "正在读取…");
        screen.outlined(device, "刷新设备信息", this::refresh);

        LinearLayout notify = screen.section("消息通知");
        screen.caption(notify, "经当前会话发一条测试通知到这只手环。");
        screen.filled(notify, "发送测试通知", () -> send("测试通知", BandNotificationCommand.post(
                getPackageName(), "桥接测试", "lab-notify", 1,
                "测试通知", "这是一条发往手环的测试消息。", Instant.now(), ZoneId.systemDefault()), false));
        screen.outlined(notify, "撤回测试通知", () -> send("撤回通知",
                BandNotificationCommand.dismiss(getPackageName(), "lab-notify", 1), false));

        LinearLayout call = screen.section("来电");
        screen.caption(call, "手环会显示来电界面，点「结束测试来电」即可关掉。");
        screen.filled(call, "发送测试来电", () -> send("测试来电",
                BandNotificationCommand.incomingCall("测试来电", Instant.now(), ZoneId.systemDefault()), false));
        screen.outlined(call, "结束测试来电", () -> send("来电结束", BandNotificationCommand.endCall(), false));

        LinearLayout find = screen.section("查找手机");
        screen.caption(find, "调用 OPPO 健康自己的查找手机响铃。");
        screen.filled(find, "测试查找手机", () -> FindPhone.start(this));
        screen.outlined(find, "停止查找", () -> FindPhone.stop(this));

        LinearLayout music = screen.section("音乐");
        screen.caption(music, "把手环上的播放信息刷成一条测试曲目。实际播放时自动同步，手环按键控制手机。");
        screen.filled(music, "发送测试音乐", () -> send("测试音乐", BandMusicCommand.playback(
                50, "测试音乐", "桥接", 12, 180, true), true));

        LinearLayout dnd = screen.section("免打扰");
        screen.caption(dnd, "依次发送同步开关和手机当前的免打扰状态，共两包。");
        screen.filled(dnd, "同步免打扰", () -> sendAll("免打扰",
                BandDndCommand.mirror(PhoneDnd.currentFilter(this))));

        LinearLayout alarm = screen.section("闹钟");
        screen.caption(alarm, "测试响铃、关闭和稍后响。");
        screen.filled(alarm, "测试闹钟响铃", () -> send("闹钟响铃",
                BandAlarmCommand.operation(0, 1, (int) (System.currentTimeMillis() / 1000L), "测试闹钟"), true));
        screen.outlined(alarm, "测试关闭闹钟", () -> send("关闭闹钟",
                BandAlarmCommand.operation(1, 1, -1, ""), true));
        screen.outlined(alarm, "测试稍后响", () -> send("稍后响",
                BandAlarmCommand.operation(2, 1, (int) (System.currentTimeMillis() / 1000L), "测试闹钟"), true));

        LinearLayout extra = screen.section("其他");
        screen.navRow(extra, "独立鉴权与电量", () -> startActivity(new Intent(this, DiagnosticActivity.class)));
        screen.navRow(extra, "检查更新", this::checkUpdate);
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
                : (connected ? "已连接，会话准备中，点「立即同步」。" : "等待连接，点「立即同步」后开始测试。"));
    }

    private static String deviceText(LocalPrefs state, boolean registered, boolean connected, int battery) {
        if (!registered) return "回首页导入绑定并添加到健康，这里会显示设备信息。";
        StringBuilder text = new StringBuilder();
        text.append("名称：").append(blank(state.getString("name", ""), "—")).append('\n');
        text.append("型号：").append(blank(state.getString("modelId", ""), "—")).append('\n');
        String firmware = state.getString("verifiedFirmware", "");
        if (firmware.isBlank()) firmware = state.getString("firmware", "");
        text.append("固件：").append(blank(firmware, "待读取")).append('\n');
        text.append("硬件：").append(blank(state.getString("verifiedHardware", ""), "待读取")).append('\n');
        text.append("连接：").append(connected ? "已连接" : "等待连接").append('\n');
        text.append("电量：").append(battery >= 0 && battery <= 100 ? battery + "%" : "待读取");
        if (state.getBoolean("charging", false)) text.append("（充电中）");
        text.append('\n');
        long sync = state.getLong("lastSyncAtMs", 0);
        text.append("上次同步：").append(sync > 0 ? TIME.format(Instant.ofEpochMilli(sync)) : "—").append('\n');
        String deviceId = state.getString("deviceId", "");
        text.append("设备号：").append(deviceId.length() >= 16 ? deviceId.substring(0, 16) + "…" : blank(deviceId, "—")).append('\n');
        text.append("MAC：").append(tailMac(state.getString("mac", "")));
        return text.toString();
    }

    private static String tailMac(String mac) {
        if (mac == null || mac.length() < 5) return "—";
        return "…" + mac.substring(mac.length() - 5).replace(":", "");
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void send(String label, nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command,
                      boolean sessionCommand) {
        if (!ready()) return;
        setResultText("正在发送" + label + "…");
        var pending = sessionCommand
                ? BandLiveService.sendSessionCommand(command)
                : BandLiveService.forwardHostNotification(this, command);
        pending.whenComplete((ignored, error) -> main.post(() -> finishSend(label, error)));
    }

    private void sendAll(String label, java.util.List<nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command> commands) {
        if (!ready()) return;
        setResultText("正在发送" + label + "…");
        sendAllAt(label, commands, 0);
    }

    private void sendAllAt(String label,
            java.util.List<nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command> commands, int index) {
        if (index >= commands.size()) {
            setResultText(label + "已确认送达手环。共 " + commands.size() + " 包。");
            refresh();
            return;
        }
        BandLiveService.sendSessionCommand(commands.get(index)).whenComplete((ignored, error) -> main.post(() -> {
            if (isDestroyed()) return;
            if (error != null) {
                finishSend(label + "（第 " + (index + 1) + " 包）", error);
                return;
            }
            sendAllAt(label, commands, index + 1);
        }));
    }

    private boolean ready() {
        if (BandLiveService.notificationSessionReady(this)) return true;
        try { BandLiveService.start(this); } catch (RuntimeException ignored) { }
        setResultText("会话准备中。点「立即同步」，手环连上后再发送。");
        refresh();
        return false;
    }

    private void finishSend(String label, Throwable error) {
        if (isDestroyed()) return;
        String detail = error == null ? "已确认送达手环。"
                : ("发送中断：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())
                        + "。点「立即同步」后再试一次。");
        Log.i("OplusBandBridge", "LAB " + label + " " + (error == null ? "ok" : error.getClass().getSimpleName()));
        setResultText(label + detail);
        refresh();
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
                message = "检查更新中断：" + failure.getClass().getSimpleName() + "。联网后再点一次。";
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
