// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;

import android.Manifest;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.miam1ku.mibandoplusbridge.protocol.SppDiagnosticClient;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground diagnostic page. Official package state stays with {@code OwnershipController}. */
public final class DiagnosticActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button start;
    private Button live;
    private Button permission;
    private volatile SppDiagnosticClient client;
    private boolean running;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BridgeScreen screen = BridgeScreen.attach(this, "独立连接诊断", true);
        LinearLayout result = screen.section("诊断结果");
        int stored = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(this, "band-state").getInt("battery", -1);
        status = screen.bodyText(result, stored >= 0 && stored <= 100
                ? "上次读取电量：" + stored + "%。随时可以开始下一次诊断。" : "准备就绪，点「开始鉴权与电量测试」。");
        LinearLayout card = screen.section("操作");
        screen.caption(card, "开始前，请结束小米运动健康里的表盘安装、OTA 升级和 NFC 操作，并暂停官方应用。"
                + "诊断会完成鉴权、核对型号、读取电量，30 秒后自动断开。");
        permission = screen.filled(card, "授权附近设备",
                () -> requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1));
        start = screen.filled(card, "开始鉴权与电量测试", this::begin);
        live = screen.outlined(card, "保持连接，在设备空间显示电量", () -> worker.execute(() -> {
            String message;
            try {
                io.github.miam1ku.mibandoplusbridge.service.BandLiveService.start(this);
                message = "正在连接，鉴权完成后显示已连接。";
            } catch (RuntimeException unavailable) {
                message = "回首页添加设备并授权附近设备，再回来点这里。";
            }
            String visible = message;
            runOnUiThread(() -> { if (!isDestroyed()) status.setText(visible); });
        }));
        updatePermission();
    }

    private void updatePermission() {
        boolean granted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        start.setEnabled(granted && !running);
        permission.setVisibility(granted ? android.view.View.GONE : android.view.View.VISIBLE);
        live.setEnabled(false);
        worker.execute(() -> {
            boolean ready = new io.github.miam1ku.mibandoplusbridge.data.BandStateRepository(this).isRegistered()
                    && new io.github.miam1ku.mibandoplusbridge.service.OwnershipController(this).nativeReady();
            runOnUiThread(() -> {
                if (!isDestroyed()) live.setEnabled(granted && !running && ready);
            });
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        updatePermission();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            status.setText("附近设备已授权。确认官方应用已暂停，就可以开始诊断。");
        }
    }


    private void begin() {
        if (running) return;
        running = true;
        start.setEnabled(false);
        client = new SppDiagnosticClient(this, code -> {
            Log.i("OplusBandBridge", "DIAG_" + code);
            runOnUiThread(() -> { if (!isDestroyed()) status.setText(code); });
        });
        SppDiagnosticClient active = client;
        worker.execute(() -> {
            String message;
            try {
                io.github.miam1ku.mibandoplusbridge.service.BandLiveService.pauseForDiagnostic(this);
                var result = active.run();
                String card;
                try {
                    new io.github.miam1ku.mibandoplusbridge.data.BandStateRepository(this)
                            .recordVerifiedDevice(result.batteryPercent(), result.batteryState(), false,
                                    result.firmware(), result.hardware(), result.sku());
                    io.github.miam1ku.mibandoplusbridge.data.DeviceImageStore.requestRefresh(this);
                    card = "设备卡片已记录电量，诊断连接已关闭。";
                } catch (Exception failure) {
                    card = "设备卡片稍后更新：" + failure.getMessage();
                }
                getSharedPreferences("diagnostic-result", 0).edit()
                        .putLong("verifiedAtMs", System.currentTimeMillis())
                        .putInt("batteryPercent", result.batteryPercent())
                        .putString("hardware", result.hardware()).putString("firmware", result.firmware()).commit();
                Log.i("OplusBandBridge", "DIAG_SUCCESS battery=" + result.batteryPercent());
                message = "诊断完成，连接已关闭。\n鉴权通过。\n电量：" + result.batteryPercent()
                        + "%\n型号：" + result.hardware() + "\n固件：" + result.firmware()
                        + "\n" + card;
            } catch (SppDiagnosticClient.Failure failure) {
                Log.i("OplusBandBridge", "DIAG_FAILED " + failure.code);
                message = "诊断已停止，连接已关闭。\n代码：" + failure.code;
            } catch (Exception failure) {
                String category = failure.getClass().getSimpleName();
                Log.i("OplusBandBridge", "DIAG_FAILED " + category);
                message = "诊断中断，连接已关闭。\n类型：" + category + "\n绑定和凭据保持原样。";
            }
            try {
                io.github.miam1ku.mibandoplusbridge.service.BandLiveService.resumeAfterDiagnostic(this);
            } catch (RuntimeException unavailable) {
                message += "\n登记已保留，回首页点「立即同步」恢复连接。";
            }
            String finalMessage = message;
            runOnUiThread(() -> {
                running = false;
                client = null;
                if (!isDestroyed()) {
                    status.setText(finalMessage);
                    updatePermission();
                }
            });
        });
    }

    @Override protected void onStop() {
        SppDiagnosticClient active = client;
        if (active != null) new Thread(active::close, "OplusBandDiagnosticCancel").start();
        super.onStop();
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }


}
