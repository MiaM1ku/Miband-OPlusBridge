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
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit account association; native readback gates receipts, not user consent. */
public final class HealthAccountActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button confirm;
    private String candidate;
    private String candidateFull;
    private final Runnable expireCandidate = this::refresh;
    private final ContentObserver updates = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        BridgeScreen screen = BridgeScreen.attach(this, "健康账号", true);
        LinearLayout statusCard = screen.card();
        status = screen.bodyText(statusCard, "正在检查账号来源…");
        screen.caption(statusCard, "确认后将心率、血氧和压力导入当前 OHealth 账号。切换账号会暂停导入，不会自动转移旧记录。");
        screen.setLastChildMargin(statusCard, 0);
        confirm = screen.filled("确认当前账号", () -> {
            String shown = candidate;
            String selectedFull = candidateFull;
            if (shown == null || selectedFull == null) return;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("确认目标账号")
                    .setMessage("当前账号指纹：" + shown + "。确认后向该账号导入手环健康记录；不更改现有云同步设置。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认", (dialog, which) -> confirmAccount(selectedFull))
                    .show();
        });
        confirm.setEnabled(false);
        screen.outlined("打开 OHealth", () -> {
            Intent launcher = getPackageManager().getLaunchIntentForPackage("com.heytap.health");
            if (launcher == null) status.setText("OHealth 未安装或版本不可用。");
            else startActivity(launcher);
        });
        getContentResolver().registerContentObserver(HealthQueueProvider.URI, false, updates);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) refresh();
    }

    private void refresh() {
        if (worker.isShutdown()) return;
        worker.execute(() -> {
            String message;
            String fingerprint = null;
            String fullFingerprint = null;
            long remainingMs = 0;
            try {
                Bundle info = getContentResolver().call(HealthQueueProvider.URI, "status", null, null);
                if (info == null) throw new IllegalStateException();
                fingerprint = info.getString("proposedFingerprint");
                fullFingerprint = info.getString("proposedFingerprintFull");
                remainingMs = info.getLong("proposedRemainingMs", 0);
                String code = info.getString("status", "ACCOUNT_UNCONFIRMED");
                message = switch (code) {
                    case "ACCOUNT_CONFIRMED" -> "目标账号已确认。待原生读回确认：" + info.getInt("pendingCount")
                            + " 条。本地健康历史会保留。";
                    case "OHEALTH_ACCOUNT_CHANGED" -> "账号已变化，旧记录保留且暂停。请打开 OHealth 核对账号，再明确确认目标账号。";
                    default -> fingerprint == null
                            ? "尚未关联。请在 OHealth 中登录并返回；仅显示账号指纹，不读取密码。"
                            : "已取得当前账号候选，请确认。候选超时后确认按钮会关闭，需重新打开 OHealth。";
                };
                boolean pendingCandidate = fingerprint != null && !"ACCOUNT_CONFIRMED".equals(code);
                if (pendingCandidate) message += "\n当前待确认指纹：" + fingerprint;
            } catch (RuntimeException unavailable) {
                message = "账号队列不可用；不写入健康数据。";
                fingerprint = null;
                fullFingerprint = null;
                remainingMs = 0;
            }
            String result = message;
            String proposed = fingerprint;
            String proposedFull = fullFingerprint;
            long waitMs = remainingMs;
            boolean enableConfirm = fingerprint != null && !result.startsWith("目标账号已确认");
            main.post(() -> {
                if (isDestroyed()) return;
                main.removeCallbacks(expireCandidate);
                candidate = enableConfirm ? proposed : null;
                candidateFull = enableConfirm ? proposedFull : null;
                confirm.setEnabled(enableConfirm);
                status.setText(result);
                if (enableConfirm && waitMs > 0) main.postDelayed(expireCandidate, waitMs + 200);
            });
        });
    }

    private void confirmAccount(String expectedFingerprint) {
        if (worker.isShutdown()) return;
        worker.execute(() -> {
            String message;
            boolean confirmed = false;
            try {
                Bundle consent = new Bundle();
                consent.putBoolean("userConfirmed", true);
                consent.putString("expectedFingerprint", expectedFingerprint);
                Bundle outcome = getContentResolver().call(HealthQueueProvider.URI,
                        "confirmProposedAccount", null, consent);
                confirmed = outcome != null && "HEALTH_ACCOUNT_CONFIRMED".equals(outcome.getString("status"));
                message = confirmed ? "账号已关联，正在处理已保存的健康记录。" : "关联未完成，请重新核对目标账号。";
            } catch (RuntimeException rejected) {
                message = "HEALTH_ACCOUNT_PROPOSAL_EXPIRED".equals(rejected.getMessage())
                        ? "账号候选已过期，请打开 OHealth 后返回此页重新确认。旧数据仍保留。"
                        : "账号关联失败。请核对是否切换账号；旧数据仍保留。";
            }
            String result = message;
            main.post(() -> { if (!isDestroyed()) status.setText(result); });
            refresh();
        });
    }

    @Override protected void onDestroy() {
        getContentResolver().unregisterContentObserver(updates);
        main.removeCallbacks(expireCandidate);
        worker.shutdown();
        super.onDestroy();
    }
}
