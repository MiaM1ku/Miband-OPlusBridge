// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;

import android.content.ClipData;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import io.github.miam1ku.mibandoplusbridge.data.BandCatalog;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.DeviceLogPublisher;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;
import io.github.miam1ku.mibandoplusbridge.protocol.XiaomiMassReceive;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen;
import io.github.miam1ku.mibandoplusbridge.ui.Copy;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pulls the band's complete device-log bundle with a blocking progress modal. */
public final class DeviceLogActivity extends AppCompatActivity {
    private static final long REFRESH_MS = 2_000;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "OplusBandDeviceLog");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private BridgeScreen screen;
    private TextView deviceTitle;
    private TextView deviceChip;
    private TextView deviceDetail;
    private TextView status;
    private MaterialButton pull;
    private MaterialButton reconnect;
    private MaterialButton share;
    private android.app.Dialog progressDialog;
    private LinearProgressIndicator progressBar;
    private TextView progressStatus;
    private File lastBundle;
    private boolean pulling;
    private boolean determinateStarted;
    private long progressNullCalls;
    private long progressValueCalls;
    private int lastLoggedProgress = -1;
    private int lastProgressPart = -1;

    private final Runnable refreshTick = this::pollState;
    private final Runnable progressPoll = this::pollTransferProgress;
    private final ContentObserver stateObserver = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            refreshDevice();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        screen = BridgeScreen.attach(this, "拉取设备日志", true);

        android.widget.LinearLayout device = screen.section("设备");
        deviceTitle = screen.title(device, "正在读取手环…");
        deviceChip = screen.chip(device, "检查连接", BridgeScreen.Tone.NEUTRAL);
        deviceDetail = screen.caption(device, "");

        android.widget.LinearLayout explanation = screen.section("日志内容");
        screen.bodyText(explanation,
                "请求手环导出完整 log 目录，经反向 MASS 通道逐片接收，并保存到手机下载目录。"
                        + "zip、tar 和 tar.gz 包会同时展开。");
        screen.caption(explanation,
                "设备日志由手环生成，可能包含运行记录和健康相关信息；分享前请检查内容。");

        android.widget.LinearLayout operation = screen.section("操作");
        pull = screen.filled(operation, "开始拉取", this::pull);
        reconnect = screen.outlined(operation, "尝试重新连接", this::reconnect);
        share = screen.outlined(operation, "再次分享上次日志", this::shareLast);
        share.setVisibility(android.view.View.GONE);
        status = screen.bodyText(operation, "有已连接的手环后即可开始。");

        getContentResolver().registerContentObserver(DeviceCardProvider.URI, false, stateObserver);
        refreshDevice();
        scheduleRefresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshDevice();
    }

    private void refreshDevice() {
        if (isDestroyed()) return;
        UserManager users = getSystemService(UserManager.class);
        if (users != null && !users.isUserUnlocked()) {
            deviceTitle.setText("解锁后读取手环");
            screen.setChip(deviceChip, "已锁定", BridgeScreen.Tone.NEUTRAL);
            deviceDetail.setText("设备日志需要先解锁手机。");
            pull.setEnabled(false);
            reconnect.setVisibility(android.view.View.GONE);
            return;
        }
        BandStateRepository repository = new BandStateRepository(this);
        boolean registered;
        try {
            registered = repository.isRegistered();
        } catch (RuntimeException unavailable) {
            registered = false;
        }
        if (!registered) {
            deviceTitle.setText("等待已连接的手环");
            screen.setChip(deviceChip, "等待连接", BridgeScreen.Tone.NEUTRAL);
            deviceDetail.setText("先在首页导入绑定并添加到健康，再回来拉取设备日志。");
            pull.setEnabled(false);
            reconnect.setVisibility(android.view.View.GONE);
            return;
        }

        LocalPrefs device = LocalPrefs.open(this, "band-state");
        String name = BandCatalog.displayName(
                device.getString("modelId", ""), device.getString("name", ""));
        boolean connected = device.getBoolean("connected", false);
        deviceTitle.setText(name.isBlank() ? "已登记手环" : name);
        screen.setChip(deviceChip, connected ? "已连接" : "等待连接",
                connected ? BridgeScreen.Tone.POSITIVE : BridgeScreen.Tone.NEUTRAL);
        StringBuilder detail = new StringBuilder();
        String model = device.getString("modelId", "");
        String firmware = device.getString("verifiedFirmware",
                device.getString("firmware", ""));
        if (!model.isBlank()) detail.append("型号 ").append(model);
        if (!firmware.isBlank()) {
            if (detail.length() > 0) detail.append("\n");
            detail.append("固件 ").append(firmware);
        }
        int battery = device.getInt("battery", -1);
        if (battery >= 0 && battery <= 100) {
            if (detail.length() > 0) detail.append("\n");
            detail.append("电量 ").append(battery).append('%');
        }
        long lastSync = device.getLong("lastSyncAtMs", 0);
        if (lastSync > 0) {
            if (detail.length() > 0) detail.append("\n");
            detail.append("上次同步 ")
                    .append(DateFormat.getMediumDateFormat(this).format(new java.util.Date(lastSync)))
                    .append(' ')
                    .append(DateFormat.getTimeFormat(this).format(new java.util.Date(lastSync)));
        }
        deviceDetail.setText(detail.length() == 0 ? "尚无验证过的设备信息。" : detail.toString());
        pull.setEnabled(connected && !pulling);
        reconnect.setVisibility(connected ? android.view.View.GONE : android.view.View.VISIBLE);
        if (!connected && !pulling && lastBundle == null) {
            String reason = Copy.offlineReason(readLiveStatus());
            status.setText(reason.isBlank()
                    ? "手环连接建立后即可拉取。可点「尝试重新连接」，或在首页点「立即同步」。"
                    : reason);
        }
    }

    private void scheduleRefresh() {
        main.removeCallbacks(refreshTick);
        main.postDelayed(refreshTick, REFRESH_MS);
    }

    private void pollState() {
        if (isDestroyed()) return;
        refreshDevice();
        scheduleRefresh();
    }

    private void reconnect() {
        String result = BandLiveService.requestSync(this);
        String message = switch (result) {
            case "ACCEPTED" -> "正在重新连接手环，连接后即可开始拉取。";
            case "DEVICE_NOT_REGISTERED" -> "先连接手环，再回来拉取设备日志。";
            case "NATIVE_OWNERSHIP_REQUIRED" -> "当前由官方应用管理。请在首页点「立即同步」重新接管。";
            case "OPEN_CONFIG_REQUIRED" -> "请打开小米手环桥接首页完成配置后再试。";
            default -> "回首页查看手环状态，再重新发起连接。";
        };
        status.setText(message);
        refreshDevice();
    }

    private void pull() {
        if (pulling) return;
        LocalPrefs device = LocalPrefs.open(this, "band-state");
        if (!device.getBoolean("registered", false) || !device.getBoolean("connected", false)) {
            status.setText("先让手环连上，再开始拉取。");
            refreshDevice();
            return;
        }
        pulling = true;
        pull.setEnabled(false);
        pull.setText("正在拉取…");
        reconnect.setVisibility(android.view.View.GONE);
        status.setText("正在请求手环准备完整 log 目录…");
        showProgressModal();
        setProgress(null);
        lastProgressPart = -1;
        main.removeCallbacks(progressPoll);
        main.post(progressPoll);
        SessionLog.start(this);
        BandLiveService.requestDeviceLogExport(this, progress -> main.post(() -> {
            applyTransferProgress(progress);
        })).whenComplete((received, requestFailure) -> {
            if (requestFailure != null) {
                showFailure(requestFailure);
                return;
            }
            main.post(() -> {
                if (isDestroyed()) return;
                setProgress(100);
                if (progressStatus != null) {
                    progressStatus.setText("接收完成，正在保存、展开并生成可浏览 ZIP…");
                }
                status.setText("手环日志接收完成，正在保存到手机下载目录…");
                worker.execute(() -> {
                    try {
                        DeviceLogPublisher.Result published =
                                DeviceLogPublisher.publish(this, received.file());
                        main.post(() -> {
                            if (isDestroyed()) return;
                            pulling = false;
                        main.removeCallbacks(progressPoll);
                            File shared = published.browseFile() == null
                                    ? received.file() : published.browseFile();
                            lastBundle = shared;
                            setProgress(100);
                            pull.setText("重新拉取");
                            share.setVisibility(android.view.View.VISIBLE);
                            String unpacked = published.extractedFiles() == 0
                                    ? "压缩包已保存。"
                                    : "已展开 " + published.extractedFiles() + " 个文件（"
                                            + size(published.extractedBytes()) + "），并生成可浏览 ZIP。";
                            dismissProgressModal();
                            status.setText("已拉取 " + received.fileName() + "（" + size(received.bytes())
                                    + "）。\n保存到 "
                                    + published.relativeDirectory().replaceFirst("^Download/", "下载/")
                                    + "\n" + unpacked);
                            refreshDevice();
                            share(shared);
                        });
                    } catch (Exception failure) {
                        main.post(() -> {
                            if (isDestroyed()) return;
                            pulling = false;
                            main.removeCallbacks(progressPoll);
                            dismissProgressModal();
                            pull.setText("重新拉取");
                            lastBundle = received.file();
                            share.setVisibility(android.view.View.VISIBLE);
                            status.setText("手环日志已收到，但保存到下载目录时中断："
                                    + safeFailure(failure) + "。可先通过下方按钮分享。");
                            refreshDevice();
                        });
                    }
                });
            });
        });
    }

    private void showFailure(Throwable failure) {
        main.post(() -> {
            if (isDestroyed()) return;
            pulling = false;
            main.removeCallbacks(progressPoll);
            pull.setText("重试拉取");
            dismissProgressModal();
            status.setText(deviceLogFailure(failure));
            refreshDevice();
        });
    }

    private void pollTransferProgress() {
        if (isDestroyed() || !pulling) return;
        applyTransferProgress(BandLiveService.deviceLogProgressSnapshot());
        main.postDelayed(progressPoll, 200);
    }

    private void applyTransferProgress(XiaomiMassReceive.Progress progress) {
        if (isDestroyed() || !pulling || progress == null || progress.totalParts() <= 0
                || progress.currentPart() <= lastProgressPart) return;
        lastProgressPart = progress.currentPart();
        int percent = Math.min(100, progress.currentPart() * 100 / progress.totalParts());
        SessionLog.line(this, "UI_LOG_POLL part=" + progress.currentPart()
                + "/" + progress.totalParts() + " percent=" + percent
                + " view=" + (progressBar == null ? "null" : System.identityHashCode(progressBar)));
        if (progressBar != null && !determinateStarted) {
            SessionLog.line(this, "UI_LOG_APPLY firstValue percent=" + percent
                    + " oldIndeterminate=" + progressBar.isIndeterminate());
            configureDeterminateWave();
            progressBar.setIndeterminate(false);
            progressBar.setMax(100);
            progressBar.setProgressCompat(0, false);
            determinateStarted = true;
            SessionLog.line(this, "UI_LOG_APPLY determinateStarted id="
                    + System.identityHashCode(progressBar));
        }
        if (progressBar != null) {
            progressBar.setProgressCompat(percent, false);
        }
        String text = "正在接收 " + progress.fileName() + "\n"
                + progress.currentPart() + "/" + progress.totalParts()
                + " 片 · " + percent + "% · " + size(progress.bytes());
        if (progressStatus != null) progressStatus.setText(text);
        status.setText(text);
    }

    private void showProgressModal() {
        dismissProgressModal();
        android.view.View content = getLayoutInflater().inflate(
                R.layout.dialog_device_log_progress, null, false);
        progressStatus = content.findViewById(R.id.device_log_progress_status);
        progressBar = content.findViewById(R.id.device_log_progress);
        configureRequestWave();
        progressBar.setIndeterminate(true);
        progressBar.setMax(100);
        determinateStarted = false;
        progressNullCalls = 0;
        progressValueCalls = 0;
        lastLoggedProgress = -1;
        SessionLog.line(this, "UI_LOG_MODAL open build=" + BuildConfig.VERSION_CODE
                + " view=" + progressBar.getClass().getName()
                + " id=" + System.identityHashCode(progressBar));

        progressDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("正在拉取设备日志")
                .setView(content)
                .setCancelable(false)
                .create();
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.setOnKeyListener((dialog, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
        progressDialog.show();
    }

    private void dismissProgressModal() {
        android.app.Dialog dialog = progressDialog;
        progressDialog = null;
        progressBar = null;
        progressStatus = null;
        determinateStarted = false;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void setProgress(Integer percent) {
        if (percent == null) {
            progressNullCalls++;
            SessionLog.line(this, "UI_LOG_ACTIVITY null calls=" + progressNullCalls
                    + " viewId=" + (progressBar == null ? 0 : System.identityHashCode(progressBar)));
            determinateStarted = false;
            if (progressBar != null) {
                configureRequestWave();
                progressBar.setIndeterminate(true);
            }
            return;
        }
        progressValueCalls++;
        if (progressBar == null) return;
        int value = Math.max(0, Math.min(100, percent));
        if (!determinateStarted) {
            SessionLog.line(this, "UI_LOG_ACTIVITY firstValue percent=" + percent
                    + " calls=" + progressValueCalls
                    + " oldIndeterminate=" + progressBar.isIndeterminate());
            configureDeterminateWave();
            progressBar.setIndeterminate(false);
            progressBar.setMax(100);
            determinateStarted = true;
            SessionLog.line(this, "UI_LOG_ACTIVITY determinateStarted "
                    + " view=" + progressBar.getClass().getName()
                    + " id=" + System.identityHashCode(progressBar));
        }
        progressBar.setProgressCompat(value, false);
        if (value == 100 || lastLoggedProgress < 0 || value >= lastLoggedProgress + 10) {
            lastLoggedProgress = value;
            SessionLog.line(this, "UI_LOG_ACTIVITY value=" + value
                    + " calls=" + progressValueCalls
                    + " viewId=" + System.identityHashCode(progressBar));
        }
    }

    private void configureRequestWave() {
        progressBar.setWaveSpeed(0);
    }

    private void configureDeterminateWave() {
        progressBar.setWaveAmplitude(getResources().getDimensionPixelSize(
                R.dimen.device_log_progress_wave_amplitude));
        progressBar.setWavelengthDeterminate(getResources().getDimensionPixelSize(
                R.dimen.device_log_progress_wavelength_determinate));
        progressBar.setWaveSpeed(getResources().getDimensionPixelSize(
                R.dimen.device_log_progress_wave_speed));
    }

    private static String deviceLogFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String code = cause.getMessage() == null ? cause.getClass().getSimpleName()
                : SessionLog.redact(cause.getMessage());
        return switch (code) {
            case "DEVICE_LOG_NO_DATA" -> "手环暂无可导出的日志。稍后再试一次。";
            case "DEVICE_LOG_DEVICE_BUSY" -> "手环正在准备日志，请稍后再试。";
            case "DEVICE_LOG_DEVICE_REJECTED" -> "手环暂缓了日志导出请求。稍后再试一次。";
            case "DEVICE_LOG_DIRECT_UPLOAD_UNSUPPORTED" -> "此固件使用直传模式，可先用「发送调试日志」。";
            case "DEVICE_LOG_RECEIVE_FAILED", "MASS_RECEIVE_CANCELLED" ->
                    "接收中途停止。确认手环保持连接后再试一次。";
            case "LIVE_SESSION_REQUIRED", "DEVICE_LOG_SESSION_UNAVAILABLE" ->
                    "手环连接正在重建，等状态显示「已连接」后再试。";
            default -> "设备日志拉取中断：" + code + "。确认手环连接后重试。";
        };
    }

    private void shareLast() {
        if (lastBundle != null && lastBundle.isFile()) share(lastBundle);
    }

    private void share(File bundle) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    "io.github.miam1ku.mibandoplusbridge.debuglog", bundle);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(logMime(bundle));
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "小米手环桥接设备日志");
            send.setClipData(ClipData.newRawUri("device-log", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "分享设备日志"));
        } catch (RuntimeException failure) {
            status.setText("日志已保存在应用缓存，可稍后点「再次分享上次日志」。");
        }
    }

    private static String logMime(File file) {
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            byte[] head = new byte[4];
            int read = input.read(head);
            if (read >= 2 && (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b) {
                return "application/gzip";
            }
            if (read >= 2 && head[0] == 'P' && head[1] == 'K') return "application/zip";
        } catch (Exception ignored) { }
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".zip")) return "application/zip";
        if (name.endsWith(".tar")) return "application/x-tar";
        if (name.endsWith(".gz") || name.endsWith(".tgz")) return "application/gzip";
        return "application/octet-stream";
    }

    private String readLiveStatus() {
        File file = new File(getNoBackupFilesDir(), "live-status.txt");
        if (!file.isFile() || file.length() <= 0 || file.length() > 2_048) return "";
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception unavailable) {
            return "";
        }
    }

    private static String safeFailure(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : SessionLog.redact(message);
    }

    private static String size(long bytes) {
        if (bytes < 1_024) return bytes + " B";
        if (bytes < 1_024 * 1_024) return (bytes + 1_023) / 1_024 + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override protected void onDestroy() {
        dismissProgressModal();
        getContentResolver().unregisterContentObserver(stateObserver);
        main.removeCallbacks(refreshTick);
        main.removeCallbacks(progressPoll);
        worker.shutdown();
        super.onDestroy();
    }
}
