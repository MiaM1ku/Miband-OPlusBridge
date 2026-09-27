// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.protocol.SppDiagnosticClient;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps one authenticated SPP session for the whole NATIVE ownership period and reconnects after the link drops. */
public final class BandLiveService extends Service {
    private static final String ACTION_SYNC = "io.github.miam1ku.mibandoplusbridge.SYNC";
    private static volatile BandLiveService instance;
    private static volatile boolean diagnosticPaused;
    private volatile java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(0);
    private final java.util.concurrent.CountDownLatch destroyed = new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.ScheduledExecutorService coordinator =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "OplusBandCoordinator"));
    private volatile io.github.miam1ku.mibandoplusbridge.protocol.LiveCommandQueue commands;
    private LiveHistorySync historySync;
    private volatile boolean syncRequested;
    private long nextBatteryAt;
    private long nextHealthAt;
    private long nextWeatherAt;
    private WeatherSync weatherSync;
    private HealthReplay healthReplay;
    private volatile long sessionEpoch;
    private static final java.util.concurrent.atomic.AtomicLong SESSION_IDS = new java.util.concurrent.atomic.AtomicLong();
    private io.github.miam1ku.mibandoplusbridge.notify.PhoneCallMonitor calls;
    private static final String HEALTH_PACKAGE = "com.heytap.health";
    private static final android.content.ComponentName HEALTH_SERVICE = new android.content.ComponentName(
            HEALTH_PACKAGE, "com.heytap.health.rpc.host.HealthRpcMsgService");
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile boolean healthBound;
    private final android.content.ServiceConnection healthConnection = new android.content.ServiceConnection() {
        @Override public void onServiceConnected(android.content.ComponentName name, IBinder binder) {
            healthHostStatus("READY");
        }
        @Override public void onServiceDisconnected(android.content.ComponentName name) {
            healthHostStatus("UNAVAILABLE");
        }
        @Override public void onBindingDied(android.content.ComponentName name) {
            unbindHealthHost();
            healthHostStatus("UNAVAILABLE");
        }
        @Override public void onNullBinding(android.content.ComponentName name) {
            unbindHealthHost();
            healthHostStatus("UNAVAILABLE");
        }
    };
    private static final String CHANNEL = "band-live";
    private static final int NOTICE = 7;
    private static final long INITIAL_BACKOFF_MS = 3_000;
    private static final long MAX_BACKOFF_MS = 30_000;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "OplusBandLive");
        thread.setDaemon(false);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object stopLock = new Object();
    private volatile boolean stopRequested;
    private volatile boolean retryNow;
    private volatile SppDiagnosticClient client;
    private boolean receiverRegistered;
    private final BroadcastReceiver bluetoothEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                SppDiagnosticClient active = client;
                if (active != null) active.close();
                // The system sends this broadcast. Clear the card immediately; the socket
                // thread may still be blocked in a read when the adapter is powered off.
                try { new BandStateRepository(context).markSessionClosed(); }
                catch (RuntimeException ignored) { }
            } else if (state == BluetoothAdapter.STATE_ON) {
                synchronized (stopLock) {
                    retryNow = true;
                    stopLock.notifyAll();
                }
            }
        }
    };

    public static void start(Context context) {
        if (diagnosticPaused) throw new IllegalStateException("DIAGNOSTIC_ACTIVE");
        if (!context.getSystemService(android.os.UserManager.class).isUserUnlocked()) {
            throw new IllegalStateException("USER_LOCKED");
        }
        if (!new BandStateRepository(context).isRegistered()) {
            throw new IllegalStateException("DEVICE_NOT_REGISTERED");
        }
        if (!new OwnershipController(context).nativeReady()) {
            throw new IllegalStateException("NATIVE_OWNERSHIP_REQUIRED");
        }
        context.startForegroundService(new Intent(context, BandLiveService.class));
    }

    public static void stop(Context context) {
        BandLiveService live = instance;
        if (live != null) live.requestStop();
        context.stopService(new Intent(context, BandLiveService.class));
    }

    public static void pauseForDiagnostic(Context context) throws InterruptedException {
        diagnosticPaused = true;
        BandLiveService live = instance;
        stop(context);
        if (live != null && (!live.destroyed.await(10, TimeUnit.SECONDS)
                || !live.stopped.await(10, TimeUnit.SECONDS))) {
            throw new IllegalStateException("LIVE_SESSION_STILL_ACTIVE");
        }
        if (!SppDiagnosticClient.stopAllAndWait()) throw new IllegalStateException("DIAGNOSTIC_SOCKET_STILL_ACTIVE");
    }

    public static void resumeAfterDiagnostic(Context context) {
        diagnosticPaused = false;
        if (new BandStateRepository(context).isRegistered() && new OwnershipController(context).nativeReady()) start(context);
    }

    /** Accepts a request without waiting for root checks, Bluetooth, or a Binder transaction. */
    public static String requestSync(Context context) {
        if (diagnosticPaused) return "OPEN_CONFIG_REQUIRED";
        if (!context.getSystemService(android.os.UserManager.class).isUserUnlocked()) return "OPEN_CONFIG_REQUIRED";
        if (!new BandStateRepository(context).isRegistered()) return "DEVICE_NOT_REGISTERED";
        var ownership = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(context, "ownership");
        if (!"NATIVE".equals(ownership.getString("mode", "OFFICIAL"))
                || !ownership.getBoolean("ownsDisable", false)
                || ownership.getBoolean("officialRestored", false)) return "NATIVE_OWNERSHIP_REQUIRED";
        BandLiveService live = instance;
        if (live != null && !live.stopRequested) {
            live.syncRequested = true;
            try {
                live.coordinator.execute(live::tick);
                return "ACCEPTED";
            } catch (java.util.concurrent.RejectedExecutionException stopping) {
                return "OPEN_CONFIG_REQUIRED";
            }
        }
        try {
            context.startForegroundService(new Intent(context, BandLiveService.class).setAction(ACTION_SYNC));
            return "ACCEPTED";
        } catch (RuntimeException backgroundRejected) {
            return "OPEN_CONFIG_REQUIRED";
        }
    }

    public static java.util.concurrent.CompletionStage<Void> requestWeather(Context context,
            io.github.miam1ku.mibandoplusbridge.protocol.BandWeatherEncoder.Sample sample, boolean inspect) {
        BandLiveService live = instance;
        if (live == null || live.stopRequested || live.commands == null
                || !new BandStateRepository(context).isRegistered()) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("LIVE_SESSION_REQUIRED"));
        }
        return inspect ? live.weatherSync.inspectCities(sample) : live.weatherSync.send(sample);
    }

    public static void refreshWeather(Context context) {
        BandLiveService live = instance;
        if (live != null && !live.stopRequested && new BandStateRepository(context).isRegistered()) {
            live.weatherSync.refreshAndSend();
        }
    }

    public static boolean notificationSessionReady(Context context) {
        BandLiveService live = instance;
        if (live == null || live.stopRequested || live.commands == null
                || !new BandStateRepository(context).isRegistered()) return false;
        var owner = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(context, "ownership");
        return "NATIVE".equals(owner.getString("mode", "OFFICIAL"))
                && owner.getBoolean("ownsDisable", false) && !owner.getBoolean("officialRestored", false);
    }

    public static long notificationSessionId() {
        BandLiveService live = instance;
        return live == null || live.stopRequested || live.commands == null ? 0 : live.sessionEpoch;
    }

    public static int notificationPayloadLimit() {
        BandLiveService live = instance;
        SppDiagnosticClient active = live == null ? null : live.client;
        return active == null || live.commands == null ? 0 : active.notificationPayloadLimit();
    }

    public static java.util.concurrent.CompletionStage<Void> sendSessionCommand(
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        BandLiveService live = instance;
        var queue = live == null ? null : live.commands;
        if (queue == null || command == null || !command.isInitialized()) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        return queue.send(command);
    }


    public static java.util.concurrent.CompletionStage<Void> sendNotification(Context context,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        BandLiveService live = instance;
        var queue = live == null ? null : live.commands;
        if (queue == null || !notificationSessionReady(context) || command == null || command.getType() != 7
                || (command.getSubtype() != 0 && command.getSubtype() != 1) || !command.hasNotification()) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        var notification = command.getNotification();
        boolean call = command.getSubtype() == 0 && notification.hasNotification2()
                && notification.getNotification2().getNotification3().getIsCall();
        boolean clearCall = command.getSubtype() == 1 && notification.hasNotificationDismiss()
                && notification.getNotificationDismiss().getNotificationIdCount() == 1
                && "phone".equals(notification.getNotificationDismiss().getNotificationId(0).getPackage())
                && notification.getNotificationDismiss().getNotificationId(0).getId() == 0;
        var settings = context.getSharedPreferences("notification-settings", MODE_PRIVATE);
        if (call && (!settings.getBoolean("callsEnabled", false)
                || context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("CALL_PERMISSION_REQUIRED"));
        }
        if (!call && !clearCall) {
            var manager = context.getSystemService(NotificationManager.class);
            if (!settings.getBoolean("enabled", false) || !manager.isNotificationListenerAccessGranted(
                    new android.content.ComponentName(context, io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.class))) {
                return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("NOTIFICATION_ACCESS_REQUIRED"));
            }
        }
        try {
            var fitted = io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.fitToPayload(command,
                    notificationPayloadLimit());
            return queue.send(fitted);
        } catch (IllegalArgumentException tooLarge) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("NOTIFICATION_IDENTITY_TOO_LARGE"));
        }
    }

    /** OHealth already decided this notification may reach the band. */
    public static java.util.concurrent.CompletionStage<Void> forwardHostNotification(Context context,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        BandLiveService live = instance;
        var queue = live == null ? null : live.commands;
        if (queue == null || !notificationSessionReady(context) || command == null || command.getType() != 7
                || (command.getSubtype() != 0 && command.getSubtype() != 1) || !command.hasNotification()) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        try {
            var fitted = io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.fitToPayload(
                    command, notificationPayloadLimit());
            android.util.Log.i("OplusBandBridge", "NOTIFY_OUT type=" + fitted.getType()
                    + " subtype=" + fitted.getSubtype() + " bytes=" + fitted.getSerializedSize()
                    + " limit=" + notificationPayloadLimit());
            return queue.send(fitted);
        } catch (IllegalArgumentException tooLarge) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_IDENTITY_TOO_LARGE"));
        }
    }

    public static void cancelNotifications(Context context) {
        BandLiveService live = instance;
        var queue = live == null ? null : live.commands;
        if (queue != null) queue.cancelNotifications(new IllegalStateException("NOTIFICATION_CANCELLED"));
    }

    public static void cancelCall(Context context) {
        BandLiveService live = instance;
        var queue = live == null ? null : live.commands;
        if (queue != null) queue.cancelCall(new IllegalStateException("CALL_CANCELLED"));
    }

    public static void refreshNotificationSettings(Context context) {
        io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.connectionChanged();
        BandLiveService live = instance;
        if (live != null && !live.stopRequested) live.calls.refresh();
    }

    @Override public void onCreate() {
        super.onCreate();
        weatherSync = new WeatherSync(this, () -> commands, coordinator);
        healthReplay = new HealthReplay(this, this::healthCollectionStatus, () -> {
            try {
                coordinator.execute(() -> { if (!stopRequested && historySync != null) historySync.resume(); });
            } catch (java.util.concurrent.RejectedExecutionException stopping) { }
        });
        calls = new io.github.miam1ku.mibandoplusbridge.notify.PhoneCallMonitor(this, coordinator);
        instance = this;
        coordinator.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.MINUTES);
        try {
            registerReceiver(bluetoothEvents,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (RuntimeException ignored) { }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (diagnosticPaused || !getSystemService(android.os.UserManager.class).isUserUnlocked()
                || !new BandStateRepository(this).isRegistered()) {
            requestStop();
            stopSelf();
            return START_NOT_STICKY;
        }
        synchronized (stopLock) {
            stopRequested = false;
            stopLock.notifyAll();
        }
        if (intent != null && ACTION_SYNC.equals(intent.getAction())) syncRequested = true;
        if (running.compareAndSet(false, true)) {
            stopped = new java.util.concurrent.CountDownLatch(1);
            show("正在连接手环", "鉴权成功前不会显示已连接");
            worker.execute(this::supervise);
        }
        return START_STICKY;
    }

    private void requestStop() {
        synchronized (stopLock) {
            stopRequested = true;
            stopLock.notifyAll();
        }
        SppDiagnosticClient active = client;
        if (active != null) active.close();
    }

    private void supervise() {
        BandStateRepository repository = new BandStateRepository(this);
        long backoff = INITIAL_BACKOFF_MS;
        String status = "STARTING";
        try {
            try { repository.markSessionClosed(); } catch (RuntimeException ignored) { }
            while (!stopRequested) {
                OwnershipController owner = new OwnershipController(this);
                if (!repository.isRegistered() || !owner.nativeReady()) {
                    status = "REGISTERED_NATIVE_DEVICE_REQUIRED registered="
                            + repository.isRegistered() + " mode=" + owner.mode()
                            + " ready=" + owner.nativeReady();
                    android.util.Log.i("OplusBandBridge", status);
                    show("连接已停止", "请在配置应用添加设备并接管");
                    break;
                }
                bindHealthHost();
                AtomicInteger stored = new AtomicInteger();
                AtomicBoolean authenticated = new AtomicBoolean();
                long epoch = SESSION_IDS.incrementAndGet();
                sessionEpoch = epoch;
                SppDiagnosticClient active = new SppDiagnosticClient(this, code -> {
                    if (stopRequested || epoch != sessionEpoch) return;
                    if ("HISTORY_FRAGMENT_RECEIVED".equals(code)) {
                        coordinator.execute(() -> {
                            if (epoch == sessionEpoch && historySync != null) historySync.progress();
                        });
                    } else if ("HISTORY_FILE_ARCHIVED".equals(code)) {
                        healthReplay.request();
                        show("手环已连接", "已保存 " + stored.incrementAndGet() + " 个健康文件");
                    } else if ("HISTORY_FORMAT_UNSUPPORTED".equals(code)) {
                        healthCollectionStatus(code);
                    } else if ("HISTORY_FILE_REJECTED".equals(code) || "HISTORY_STORAGE_FAILED".equals(code)
                            || "HISTORY_CONFIRMATION_PENDING".equals(code)) {
                        coordinator.execute(() -> {
                            if (epoch == sessionEpoch && historySync != null) historySync.rejected(code);
                        });
                        healthCollectionStatus(code);
                    }
                });
                client = active;
                try {
                    active.runLive(result -> {
                        authenticated.set(true);
                        try {
                            repository.recordVerifiedDevice(result.batteryPercent(), result.batteryState(), true,
                                    result.firmware(), result.hardware());
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure.getMessage(), failure);
                        }
                        if (!repository.isRegistered() || stopRequested) return;
                        show("手环已连接", "电量 " + result.batteryPercent() + "%，正在同步活动");
                    }, queue -> coordinator.execute(() -> {
                        if (client != active || stopRequested) {
                            queue.close(new IllegalStateException("SESSION_CLOSED"));
                            return;
                        }
                        commands = queue;
                        calls.connected();
                        io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.connectionChanged();
                        historySync = new LiveHistorySync(queue, coordinator, () -> {
                            try { repository.recordSyncCompleted(); }
                            catch (RuntimeException unavailable) { healthCollectionStatus("HEALTH_STORAGE_UNAVAILABLE"); }
                        }, this::healthCollectionStatus, healthReplay::hasCapacity);
                        long now = System.nanoTime();
                        nextBatteryAt = now + TimeUnit.MINUTES.toNanos(3);
                        nextHealthAt = now + TimeUnit.MINUTES.toNanos(5);
                        nextWeatherAt = now + TimeUnit.MINUTES.toNanos(30);
                        weatherSync.refreshAndSend();
                        syncRequested = false;
                        healthReplay.request();
                        queue.send(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command.newBuilder()
                                .setType(8).setSubtype(45).build());
                        historySync.request(true);
                    }), fileId -> coordinator.execute(() -> {
                        if (client == active && !stopRequested && historySync != null) historySync.saved(fileId);
                    }), command -> {
                        if (command.getType() == 7) {
                            android.util.Log.i("OplusBandBridge", "NOTIFY_IN subtype=" + command.getSubtype()
                                    + " status=" + command.getStatus());
                            if (command.getSubtype() == 16 && command.hasNotification()
                                    && command.getNotification().hasNotificationIconQuery()) {
                                String pkg = command.getNotification().getNotificationIconQuery().getPackage();
                                if (pkg != null && !pkg.isBlank()) {
                                    sendSessionCommand(io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand
                                            .iconQueryReply(pkg));
                                }
                            }
                            if (calls != null) calls.onBandCommand(command);
                        }
                        weatherSync.onCommand(command);
                        if (command.getType() == 8 && command.getSubtype() == 47 && command.hasHealth()
                                && command.getHealth().hasRealTimeStats()) {
                            int steps = command.getHealth().getRealTimeStats().getSteps();
                            coordinator.execute(() -> {
                                if (epoch != sessionEpoch || steps < 0 || steps > 200_000) return;
                                try {
                                    repository.recordDailySteps(steps, System.currentTimeMillis(), true);
                                } catch (RuntimeException ignored) { }
                            });
                        }
                        if (command.getType() == 8 && command.hasStatus() && command.getStatus() != 0) {
                            coordinator.execute(() -> {
                                if (epoch == sessionEpoch && historySync != null) historySync.onCommand(command);
                            });
                        }
                    });
                    status = "CLOSED files=" + stored.get();
                } catch (Exception failure) {
                    status = failure instanceof SppDiagnosticClient.Failure typed
                            ? typed.code : failure.getClass().getSimpleName();
                    if (!retryable(status)) {
                        show("连接已结束", status);
                        break;
                    }
                    show("连接已断开", status + "，即将重连");
                } finally {
                    client = null;
                    commands = null;
                    try {
                        coordinator.execute(() -> {
                            if (epoch == sessionEpoch && commands == null && historySync != null) {
                                historySync.close();
                                historySync = null;
                            }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException stopping) { }
                    weatherSync.onDisconnected();
                    calls.disconnected();
                    io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.connectionChanged();
                    try { repository.markSessionClosed(); } catch (RuntimeException ignored) { }
                }
                if (authenticated.get()) backoff = INITIAL_BACKOFF_MS;
                if (stopRequested || !retryable(status)) break;
                show("正在重连", "等待 " + (backoff / 1000) + " 秒");
                if (!waitForRetry(backoff)) break;
                if (!authenticated.get()) backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        } finally {
            writeStatus(stopRequested ? "STOPPED" : status);
            running.set(false);
            stopped.countDown();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void bindHealthHost() {
        if (healthBound || stopRequested) return;
        try {
            if (!io.github.miam1ku.mibandoplusbridge.HostIdentity.installed(this, HEALTH_PACKAGE)) {
                healthHostStatus("UNAVAILABLE");
                return;
            }
            var pkg = getPackageManager().getPackageInfo(HEALTH_PACKAGE, 0);
            var service = getPackageManager().getServiceInfo(HEALTH_SERVICE, 0);
            if (pkg.getLongVersionCode() != 6093700L || !service.enabled || !service.exported
                    || !service.applicationInfo.enabled || !HEALTH_PACKAGE.equals(service.processName)
                    || (service.permission != null && !service.permission.isBlank())) {
                healthHostStatus("UNAVAILABLE");
                return;
            }
            main.post(() -> {
                if (healthBound || stopRequested || !new BandStateRepository(this).isRegistered()) return;
                try {
                    healthBound = bindService(new Intent().setComponent(HEALTH_SERVICE), healthConnection, BIND_AUTO_CREATE);
                    if (!healthBound) healthHostStatus("UNAVAILABLE");
                } catch (RuntimeException unavailable) {
                    healthHostStatus("UNAVAILABLE");
                }
            });
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            healthHostStatus("UNAVAILABLE");
        }
    }

    private void unbindHealthHost() {
        if (healthBound) {
            unbindService(healthConnection);
            healthBound = false;
        }
    }

    private void healthHostStatus(String status) {
        getSharedPreferences("live-service", MODE_PRIVATE).edit().putString("healthHostStatus", status).apply();
        getContentResolver().notifyChange(io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider.URI, null);
    }

    private void healthCollectionStatus(String status) {
        getSharedPreferences("live-service", MODE_PRIVATE).edit().putString("healthCollectionStatus", status).apply();
        getContentResolver().notifyChange(io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider.URI, null);
    }

    private void tick() {
        var queue = commands;
        if (stopRequested || queue == null || historySync == null) return;
        if (!new BandStateRepository(this).isRegistered()) {
            requestStop();
            return;
        }
        long now = System.nanoTime();
        if (now >= nextBatteryAt) {
            nextBatteryAt = now + TimeUnit.MINUTES.toNanos(3);
            queue.request(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command
                    .newBuilder().setType(2).setSubtype(1).build(), 2, 1)
                    .whenComplete((ignored, error) -> {
                        if (error != null) writeStatus("BATTERY_REFRESH_FAILED");
                    });
        }
        if (syncRequested || now >= nextHealthAt) {
            boolean historical = syncRequested;
            syncRequested = false;
            nextHealthAt = now + TimeUnit.MINUTES.toNanos(5);
            historySync.request(historical);
            if (historical) {
                bindHealthHost();
                nextWeatherAt = now + TimeUnit.MINUTES.toNanos(30);
                weatherSync.refreshAndSend();
            }
        }
        if (now >= nextWeatherAt) {
            nextWeatherAt = now + TimeUnit.MINUTES.toNanos(30);
            weatherSync.refreshAndSend();
        }
    }

    private static boolean retryable(String code) {
        if (code.indexOf('_') < 0) return true;
        return switch (code) {
            case "BLUETOOTH_DISABLED", "DIAGNOSTIC_TIMEOUT", "CANCELLED", "CONNECTION_ALREADY_ACTIVE",
                    "USER_LOCKED", "EMPTY_SOCKET_READ", "CLOSED" -> true;
            default -> code.startsWith("CLOSED");
        };
    }

    private boolean waitForRetry(long delayMs) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        synchronized (stopLock) {
            while (!stopRequested && !retryNow) {
                long leftMs = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
                if (leftMs <= 0) return true;
                try {
                    stopLock.wait(leftMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (stopRequested) return false;
            retryNow = false;
            return true;
        }
    }

    private void writeStatus(String status) {
        try (FileWriter writer = new FileWriter(new File(getNoBackupFilesDir(), "live-status.txt"))) {
            writer.write(status);
        } catch (IOException ignored) { }
    }

    private void show(String title, String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "手环连接", NotificationManager.IMPORTANCE_LOW));
        Notification notice = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .build();
        startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        unbindHealthHost();
        if (receiverRegistered) {
            unregisterReceiver(bluetoothEvents);
            receiverRegistered = false;
        }
        requestStop();
        calls.close();
        weatherSync.close();
        healthReplay.close();
        coordinator.execute(() -> {
            if (historySync != null) {
                historySync.close();
                historySync = null;
            }
        });
        coordinator.shutdown();
        worker.shutdownNow();
        super.onDestroy();
        destroyed.countDown();
    }
}
