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

/** Foreground-only diagnostic surface; does not change official package state. */
public final class DiagnosticActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button start;
    private Button live;
    private volatile SppDiagnosticClient client;
    private boolean running;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BridgeScreen screen = BridgeScreen.attach(this, "连接诊断", true);
        LinearLayout card = screen.card();
        screen.caption(card, "先结束官方高级操作并暂停小米运动健康。禁止在固件升级中测试。仅鉴权、核对型号和读取电量；不读取历史、不修改设置。30 秒截止，完成后自动断开。");
        Button permission = screen.outlined(card, "授予附近设备权限",
                () -> requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1));
        permission.setEnabled(true);
        start = screen.filled(card, "开始独立鉴权与电量测试", this::begin);
        live = screen.outlined(card, "保持连接并在我的设备显示电量", () -> worker.execute(() -> {
            String message;
            try {
                io.github.miam1ku.mibandoplusbridge.service.BandLiveService.start(this);
                message = "正在连接。鉴权完成后才显示已连接。";
            } catch (RuntimeException unavailable) {
                message = "请先在配置首页添加设备、接管并授予附近设备权限。";
            }
            String visible = message;
            runOnUiThread(() -> { if (!isDestroyed()) status.setText(visible); });
        }));
        int stored = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(this, "band-state").getInt("battery", -1);
        status = screen.bodyText(card, stored >= 0 && stored <= 100
                ? "上次读取电量：" + stored + "%。等待下一次诊断。" : "等待诊断。");
        screen.setLastChildMargin(card, 0);
        updatePermission();
    }

    private void updatePermission() {
        boolean granted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        start.setEnabled(granted && !running);
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
            status.setText("权限已授予。确认官方应用已暂停后，再开始诊断。");
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
                                    result.firmware(), result.hardware());
                    card = "设备卡片已记录电量，当前未保持连接。";
                } catch (Exception failure) {
                    card = "设备卡片未更新：" + failure.getMessage();
                }
                getSharedPreferences("diagnostic-result", 0).edit()
                        .putLong("verifiedAtMs", System.currentTimeMillis())
                        .putInt("batteryPercent", result.batteryPercent())
                        .putString("hardware", result.hardware()).putString("firmware", result.firmware()).commit();
                Log.i("OplusBandBridge", "DIAG_SUCCESS battery=" + result.batteryPercent());
                message = "诊断成功，连接已关闭。\n设备已确认鉴权。\n电量：" + result.batteryPercent()
                        + "%\n型号：" + result.hardware() + "\n固件：" + result.firmware()
                        + "\n" + card;
            } catch (SppDiagnosticClient.Failure failure) {
                Log.i("OplusBandBridge", "DIAG_FAILED " + failure.code);
                message = "诊断停止，连接已关闭。\n" + failure.code;
            } catch (Exception failure) {
                String category = failure.getClass().getSimpleName();
                Log.i("OplusBandBridge", "DIAG_FAILED " + category);
                message = "诊断失败，连接已关闭。\n错误类型：" + category + "\n未解绑、未清除凭据。";
            }
            try {
                io.github.miam1ku.mibandoplusbridge.service.BandLiveService.resumeAfterDiagnostic(this);
            } catch (RuntimeException unavailable) {
                message += "\n已保留登记；请返回配置页恢复连接。";
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
