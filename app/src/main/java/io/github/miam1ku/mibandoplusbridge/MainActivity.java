// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import io.github.miam1ku.mibandoplusbridge.data.BandCatalog;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.BindingStore;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecordStore;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.data.TransportObservation;
import io.github.miam1ku.mibandoplusbridge.integration.BandDetailsActivity;
import io.github.miam1ku.mibandoplusbridge.integration.CredentialProvider;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener;
import io.github.miam1ku.mibandoplusbridge.notify.SleepMusic;
import io.github.miam1ku.mibandoplusbridge.protocol.SppDiagnosticClient;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import io.github.miam1ku.mibandoplusbridge.service.OwnershipController;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen.StepState;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen.Tone;
import io.github.miam1ku.mibandoplusbridge.ui.Copy;
import io.github.miam1ku.mibandoplusbridge.ui.SetupProgress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Home screen. Reads top to bottom: band status with one primary action, first-run checklist,
 * things to fix now, features, help, management, advanced import and debug tools.
 * Device registration stays independent of connection ownership.
 */
public final class MainActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final int BLUETOOTH_PERMISSION = 1;
    private static final int CALL_PERMISSIONS = 2;
    enum Change { ADD, RECONNECT, REMOVE, RESTORE }

    private BridgeScreen screen;
    // status card
    private TextView deviceName;
    private TextView statusChip;
    private TextView connectionHint;
    private TextView metricsLine;
    private TextView healthLine;
    private TextView ownershipStatus;
    private Button homePrimary;
    // setup card
    private LinearLayout setupCard;
    private TextView setupTitle;
    private TextView stepRoot;
    private TextView stepImport;
    private TextView stepAdd;
    private TextView primaryHint;
    private Button openKsu;
    // notices
    private LinearLayout nearbyNotice;
    private LinearLayout notifyNotice;
    private LinearLayout healthAccessNotice;
    // management
    private TextView manageHeading;
    private LinearLayout manageCard;
    private Button remove;
    private Button restore;
    // advanced
    private LinearLayout bonded;
    private TextView status;
    private Button open;
    private Button advancedRestore;

    private String selectedAddress = "";
    private String databaseImportNote = "";
    private boolean hostSupported;
    private boolean callPermissionsAsked;
    private boolean changingOwnership;
    private boolean versionPromptShown;
    private boolean launchHostAfterOpen;
    private boolean profileAfterRestore;
    private boolean rootGranted;
    private boolean probingRoot;
    private Change pendingPermission;

    /** Open the profile window when this restore task succeeded and the user came in from the capture entry. */
    static boolean openProfileAfterRestore(Change change, boolean restored) {
        return restored && change == Change.RESTORE;
    }

    private final ContentObserver deviceObserver = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refreshDevice(); }
    };
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            refresh();
            refreshDevice();
        }
    };
    private final Runnable expireRefresh = this::refresh;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        screen = BridgeScreen.attach(this, "小米手环桥接", false);
        buildStatusCard();
        buildSetupCard();
        buildNotices();
        buildFeatures();
        buildHelp();
        buildManagement();
        buildAdvanced();
        getContentResolver().registerContentObserver(CredentialProvider.URI, false, observer);
        getContentResolver().registerContentObserver(DeviceCardProvider.URI, false, deviceObserver);
        getContentResolver().registerContentObserver(HealthQueueProvider.URI, false, deviceObserver);
        refreshDevice();
        requestRoot();
    }

    // ---- layout ----

    private void buildStatusCard() {
        LinearLayout card = screen.card();
        deviceName = screen.title(card, "小米手环");
        statusChip = screen.chip(card, "正在读取", Tone.NEUTRAL);
        connectionHint = screen.caption(card, "");
        metricsLine = screen.caption(card, "");
        healthLine = screen.caption(card, "");
        ownershipStatus = screen.bodyText(card, "");
        ownershipStatus.setTextSize(14);
        homePrimary = screen.filled(card, Copy.setupLabel(SetupProgress.Step.ROOT), this::runHomePrimary);
        connectionHint.setVisibility(View.GONE);
        metricsLine.setVisibility(View.GONE);
        healthLine.setVisibility(View.GONE);
    }

    private void buildSetupCard() {
        setupCard = screen.card();
        setupTitle = screen.overline(setupCard, "首次设置");
        stepRoot = screen.stepRow(setupCard, 1, "授权 Root", StepState.CURRENT);
        stepImport = screen.stepRow(setupCard, 2, "导入绑定", StepState.LATER);
        stepAdd = screen.stepRow(setupCard, 3, "添加到健康", StepState.LATER);
        primaryHint = screen.caption(setupCard, "");
        openKsu = screen.outlined(setupCard, "打开 KernelSU", this::openKernelSu);
        LinearLayout prereq = screen.expander(setupCard, "开始前准备", null);
        screen.caption(prereq, Copy.SETUP_PREREQ);
    }

    private void buildNotices() {
        nearbyNotice = screen.noticeCard(Tone.ATTENTION);
        screen.bodyText(nearbyNotice, "授权附近设备后即可连接手环。");
        screen.filled(nearbyNotice, "授权附近设备", () -> {
            pendingPermission = null;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION);
        });
        notifyNotice = screen.noticeCard(Tone.NEUTRAL);
        screen.bodyText(notifyNotice, "开启通知后，可在通知栏看到手环连接状态。");
        screen.outlined(notifyNotice, "开启通知", this::openAppNotificationSettings);
        healthAccessNotice = screen.noticeCard(Tone.ACTIVE);
        screen.bodyText(healthAccessNotice,
                "在系统设置里把 OPPO 健康的通知使用权关掉再打开一次，手机通知就能转到手环。");
        screen.outlined(healthAccessNotice, "打开通知使用权", this::openHealthListenerSettings);
        setShown(nearbyNotice, false);
        setShown(notifyNotice, false);
        setShown(healthAccessNotice, false);
    }

    private void buildFeatures() {
        screen.heading("功能");
        LinearLayout links = screen.card();
        screen.navRow(links, "设备详情", () -> startActivity(new Intent(this, BandDetailsActivity.class)));
        screen.navRow(links, "天气同步", () -> startActivity(new Intent(this, WeatherActivity.class)));
        screen.caption(links, "在控制中心添加「手环」磁贴，下拉或点按即可连接。");

        LinearLayout sleepCard = screen.card();
        MaterialSwitch sleepPause = screen.switchRow(sleepCard, "入睡后暂停音乐",
                "手环报告入睡时暂停正在播放的音乐，醒来后保持暂停。打开开关时如果已经入睡，会在下一次入睡时生效。"
                        + "通过睡眠记录报告入睡的手环，会在记录同步后暂停，通常需要几分钟。");
        final boolean[] writingSleepPause = {false};
        sleepPause.setChecked(SleepMusic.enabled(this));
        sleepPause.setOnCheckedChangeListener((button, checked) -> {
            if (writingSleepPause[0]) return;
            if (SleepMusic.setEnabled(this, checked)) {
                BandLiveService.sleepPauseChanged(this);
                return;
            }
            writingSleepPause[0] = true;
            button.setChecked(!checked);
            writingSleepPause[0] = false;
            ownershipStatus.setText("开关状态保存中断，再拨一次即可。");
        });
    }

    private void buildHelp() {
        screen.heading("帮助");
        LinearLayout help = screen.card();
        screen.navRow(help, "使用说明", this::showSetupGuide);
        screen.navRow(help, "系统权限设置", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        screen.navRow(help, "发送调试日志", this::shareDebugLog);
    }

    private void buildManagement() {
        manageHeading = screen.heading("管理");
        manageCard = screen.card();
        restore = screen.outlined(manageCard, "恢复小米运动健康", () -> changeOwnership(Change.RESTORE));
        remove = screen.dangerOutlined(manageCard, "从健康移除", () -> new MaterialAlertDialogBuilder(this)
                .setTitle("从健康移除这只手环？")
                .setMessage("桥接会断开连接，把手环交还小米运动健康。健康历史和加密绑定都会保留，之后可随时重新添加。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (dialog, which) -> changeOwnership(Change.REMOVE))
                .show());
        screen.caption(manageCard, "绑定和健康历史始终保存在本机。");
    }

    private void buildAdvanced() {
        LinearLayout advanced = screen.expander(screen.body, "高级设置", this::refreshBonded);
        LinearLayout importCard = screen.card(advanced);
        screen.overline(importCard, "绑定导入");
        screen.caption(importCard, "首页的「导入绑定」直接读取小米运动健康的本地数据库。"
                + "这里保留旧式导入窗口：选择已配对设备，在 120 秒内由官方应用重新读取绑定。");
        bonded = new LinearLayout(this);
        bonded.setOrientation(LinearLayout.VERTICAL);
        importCard.addView(bonded);
        String preselected = getIntent().getStringExtra("deviceAddress");
        if (preselected != null && preselected.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) {
            selectedAddress = preselected;
        }
        open = screen.filled(importCard, "开启导入窗口（120 秒）", () -> {
            if (!hostSupported) return;
            if (selectedAddress.isBlank()) {
                status.setText("先在上方选择一只已配对设备。");
                return;
            }
            Bundle selected = new Bundle();
            selected.putString("address", selectedAddress);
            action("openWindow", selected);
        });
        open.setEnabled(false);
        screen.outlined(importCard, "关闭导入和采集窗口", () -> action("closeWindow", null));
        screen.outlined(importCard, "打开小米运动健康", this::launchMiHealth);
        advancedRestore = screen.outlined(importCard, "恢复官方管理", () -> changeOwnership(Change.RESTORE));
        status = screen.bodyText(importCard, "正在检查小米运动健康…");
        status.setTextSize(14);

        LinearLayout debug = screen.card(advanced);
        screen.overline(debug, "调试");
        screen.navRow(debug, "协议测试", () -> startActivity(new Intent(this, LabActivity.class)));
        if (BuildConfig.DEBUG) {
            screen.navRow(debug, "独立连接诊断", () -> startActivity(new Intent(this, DiagnosticActivity.class)));
            screen.outlined(debug, "采集连接参数（120 秒）", this::startProfileCapture);
            screen.outlined(debug, "采集协议消息（120 秒）", () -> new MaterialAlertDialogBuilder(this)
                    .setTitle("采集所选手环的协议消息")
                    .setMessage("消息可能包含通知和健康数据。原始内容保存在本机私有目录，用于本次协议分析。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始采集", (dialog, which) -> action("openCapture", null))
                    .show());
        }
    }

    private static void setShown(LinearLayout cardInner, boolean shown) {
        View frame = (View) cardInner.getParent();
        (frame == null ? cardInner : frame).setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    // ---- lifecycle ----

    @Override protected void onResume() {
        super.onResume();
        refreshNotices();
        promptHostVersions();
        if (!rootGranted) requestRoot();
        else continueInit();
    }

    private void continueInit() {
        refreshOwnership();
        refreshDevice();
        refreshBonded();
        if (hostSupported) refresh();
        else worker.execute(() -> {
            boolean supported = HostIdentity.installed(this, HostIdentity.MI_PACKAGE);
            main.post(() -> {
                if (isDestroyed()) return;
                hostSupported = supported;
                open.setEnabled(supported && rootGranted);
                if (supported) refresh();
                else status.setText("安装小米运动健康并配对手环后，即可使用导入窗口。");
            });
        });
        ensureCallPermissions();
    }

    @Override protected void onDestroy() {
        getContentResolver().unregisterContentObserver(observer);
        getContentResolver().unregisterContentObserver(deviceObserver);
        main.removeCallbacks(expireRefresh);
        worker.shutdown();
        super.onDestroy();
    }

    // ---- notices ----

    private void refreshNotices() {
        if (nearbyNotice == null) return;
        setShown(nearbyNotice, !hasBluetoothPermission());
        setShown(notifyNotice, !getSystemService(NotificationManager.class).areNotificationsEnabled());
        LocalPrefs prefs = LocalPrefs.open(this, BandNotificationListener.SETTINGS);
        boolean healthAccess = prefs.getBoolean("healthSecure", false)
                && !prefs.getBoolean("healthApproved", true)
                && prefs.getBoolean("healthGrantFailed", false);
        setShown(healthAccessNotice, healthAccess);
    }

    private void openAppNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(intent);
        } catch (RuntimeException fallback) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void openHealthListenerSettings() {
        Intent detail = new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS");
        detail.putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                "com.heytap.health/com.heytap.health.watch.commonnotification.HeytapNotificationListenerService");
        try {
            startActivity(detail);
        } catch (RuntimeException failure) {
            try {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            } catch (RuntimeException ignored) {
                ownershipStatus.setText("在系统设置 → 通知 → 通知使用权中找到 OPPO 健康。");
            }
        }
    }

    // ---- help ----

    private void showSetupGuide() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("使用说明")
                .setMessage(Copy.GUIDE)
                .setPositiveButton("好的", null)
                .show();
    }

    private void shareDebugLog() {
        worker.execute(() -> {
            try {
                java.io.File file = io.github.miam1ku.mibandoplusbridge.data.SessionLog.export(this);
                Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                        "io.github.miam1ku.mibandoplusbridge.debuglog", file);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, "小米手环桥接调试日志");
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent qq = new Intent(send);
                qq.setPackage("com.tencent.mobileqq");
                main.post(() -> {
                    if (isDestroyed()) return;
                    if (qq.resolveActivity(getPackageManager()) != null) startActivity(qq);
                    else startActivity(Intent.createChooser(send, "发送调试日志"));
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (!isDestroyed()) android.widget.Toast.makeText(this,
                            "调试日志生成中断，稍后再试一次", android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void promptHostVersions() {
        if (isDestroyed() || versionPromptShown) return;
        ArrayList<HostVersionNotice.Installed> installed = new ArrayList<>();
        for (HostVersionNotice.Expectation expect : HostVersionNotice.EXPECTATIONS) {
            try {
                var info = getPackageManager().getPackageInfo(expect.packageName(), 0);
                installed.add(new HostVersionNotice.Installed(expect.packageName(), info.versionName,
                        info.getLongVersionCode(), true));
            } catch (PackageManager.NameNotFoundException missing) {
                installed.add(new HostVersionNotice.Installed(expect.packageName(), "", 0, false));
            }
        }
        String finger = HostVersionNotice.fingerprint(installed);
        if (finger.isEmpty()) return;
        SharedPreferences prefs = getSharedPreferences("host-version-notice", MODE_PRIVATE);
        if (finger.equals(prefs.getString("dismissed", ""))) return;
        versionPromptShown = true;
        new MaterialAlertDialogBuilder(this)
                .setTitle("建议使用实测版本")
                .setMessage(HostVersionNotice.message(installed))
                .setCancelable(true)
                .setPositiveButton("好的", (dialog, which) -> prefs.edit().putString("dismissed", finger).apply())
                .setOnCancelListener(dialog -> prefs.edit().putString("dismissed", finger).apply())
                .show();
    }

    // ---- root ----

    private void requestRoot() {
        if (worker.isShutdown() || probingRoot || changingOwnership) return;
        probingRoot = true;
        ownershipStatus.setText("正在检查 KernelSU 授权…");
        worker.execute(() -> {
            boolean granted = new OwnershipController(this).probeRoot();
            main.post(() -> {
                if (isDestroyed()) return;
                probingRoot = false;
                rootGranted = granted;
                ownershipStatus.setText(granted ? "KernelSU 已授权。" : Copy.setupHint(SetupProgress.Step.ROOT));
                updateControls();
                refreshDevice();
                if (granted) continueInit();
            });
        });
    }

    private void openKernelSu() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("me.weishu.kernelsu");
        if (launch == null) {
            ownershipStatus.setText("安装 KernelSU 管理器后，为本应用打开超级用户。");
            return;
        }
        startActivity(launch);
    }

    // ---- ownership ----

    private void refreshOwnership() {
        if (worker.isShutdown() || changingOwnership || !rootGranted) return;
        worker.execute(() -> {
            if (!isUnlocked()) return;
            OwnershipController owner = new OwnershipController(this);
            BandStateRepository repository = new BandStateRepository(this);
            String message;
            try {
                boolean registered = repository.isRegistered();
                boolean ready = owner.nativeReady();
                if (!registered) {
                    BandLiveService.stop(this);
                    if (!SppDiagnosticClient.stopAllAndWait()) {
                        throw new OwnershipController.Failure("DIAGNOSTIC_SOCKET_STILL_ACTIVE");
                    }
                    repository.disconnected();
                }
                if (registered && ready && hasBluetoothPermission()) {
                    BandLiveService.start(this);
                }
                message = !registered
                        ? (needsOfficialRestore()
                                ? "小米运动健康仍在暂停。可以添加设备，或在「管理」中恢复小米运动健康。"
                                : "")
                        : "NATIVE".equals(owner.mode())
                                ? "由桥接管理，连接状态以手环实际响应为准。"
                                : "当前由小米运动健康管理，点「立即同步」重新接管。";
            } catch (Exception failure) {
                message = repository.isRegistered()
                        ? "设备登记已保留。点「立即同步」再连接一次，或恢复官方管理。"
                        : "点「恢复小米运动健康」再试一次。";
            }
            String idle = message;
            main.post(() -> {
                if (isDestroyed()) return;
                ownershipStatus.setText(databaseImportNote.isEmpty() ? idle : databaseImportNote);
                updateControls();
            });
        });
    }

    private void changeOwnership(Change change) {
        if (worker.isShutdown() || changingOwnership) return;
        if (!rootGranted) {
            requestRoot();
            return;
        }
        if (!isUnlocked()) {
            ownershipStatus.setText("解锁手机后再管理设备。");
            return;
        }
        changingOwnership = true;
        updateControls();
        ownershipStatus.setText("正在处理，请稍候…");
        worker.execute(() -> {
            OwnershipController owner = new OwnershipController(this);
            BandStateRepository repository = new BandStateRepository(this);
            String message;
            boolean registrationSaved = false;
            boolean removalSaved = false;
            boolean officialRestored = false;
            try {
                if (change == Change.ADD || change == Change.RECONNECT) {
                    if (!isUnlocked()) throw new IllegalStateException("USER_LOCKED");
                    if (!owner.probeRoot()) throw new OwnershipController.Failure("ROOT_REQUIRED");
                    if (!hasBluetoothPermission()) throw new IllegalStateException("NEARBY_PERMISSION_REQUIRED");
                    if (change == Change.ADD) {
                        repository.registerDevice();
                        registrationSaved = true;
                    }
                    if (!repository.isRegistered()) throw new IllegalStateException("DEVICE_NOT_REGISTERED");
                    if (!owner.nativeReady()) owner.takeOver();
                    if (!repository.isRegistered() || !owner.nativeReady()) {
                        throw new IllegalStateException("NATIVE_OWNERSHIP_REQUIRED");
                    }
                    BandLiveService.start(this);
                    BandLiveService.requestSync(this);
                    message = "设备已登记，正在连接。";
                } else {
                    if (change == Change.REMOVE) {
                        repository.unregisterDevice();
                        removalSaved = true;
                        main.post(this::refreshDevice);
                    }
                    BandLiveService.stop(this);
                    if (!SppDiagnosticClient.stopAllAndWait()) {
                        throw new OwnershipController.Failure("DIAGNOSTIC_SOCKET_STILL_ACTIVE");
                    }
                    repository.disconnected();
                    owner.restoreOfficial();
                    officialRestored = change == Change.RESTORE;
                    message = change == Change.REMOVE
                            ? "已移除，小米运动健康已恢复。绑定和健康历史都已保留。"
                            : "小米运动健康已恢复。绑定和健康历史都已保留。";
                }
            } catch (Exception failure) {
                String hint = Copy.ownershipFailure(failureCode(failure));
                if (removalSaved || (change == Change.RESTORE && !repository.isRegistered())) {
                    message = "已移除，小米运动健康待恢复。" + hint;
                } else if (change == Change.REMOVE) {
                    message = "设备仍在登记中，再点一次「从健康移除」。";
                } else if (registrationSaved || (change == Change.RECONNECT && repository.isRegistered())) {
                    message = "设备登记已保留，连接稍后继续。" + hint;
                } else {
                    message = hint;
                }
            }
            String visible = message;
            boolean followProfile = openProfileAfterRestore(change, officialRestored);
            main.post(() -> {
                if (isDestroyed()) return;
                changingOwnership = false;
                ownershipStatus.setText(visible);
                updateControls();
                refreshDevice();
                if (profileAfterRestore && followProfile) {
                    profileAfterRestore = false;
                    startProfileWindow();
                }
            });
        });
    }

    private static String failureCode(Exception failure) {
        return failure instanceof OwnershipController.Failure
                ? ((OwnershipController.Failure) failure).code : failure.getMessage();
    }

    private void requestNative(Change change) {
        if (changingOwnership) return;
        if (!rootGranted) {
            requestRoot();
            return;
        }
        if (!isUnlocked()) {
            ownershipStatus.setText("解锁手机后再管理设备。");
            return;
        }
        if (!hasBluetoothPermission()) {
            pendingPermission = change;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION);
            return;
        }
        if (change == Change.RECONNECT) {
            changeOwnership(change);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("添加到健康并接管？")
                .setMessage("小米运动健康会暂停，由桥接接管手环。开始前请结束表盘安装、OTA 升级和 NFC 操作。"
                        + "绑定和健康历史都会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("添加并接管", (dialog, which) -> changeOwnership(change))
                .show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CALL_PERMISSIONS) {
            BandLiveService.refreshNotificationSettings(this);
            return;
        }
        if (requestCode != BLUETOOTH_PERMISSION) return;
        Change requested = pendingPermission;
        pendingPermission = null;
        refreshNotices();
        refreshDevice();
        refreshBonded();
        if (hasBluetoothPermission()) {
            if (requested != null) requestNative(requested);
            else refreshOwnership();
        } else {
            ownershipStatus.setText("授权附近设备后即可添加和连接，已有登记保持原样。");
        }
    }

    private void ensureCallPermissions() {
        if (callPermissionsAsked || !isUnlocked() || !new BandStateRepository(this).isRegistered()) return;
        boolean phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        boolean hangup = checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED;
        if (phone && hangup) return;
        callPermissionsAsked = true;
        if (!phone && !hangup) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS},
                    CALL_PERMISSIONS);
        } else if (!phone) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, CALL_PERMISSIONS);
        } else {
            requestPermissions(new String[]{Manifest.permission.ANSWER_PHONE_CALLS}, CALL_PERMISSIONS);
        }
    }

    private boolean isUnlocked() {
        return getSystemService(UserManager.class).isUserUnlocked()
                && !getSystemService(KeyguardManager.class).isDeviceLocked();
    }

    private boolean hasBluetoothPermission() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needsOfficialRestore() {
        var ownership = LocalPrefs.open(this, "ownership");
        return ownership.getBoolean("ownsDisable", false) || ownership.getBoolean("transitionPending", false);
    }

    // ---- controls ----

    private void updateControls() {
        boolean registered = new BandStateRepository(this).isRegistered();
        SetupProgress progress = readProgress();
        if (setupCard != null) {
            setShown(setupCard, progress.showChecklist());
            if (progress.showChecklist()) bindChecklist(progress);
        }
        if (homePrimary != null) {
            homePrimary.setText(progress.primaryLabel());
            homePrimary.setEnabled(!changingOwnership && !probingRoot);
        }
        if (openKsu != null) {
            openKsu.setVisibility(rootGranted ? View.GONE : View.VISIBLE);
            openKsu.setEnabled(!probingRoot);
        }
        boolean officialPaused = needsOfficialRestore();
        boolean manage = rootGranted && (registered || officialPaused);
        manageHeading.setVisibility(manage ? View.VISIBLE : View.GONE);
        setShown(manageCard, manage);
        remove.setVisibility(registered ? View.VISIBLE : View.GONE);
        remove.setEnabled(!changingOwnership);
        restore.setVisibility(officialPaused ? View.VISIBLE : View.GONE);
        restore.setEnabled(!changingOwnership);
        advancedRestore.setEnabled(!changingOwnership && rootGranted);
    }

    private SetupProgress readProgress() {
        boolean registered = new BandStateRepository(this).isRegistered();
        boolean hasBinding = false;
        String model = "";
        try {
            JSONObject binding = new BindingStore(this).read();
            hasBinding = binding != null;
            if (binding != null) model = binding.optString("model", "");
        } catch (Exception ignored) { }
        boolean hasProfile = hasBinding && TransportObservation.supportsLive(
                TransportObservation.liveProfile(this, model), model);
        boolean nativeOwned = false;
        try {
            nativeOwned = new OwnershipController(this).nativeReady();
        } catch (RuntimeException ignored) { }
        boolean accountConfirmed = false;
        try {
            accountConfirmed = new HealthRecordStore(this).confirmedAccountHash() != null;
        } catch (Exception ignored) { }
        return new SetupProgress(rootGranted, hasBinding, hasProfile, registered, nativeOwned, accountConfirmed);
    }

    private void bindChecklist(SetupProgress progress) {
        setupTitle.setText("首次设置 · 第 " + progress.visibleIndex() + "/3 步");
        int current = progress.visibleIndex();
        screen.setStep(stepRoot, 1, "授权 Root", stepState(1, current));
        screen.setStep(stepImport, 2, "导入绑定", stepState(2, current));
        screen.setStep(stepAdd, 3, "添加到健康", stepState(3, current));
        String hint = progress.primaryHint();
        primaryHint.setText(hint);
        primaryHint.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static StepState stepState(int index, int current) {
        if (index < current) return StepState.DONE;
        return index == current ? StepState.CURRENT : StepState.LATER;
    }

    private void runHomePrimary() {
        if (changingOwnership || probingRoot) return;
        switch (readProgress().current()) {
            case ROOT -> requestRoot();
            case IMPORT -> startBindingImport();
            case PROFILE -> startProfileCapture();
            case ADD -> requestNative(Change.ADD);
            case DONE -> requestNative(Change.RECONNECT);
        }
    }

    // ---- status card ----

    private void refreshDevice() {
        if (isDestroyed() || worker.isShutdown()) return;
        updateControls();
        refreshNotices();
        if (!isUnlocked()) {
            deviceName.setText("小米手环");
            screen.setChip(statusChip, "解锁后显示", Tone.NEUTRAL);
            setOptionalCaption(connectionHint, "解锁手机后显示手环信息。");
            metricsLine.setVisibility(View.GONE);
            healthLine.setVisibility(View.GONE);
            return;
        }
        var state = LocalPrefs.open(this, "band-state");
        if (new BandStateRepository(this).isRegistered()) {
            deviceName.setText(safeIdentity(
                    BandCatalog.displayName(state.getString("modelId", ""), state.getString("name", "")),
                    "已登记手环"));
            boolean connected = state.getBoolean("connected", false);
            if (connected) {
                screen.setChip(statusChip, "已连接", Tone.POSITIVE);
                setOptionalCaption(connectionHint, "");
            } else {
                screen.setChip(statusChip, "等待连接", Tone.NEUTRAL);
                setOptionalCaption(connectionHint, Copy.offlineReason(liveStatus()));
            }
            setOptionalCaption(metricsLine, metricsText(state));
            setOptionalCaption(healthLine, healthExtras());
            return;
        }
        deviceName.setText("小米手环");
        screen.setChip(statusChip, "待添加", Tone.NEUTRAL);
        setOptionalCaption(connectionHint, "");
        healthLine.setVisibility(View.GONE);
        try {
            JSONObject binding = new BindingStore(this).read();
            setOptionalCaption(metricsLine, binding == null
                    ? "导入绑定后即可添加到健康。" : "绑定已导入，点「添加到健康」即可连接。");
        } catch (Exception unavailable) {
            setOptionalCaption(metricsLine, "解锁手机后显示绑定信息，已有数据都在。");
        }
    }

    private static String metricsText(LocalPrefs state) {
        int battery = state.getInt("battery", -1);
        long lastSync = state.getLong("lastSyncAtMs", 0);
        String batteryText = battery >= 0 && battery <= 100 ? "电量 " + battery + "%" : "";
        String syncText = lastSync > 0
                ? "同步于 " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,
                        java.text.DateFormat.SHORT).format(new java.util.Date(lastSync))
                : "";
        if (batteryText.isEmpty()) return syncText;
        if (syncText.isEmpty()) return batteryText;
        return batteryText + " · " + syncText;
    }

    private String liveStatus() {
        java.io.File file = new java.io.File(getNoBackupFilesDir(), "live-status.txt");
        if (file.isFile() && file.length() > 0 && file.length() < 256) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                byte[] bytes = new byte[(int) file.length()];
                int n = in.read(bytes);
                if (n > 0) return new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) { }
        }
        return "";
    }

    private String healthExtras() {
        var live = getSharedPreferences("live-service", MODE_PRIVATE);
        String host = Copy.healthHost(live.getString("healthHostStatus", ""));
        String collection = Copy.healthCollection(live.getString("healthCollectionStatus", ""));
        if (collection.isEmpty()) return host;
        return host.isEmpty() ? collection : host + "\n" + collection;
    }

    private static void setOptionalCaption(TextView view, String value) {
        boolean show = value != null && !value.isBlank();
        view.setVisibility(show ? View.VISIBLE : View.GONE);
        view.setText(show ? value : "");
    }

    private static String safeIdentity(String value, String fallback) {
        String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
        return clean.isEmpty() || clean.length() > 80 ? fallback : clean;
    }

    // ---- credential provider ----

    private void action(String method, Bundle arguments) {
        if (isDestroyed() || worker.isShutdown()) return;
        worker.execute(() -> {
            try {
                Bundle response = getContentResolver().call(CredentialProvider.URI, method, null, arguments);
                main.post(() -> {
                    if (isDestroyed()) return;
                    show(response);
                    if ("openWindow".equals(method) || "openDiagnostics".equals(method) || "openCapture".equals(method)) {
                        main.removeCallbacks(expireRefresh);
                        main.postDelayed(expireRefresh, 120_100);
                    }
                });
            } catch (RuntimeException rejected) {
                main.post(() -> {
                    if (!isDestroyed()) status.setText("解锁手机、核对所选设备后再点一次。");
                });
            }
        });
    }

    private void refresh() { action("status", null); }

    private void show(Bundle response) {
        if (response == null) {
            status.setText("导入服务正在启动，稍后再点一次。");
            return;
        }
        String code = response.getString("status", "CREDENTIAL_STORAGE_FAILED");
        String text = Copy.importResult(code, response.getString("model", ""),
                response.getString("productId", ""), response.getString("firmware", ""));
        String observation = response.getString("transportObservation", "");
        if (!observation.isBlank()) text += "\n连接参数（仅观察）：\n" + observation;
        if (response.getBoolean("diagnosticOpen", false)) text += "\n连接参数采集窗口开启中。";
        if (response.getBoolean("captureOpen", false)) text += "\n协议消息采集窗口开启中。";
        String captureFile = response.getString("captureFile", "");
        if (!captureFile.isBlank()) text += "\n采集记录：" + response.getInt("captureRecords")
                + "，字节：" + response.getLong("captureBytes") + "\n文件：" + captureFile;
        String captureError = response.getString("captureError", "");
        if (!captureError.isBlank()) text += "\n采集中断：" + captureError;
        status.setText(text);
        if ("IMPORT_WINDOW_OPEN".equals(code) || "DIAGNOSTIC_WINDOW_OPEN".equals(code)) {
            ownershipStatus.setText(text);
            if (launchHostAfterOpen) {
                launchHostAfterOpen = false;
                if (hostSupported) launchMiHealth();
            }
        }
    }

    private void launchMiHealth() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(HostIdentity.MI_PACKAGE);
        if (launch == null) {
            String text = "在桌面打开小米运动健康，确认它已安装并启用。";
            status.setText(text);
            ownershipStatus.setText(text);
            return;
        }
        startActivity(launch);
    }

    // ---- bonded devices (advanced) ----

    private void refreshBonded() {
        if (bonded == null) return;
        bonded.removeAllViews();
        if (!hasBluetoothPermission()) {
            screen.caption(bonded, "授权附近设备后，这里会列出已配对设备。");
            return;
        }
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null) {
            screen.caption(bonded, "这台手机需要蓝牙才能连接手环。");
            return;
        }
        java.util.Set<BluetoothDevice> devices;
        try {
            devices = adapter.getBondedDevices();
        } catch (SecurityException denied) {
            screen.caption(bonded, "授权附近设备后，这里会列出已配对设备。");
            return;
        }
        if (devices == null || devices.isEmpty()) {
            screen.caption(bonded, "在系统蓝牙设置中配对手环后，它会出现在这里。");
            return;
        }
        screen.caption(bonded, "选择已配对设备");
        java.util.List<BluetoothDevice> listed = sortedBonded(devices);
        preferXiaomiBand(listed);
        for (BluetoothDevice device : listed) {
            String mac = device.getAddress() == null ? "" : device.getAddress().toUpperCase(java.util.Locale.ROOT);
            String name = BandCatalog.displayName(null, device.getName());
            boolean chosen = !mac.isBlank() && mac.equals(selectedAddress);
            Button choice = screen.outlined(bonded, (chosen ? "✓ " : "") + name, () -> {
                selectedAddress = mac;
                refreshBonded();
            });
            choice.setEnabled(!chosen);
        }
    }

    private static java.util.List<BluetoothDevice> sortedBonded(java.util.Set<BluetoothDevice> devices) {
        ArrayList<BluetoothDevice> listed = new ArrayList<>(devices);
        listed.sort(Comparator.comparing((BluetoothDevice device) -> !BandCatalog.looksLikeBand(device.getName()))
                .thenComparing(device -> BandCatalog.displayName(null, device.getName()),
                        String.CASE_INSENSITIVE_ORDER));
        return listed;
    }

    private void preferXiaomiBand(java.util.List<BluetoothDevice> devices) {
        if (!selectedAddress.isBlank()) return;
        String onlyBand = null;
        int bands = 0;
        for (BluetoothDevice device : devices) {
            if (!BandCatalog.looksLikeBand(device.getName())) continue;
            String mac = device.getAddress();
            if (mac == null || mac.isBlank()) continue;
            bands++;
            onlyBand = mac.toUpperCase(java.util.Locale.ROOT);
        }
        if (bands == 1) {
            selectedAddress = onlyBand;
            return;
        }
        if (devices.size() != 1) return;
        String only = devices.get(0).getAddress();
        if (only != null) selectedAddress = only.toUpperCase(java.util.Locale.ROOT);
    }

    // ---- database import ----

    private void startBindingImport() {
        if (changingOwnership) return;
        if (!rootGranted) {
            requestRoot();
            return;
        }
        if (!isUnlocked()) {
            ownershipStatus.setText("解锁手机后再导入绑定。");
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("导入绑定？")
                .setMessage("用 root 读取小米运动健康本地数据库中的手环密钥。全程在本机完成，密钥保存在本应用的私有目录里。")
                .setNegativeButton("取消", null)
                .setPositiveButton("读取", (dialog, which) -> importFromDatabase(selectedAddress))
                .show();
    }

    private void importFromDatabase(String address) {
        if (worker.isShutdown()) return;
        databaseImportNote = "";
        ownershipStatus.setText("正在读取本地数据库…");
        worker.execute(() -> {
            Bundle selected = new Bundle();
            if (address != null && !address.isBlank()) selected.putString("address", address);
            Bundle response;
            try {
                response = getContentResolver().call(CredentialProvider.URI, "importFromDatabase", null, selected);
            } catch (RuntimeException rejected) {
                response = null;
            }
            Bundle shown = response;
            main.post(() -> {
                if (isDestroyed()) return;
                if (shown != null && "MULTIPLE_BANDS".equals(shown.getString("status"))) {
                    chooseDatabaseBand(shown.getStringArray("choices"));
                    return;
                }
                show(shown);
                databaseImportNote = status.getText().toString();
                ownershipStatus.setText(databaseImportNote);
                updateControls();
                refreshDevice();
            });
        });
    }

    private void chooseDatabaseBand(String[] choices) {
        if (choices == null || choices.length == 0) {
            ownershipStatus.setText(Copy.importResult("NO_BAND_KEY", "", "", ""));
            return;
        }
        String[] labels = new String[choices.length];
        String[] addresses = new String[choices.length];
        for (int index = 0; index < choices.length; index++) {
            String[] parts = choices[index].split("\t", 3);
            addresses[index] = parts.length > 0 ? parts[0] : "";
            String model = parts.length > 1 ? parts[1] : "";
            String name = parts.length > 2 ? parts[2] : "";
            labels[index] = BandCatalog.displayName(model, name);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("选择要导入的手环")
                .setItems(labels, (dialog, which) -> importFromDatabase(addresses[which]))
                .show();
    }

    // ---- connection profile capture (debug) ----

    private void startProfileCapture() {
        if (changingOwnership) return;
        if (!rootGranted) {
            requestRoot();
            return;
        }
        if (!isUnlocked()) {
            ownershipStatus.setText("解锁手机后再采集连接参数。");
            return;
        }
        if (!hostSupported) {
            ownershipStatus.setText("安装小米运动健康并配对手环后，即可采集连接参数。");
            return;
        }
        if (needsOfficialRestore()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("先恢复官方管理")
                    .setMessage("采集连接参数时要用到小米运动健康。先恢复官方管理，随后会打开采集窗口。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("恢复", (dialog, which) -> {
                        profileAfterRestore = true;
                        changeOwnership(Change.RESTORE);
                    })
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("采集连接参数？")
                .setMessage("将开启 120 秒采集窗口，随后打开小米运动健康。点开这只手环，让它重新连上。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始采集", (dialog, which) -> startProfileWindow())
                .show();
    }

    private void startProfileWindow() {
        launchHostAfterOpen = true;
        action("openDiagnostics", null);
    }
}
