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
    private volatile boolean realtimeStatsStopPending;
    private long nextBatteryAt;
    private long nextHealthAt;
    private long nextWeatherAt;
    /** Battery and a today-file list. Unchanged files and weather stay off the radio so the ACL can sniff. */
    private static final long IDLE_POLL_MINUTES = 30;
    /**
     * 8/45 starts the band's live-stats stream. The band measures heart rate while that stream runs,
     * so it is a one-shot like Gadgetbridge's heart-rate test: 8/46 stops it after the first snapshot.
     */
    private static final long REALTIME_STATS_TIMEOUT_SECONDS = 30;
    private static final int REALTIME_STATS_START = 45;
    private static final int REALTIME_STATS_STOP = 46;
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
            reviveHealth();
        }
        @Override public void onNullBinding(android.content.ComponentName name) {
            unbindHealthHost();
            healthHostStatus("UNAVAILABLE");
        }
    };
    private static final String CHANNEL = "band-live";
    private static final io.github.miam1ku.mibandoplusbridge.notify.NotifyDedupe NOTIFICATIONS =
            new io.github.miam1ku.mibandoplusbridge.notify.NotifyDedupe();
    private static final io.github.miam1ku.mibandoplusbridge.notify.NotifyReplay HELD_NOTIFICATIONS =
            new io.github.miam1ku.mibandoplusbridge.notify.NotifyReplay();
    private static final int NOTICE = 7;
    private static final long INITIAL_BACKOFF_MS = 3_000;
    private static final long MAX_BACKOFF_MS = 30_000;
    /** Config errors stay in this process. Exiting is what makes health launch the app. */
    private static final long QUIET_BACKOFF_MS = 120_000;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "OplusBandLive");
        thread.setDaemon(false);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object stopLock = new Object();
    private volatile boolean stopRequested;
    private volatile long lastDndSentNanos;
    private int lastDndFilter = Integer.MIN_VALUE;
    private long dndSyncAtNanos;
    private final Object dndSyncLock = new Object();
    private final Runnable dndRulesAgain = this::sendDndRulesAgain;
    private volatile boolean dndPushPhone;
    private volatile boolean dndBandKnown;
    private volatile boolean dndBandOn;
    private final java.util.concurrent.atomic.AtomicBoolean dndAdopted = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command lastMusic;
    private final io.github.miam1ku.mibandoplusbridge.notify.SleepMusic sleepMusic =
            new io.github.miam1ku.mibandoplusbridge.notify.SleepMusic();
    private volatile boolean sleepPauseOn;
    private volatile long sleepArmedAtMs = Long.MAX_VALUE;
    private long nextSleepFileAt;
    private volatile boolean retryNow;
    private String noticeTitle = "";
    private volatile SppDiagnosticClient client;
    private boolean receiverRegistered;
    private boolean dndReceiverRegistered;
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
    private final BroadcastReceiver dndEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(intent.getAction())) syncDnd();
        }
    };
    private final android.database.ContentObserver zenMode = new android.database.ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { syncDnd(); }
    };

    public static void start(Context context) {
        admit(context);
        launch(context, false);
    }

    /** Package update runs this off the main thread and can wait for the root start. */
    public static void startBlocking(Context context) {
        admit(context);
        launch(context, true);
    }

    /** Health card reads and notification IPC. Does nothing while this process already hosts the service. */
    public static void ensureProcess(Context context) {
        BandLiveService live = instance;
        if (live != null && !live.stopRequested) return;
        try {
            start(context);
        } catch (RuntimeException ignored) { }
    }
    /** True while this process hosts the service and a stop has not been requested. */
    public static boolean isRunning() {
        BandLiveService live = instance;
        return live != null && !live.stopRequested;
    }

    public static boolean mayWake(Context context) {
        if (context == null || diagnosticPaused) return false;
        if (!context.getSystemService(android.os.UserManager.class).isUserUnlocked()) return false;
        if (!new BandStateRepository(context).isRegistered()) return false;
        return new OwnershipController(context).nativeReady();
    }

    private static void admit(Context context) {
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
    }

    private static void launch(Context context, boolean waitForRoot) {
        try {
            context.startForegroundService(new Intent(context, BandLiveService.class));
        } catch (RuntimeException backgroundRejected) {
            if (waitForRoot) HostKeepAlive.startBridge(context);
            else HostKeepAlive.startBridgeAsync(context);
        }
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
            synchronized (live.stopLock) {
                live.retryNow = true;
                live.stopLock.notifyAll();
            }
            try {
                live.coordinator.execute(live::tick);
                return "ACCEPTED";
            } catch (java.util.concurrent.RejectedExecutionException stopping) {
                return "OPEN_CONFIG_REQUIRED";
            }
        }
        try {
            start(context);
            return "ACCEPTED";
        } catch (RuntimeException denied) {
            return "OPEN_CONFIG_REQUIRED";
        }
    }

    /** Switch-on starts a new baseline. A sleep already underway does not pause. */
    public static void sleepPauseChanged(Context context) {
        BandLiveService live = instance;
        if (live == null || live.stopRequested) return;
        live.sleepPauseOn = io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.enabled(context);
        live.sleepMusic.reset();
        live.sleepArmedAtMs = io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.armedAt(context);
        if (live.sleepPauseOn) {
            live.nextSleepFileAt = System.nanoTime();
            live.requestSleepState();
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
    private static String sessionReason(Context context) {
        BandLiveService live = instance;
        if (live == null || live.stopRequested || live.commands == null) return "service";
        boolean registered = new BandStateRepository(context).isRegistered();
        var owner = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(context, "ownership");
        return "session registered=" + registered
                + " mode=" + owner.getString("mode", "OFFICIAL")
                + " ownsDisable=" + owner.getBoolean("ownsDisable", false)
                + " restored=" + owner.getBoolean("officialRestored", false);
    }

    public static boolean callsOwned() {
        BandLiveService live = instance;
        return live != null && live.calls != null && live.calls.ownsCalls();
    }

    public static void noteCallAnswered() {
        BandLiveService live = instance;
        if (live != null && live.calls != null) live.calls.noteAnswered();
    }

    private void logFeatures() {
        var settings = getSharedPreferences("notification-settings", MODE_PRIVATE);
        boolean access = io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.accessGranted(this);
        boolean up = io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.listenerConnected();
        String enabled = android.provider.Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean health = enabled != null && enabled.contains(
                "com.heytap.health/com.heytap.health.watch.commonnotification.HeytapNotificationListenerService");
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "features local="
                + (up ? "up" : access ? "granted" : "absent")
                + " healthListener=" + health
                + " notify=" + settings.getBoolean("enabled", true)
                + " packages=" + settings.getStringSet("packages", java.util.Set.of()).size()
                + " calls=" + settings.getBoolean("callsEnabled", false)
                + " callOwner=" + (calls != null && calls.ownsCalls())
                + " music=" + io.github.miam1ku.mibandoplusbridge.notify.PhoneMusic.attached());
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "hosts "
                + hostVersion("com.coloros.alarmclock")
                + " " + hostVersion("com.heytap.health")
                + " " + hostVersion("com.heytap.mydevices")
                + " " + hostVersion(getPackageName()));
    }

    private String hostVersion(String pkg) {
        String name = pkg == null ? "" : pkg.substring(pkg.lastIndexOf('.') + 1);
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
            return name + "=" + info.versionName + "/" + info.getLongVersionCode();
        } catch (android.content.pm.PackageManager.NameNotFoundException missing) {
            return name + "=absent";
        }
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
        if (command.getType() == 18 && command.getSubtype() == 1) lastMusic = command;
        return queue.send(command);
    }


    public static java.util.concurrent.CompletionStage<Void> sendNotification(Context context,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        if (!notificationCommand(command)) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=command");
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        BandLiveService live = instance;
        var queue = live == null ? null : live.commands;
        var notification = command.getNotification();
        boolean call = command.getSubtype() == 0 && notification.hasNotification2()
                && io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.isCall(
                        notification.getNotification2().getNotification3());
        boolean clearCall = command.getSubtype() == 1 && notification.hasNotificationDismiss()
                && notification.getNotificationDismiss().getNotificationIdCount() == 1
                && "phone".equals(notification.getNotificationDismiss().getNotificationId(0).getPackage())
                && notification.getNotificationDismiss().getNotificationId(0).getId() == 0;
        var settings = context.getSharedPreferences("notification-settings", MODE_PRIVATE);
        if (call && (!settings.getBoolean("callsEnabled", false)
                || context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=call-permission");
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("CALL_PERMISSION_REQUIRED"));
        }
        if (!call && !clearCall) {
            var manager = context.getSystemService(NotificationManager.class);
            if (!settings.getBoolean("enabled", true) || !manager.isNotificationListenerAccessGranted(
                    new android.content.ComponentName(context, io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.class))) {
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=access");
                return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("NOTIFICATION_ACCESS_REQUIRED"));
            }
        }
        if (queue == null || !notificationSessionReady(context)) {
            if (mayWake(context)) return holdNotification(context, command);
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason="
                    + (queue == null ? "service" : sessionReason(context)));
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        if (duplicate(context, command)) return java.util.concurrent.CompletableFuture.completedFuture(null);
        if (suppressForDnd(command, io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(context))) {
            android.util.Log.i("OplusBandBridge", "NOTIFY_SUPPRESSED_DND");
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_SUPPRESSED_DND");
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        try {
            var fitted = io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.fitToPayload(command,
                    notificationPayloadLimit());
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_OUT type=" + fitted.getType()
                    + " subtype=" + fitted.getSubtype() + " bytes=" + fitted.getSerializedSize());
            return queue.send(fitted);
        } catch (IllegalArgumentException tooLarge) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=too-large");
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("NOTIFICATION_IDENTITY_TOO_LARGE"));
        }
    }

    /** OHealth already decided this notification may reach the band. */
    public static java.util.concurrent.CompletionStage<Void> forwardHostNotification(Context context,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        if (!notificationCommand(command)) {
            android.util.Log.i("OplusBandBridge", "NOTIFY_DROP reason=command");
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=command");
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        BandLiveService live = instance;
        var queue = live == null ? null : live.commands;
        if (queue == null || !notificationSessionReady(context)) {
            if (mayWake(context)) return holdNotification(context, command);
            String reason = queue == null ? "service" : sessionReason(context);
            android.util.Log.i("OplusBandBridge", "NOTIFY_DROP reason=" + reason);
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=" + reason);
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        if (duplicate(context, command)) return java.util.concurrent.CompletableFuture.completedFuture(null);
        if (ordinaryPost(command) && io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.blocksNotifications(
                io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(context))) {
            android.util.Log.i("OplusBandBridge", "NOTIFY_SUPPRESSED_DND");
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_SUPPRESSED_DND");
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        try {
            var fitted = io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.fitToPayload(
                    command, notificationPayloadLimit());
            android.util.Log.i("OplusBandBridge", "NOTIFY_OUT type=" + fitted.getType()
                    + " subtype=" + fitted.getSubtype() + " bytes=" + fitted.getSerializedSize()
                    + " limit=" + notificationPayloadLimit());
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_OUT type=" + fitted.getType()
                    + " subtype=" + fitted.getSubtype() + " bytes=" + fitted.getSerializedSize());
            return queue.send(fitted);
        } catch (IllegalArgumentException tooLarge) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=too-large");
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_IDENTITY_TOO_LARGE"));
        }
    }

    private static boolean notificationCommand(
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        return command != null && command.getType() == 7
                && (command.getSubtype() == 0 || command.getSubtype() == 1)
                && command.hasNotification();
    }

    private static java.util.concurrent.CompletionStage<Void> holdNotification(Context context,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_HOLD");
        return HELD_NOTIFICATIONS.add(command, android.os.SystemClock.elapsedRealtime());
    }

    private void expireHeld() {
        int expired = HELD_NOTIFICATIONS.expire(android.os.SystemClock.elapsedRealtime());
        if (expired > 0) io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                "NOTIFY_DROP reason=expired count=" + expired);
    }

    private void replayHeld() {
        long now = android.os.SystemClock.elapsedRealtime();
        int expired = HELD_NOTIFICATIONS.expire(now);
        if (expired > 0) io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                "NOTIFY_DROP reason=expired count=" + expired);
        for (var held : HELD_NOTIFICATIONS.poll(now)) deliverHeld(held);
    }

    private void deliverHeld(io.github.miam1ku.mibandoplusbridge.notify.NotifyReplay.Held held) {
        var command = held.command;
        var queue = commands;
        if (queue == null || !notificationSessionReady(this)) {
            held.fail(new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "NOTIFY_DROP reason=service");
            return;
        }
        if (duplicate(this, command)) {
            held.succeed();
            return;
        }
        if (ordinaryPost(command) && io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.blocksNotifications(
                io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this))) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "NOTIFY_SUPPRESSED_DND");
            held.succeed();
            return;
        }
        try {
            var fitted = io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.fitToPayload(
                    command, notificationPayloadLimit());
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "NOTIFY_OUT type=" + fitted.getType()
                    + " subtype=" + fitted.getSubtype() + " bytes=" + fitted.getSerializedSize());
            queue.send(fitted).whenComplete((ignored, error) -> {
                if (error == null) held.succeed();
                else held.fail(error);
            });
        } catch (IllegalArgumentException tooLarge) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "NOTIFY_DROP reason=too-large");
            held.fail(new IllegalStateException("NOTIFICATION_IDENTITY_TOO_LARGE"));
        }
    }

    static boolean suppressForDnd(
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command, int filter) {
        return ordinaryPost(command) && io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd
                .blocksNotifications(filter);
    }

    private static boolean ordinaryPost(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        if (command.getSubtype() != 0 || !command.hasNotification()
                || !command.getNotification().hasNotification2()) return false;
        return !io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.isCall(
                command.getNotification().getNotification2().getNotification3());
    }

    private static boolean duplicate(Context context,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        if (command == null || (!ordinaryPost(command) && !ordinaryDismiss(command))) return false;
        if (NOTIFICATIONS.first(io.github.miam1ku.mibandoplusbridge.notify.NotifyDedupe.identity(command),
                android.os.SystemClock.elapsedRealtime())) return false;
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=duplicate");
        return true;
    }

    private static boolean ordinaryDismiss(
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        if (command.getSubtype() != 1 || !command.hasNotification()
                || !command.getNotification().hasNotificationDismiss()) return false;
        var dismiss = command.getNotification().getNotificationDismiss();
        if (dismiss.getNotificationIdCount() != 1) return false;
        var id = dismiss.getNotificationId(0);
        return !"phone".equals(id.getPackage());
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
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.start(this);
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "module start");
        weatherSync = new WeatherSync(this, () -> commands, coordinator);
        healthReplay = new HealthReplay(this, this::healthCollectionStatus, () -> {
            try {
                coordinator.execute(() -> { if (!stopRequested && historySync != null) historySync.resume(); });
            } catch (java.util.concurrent.RejectedExecutionException stopping) { }
        });
        calls = new io.github.miam1ku.mibandoplusbridge.notify.PhoneCallMonitor(this, coordinator);
        io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.ensureEnabled(this);
        HostKeepAlive.runRoot(() -> {
            try {
                new OwnershipController(this).keepAwake();
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "KEEP_AWAKE");
            } catch (RuntimeException ignored) { }
        });
        instance = this;
        sleepPauseOn = io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.enabled(this);
        long armed = io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.armedAt(this);
        if (sleepPauseOn && armed == Long.MAX_VALUE) {
            armed = System.currentTimeMillis();
            io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.rememberCutoff(this, armed);
        }
        sleepArmedAtMs = sleepPauseOn ? armed : Long.MAX_VALUE;
        coordinator.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.MINUTES);
        coordinator.scheduleAtFixedRate(this::expireHeld, 5, 5, TimeUnit.SECONDS);
        try {
            registerReceiver(bluetoothEvents,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (RuntimeException ignored) { }
        try {
            registerReceiver(dndEvents, new IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                    RECEIVER_EXPORTED);
            dndReceiverRegistered = true;
        } catch (RuntimeException ignored) { }
        try {
            android.content.ContentResolver resolver = getContentResolver();
            resolver.registerContentObserver(
                    android.provider.Settings.Global.getUriFor("zen_mode"), false, zenMode);
            resolver.registerContentObserver(
                    android.provider.Settings.Secure.getUriFor("focusmode_switch"), false, zenMode);
            resolver.registerContentObserver(
                    android.provider.Settings.Secure.getUriFor("focusmode_switch_new"), false, zenMode);
            resolver.registerContentObserver(
                    android.provider.Settings.Secure.getUriFor("op_breath_mode_status"), false, zenMode);
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
            show("正在连接手环");
            worker.execute(this::supervise);
        }
        io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.ensureEnabled(this);
        return START_STICKY;
    }

    /** ColorOS freezes a process whose task was swiped, even while this service is still running. */
    @Override public void onTaskRemoved(android.content.Intent rootIntent) {
        if (stopRequested || !mayWake(this)) return;
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "TASK_REMOVED keep");
        String title = noticeTitle == null || noticeTitle.isBlank() ? "正在连接手环" : noticeTitle;
        noticeTitle = null;
        show(title);
        io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.ensureEnabled(this);
        synchronized (stopLock) {
            retryNow = true;
            stopLock.notifyAll();
        }
    }

    private void requestStop() {
        synchronized (stopLock) {
            stopRequested = true;
            stopLock.notifyAll();
        }
        int dropped = HELD_NOTIFICATIONS.failAll(new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        if (dropped > 0) io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                "NOTIFY_DROP reason=stopped count=" + dropped);
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
                    show("连接已停止");
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
                        stored.incrementAndGet();
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
                active.setSleepFiles(measurements -> {
                    try {
                        coordinator.execute(() -> {
                            if (!stopRequested) noteSleepFile(measurements);
                        });
                    } catch (java.util.concurrent.RejectedExecutionException stopping) { }
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
                        show("手环已连接");
                    }, queue -> coordinator.execute(() -> {
                        if (client != active || stopRequested) {
                            queue.close(new IllegalStateException("SESSION_CLOSED"));
                            return;
                        }
                        commands = queue;
                        replayHeld();
                        calls.connected();
                        logFeatures();
                        io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.connectionChanged();
                        io.github.miam1ku.mibandoplusbridge.notify.NativeMusic.requestRefresh(this);
                        historySync = new LiveHistorySync(queue, coordinator, () -> {
                            try {
                                repository.recordSyncCompleted();
                                clearStoragePause();
                            } catch (RuntimeException unavailable) {
                                healthCollectionStatus("HEALTH_STORAGE_UNAVAILABLE");
                            }
                        }, this::healthCollectionStatus, healthReplay::hasCapacity);
                        long now = System.nanoTime();
                        nextBatteryAt = now + TimeUnit.MINUTES.toNanos(IDLE_POLL_MINUTES);
                        nextHealthAt = now + TimeUnit.MINUTES.toNanos(IDLE_POLL_MINUTES);
                        nextSleepFileAt = now + TimeUnit.MINUTES.toNanos(10);
                        nextWeatherAt = now + TimeUnit.MINUTES.toNanos(30);
                        weatherSync.refreshAndSend();
                        syncRequested = false;
                        healthReplay.request();
                        queue.send(realtimeStats(REALTIME_STATS_START));
                        realtimeStatsStopPending = true;
                        coordinator.schedule(() -> {
                            if (epoch == sessionEpoch) stopRealtimeStats();
                        }, REALTIME_STATS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        historySync.request(true);
                        syncDnd();
                        requestSleepState();
                    }), fileId -> coordinator.execute(() -> {
                        if (client == active && !stopRequested && historySync != null) historySync.saved(fileId);
                    }), command -> {
                        if (command.getType() == 2) {
                            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(BandLiveService.this,
                                    describeSystem(command));
                            noteBandRules(command);
                            if (command.getSubtype() == 109) adoptStoredBand();
                            applyBandManual(command);
                            noteSleep(command);
                        } else if (command.getType() == 7 || command.getType() == 18) {
                            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(BandLiveService.this,
                                    "rx type=" + command.getType() + " subtype=" + command.getSubtype()
                                            + " status=" + command.getStatus());
                        }
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
                        if (command.getType() == 2 && command.getSubtype() == 17) {
                            main.post(() -> io.github.miam1ku.mibandoplusbridge.notify.FindPhone
                                    .onBandCommand(BandLiveService.this, command));
                        }
                        if (command.getType() == 18) {
                            if (command.getSubtype() == 0) {
                                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(BandLiveService.this,
                                        "MUSIC_REQ");
                                main.post(BandLiveService.this::replyMusic);
                            }
                            main.post(() -> io.github.miam1ku.mibandoplusbridge.notify.NativeMusic
                                    .onBandCommand(BandLiveService.this, command));
                        }
                        if (command.getType() == 2 && command.getSubtype() == 43) {
                            main.post(BandLiveService.this::replySilent);
                        }
                        if (command.getType() == 17 && command.getSubtype() == 16) {
                            int op = command.hasSchedule() && command.getSchedule().hasPhoneAlarmOperation()
                                    ? command.getSchedule().getPhoneAlarmOperation().getOpCode() : -1;
                            int alarmId = op >= 0 && command.getSchedule().getPhoneAlarmOperation().hasPhoneAlarm()
                                    ? command.getSchedule().getPhoneAlarmOperation().getPhoneAlarm().getId() : -1;
                            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(BandLiveService.this,
                                    "ALARM_FROM_BAND op=" + op + " id=" + alarmId);
                            if (op == 1 || op == 2) {
                                io.github.miam1ku.mibandoplusbridge.integration.PhoneAlarmProvider.offer(op, alarmId);
                            }
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
                                if (steps > 0) stopRealtimeStats();
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
                    show(retryable(status) ? "正在重连" : "等待重连");
                } finally {
                    client = null;
                    commands = null;
                    realtimeStatsStopPending = false;
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
                if (stopRequested) break;
                long delay = retryable(status) ? backoff : QUIET_BACKOFF_MS;
                if (!waitForRetry(delay)) break;
                if (!authenticated.get() && retryable(status)) backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
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
            var service = getPackageManager().getServiceInfo(HEALTH_SERVICE, 0);
            // Version drift is a one-time home-screen hint, not a reason to refuse the import bind.
            if (!service.enabled || !service.exported
                    || !service.applicationInfo.enabled || !HEALTH_PACKAGE.equals(service.processName)
                    || (service.permission != null && !service.permission.isBlank())) {
                healthHostStatus("UNAVAILABLE");
                return;
            }
            main.post(() -> {
                if (healthBound || stopRequested || !new BandStateRepository(this).isRegistered()) return;
                try {
                    healthBound = bindService(new Intent().setComponent(HEALTH_SERVICE), healthConnection, BIND_AUTO_CREATE);
                    if (!healthBound) {
                        healthHostStatus("UNAVAILABLE");
                        reviveHealth();
                    }
                } catch (RuntimeException unavailable) {
                    healthHostStatus("UNAVAILABLE");
                    reviveHealth();
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

    private void reviveHealth() {
        HostKeepAlive.startHealthAsync(this, () -> main.post(() -> {
            if (!stopRequested) bindHealthHost();
        }));
    }

    private void healthHostStatus(String status) {
        getSharedPreferences("live-service", MODE_PRIVATE).edit().putString("healthHostStatus", status).apply();
        getContentResolver().notifyChange(io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider.URI, null);
    }

    private void healthCollectionStatus(String status) {
        getSharedPreferences("live-service", MODE_PRIVATE).edit().putString("healthCollectionStatus", status).apply();
        getContentResolver().notifyChange(io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider.URI, null);
    }
    /** A later successful round must not keep showing a storage pause that already cleared. */
    private void clearStoragePause() {
        String current = getSharedPreferences("live-service", MODE_PRIVATE)
                .getString("healthCollectionStatus", "");
        if ("HEALTH_STORAGE_UNAVAILABLE".equals(current) || "HISTORY_STORAGE_FAILED".equals(current)) {
            healthCollectionStatus("");
        }
    }


    /** 45 starts the band's live-stats stream, 46 stops it, 47 is the periodic event. */
    private static nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command realtimeStats(int subtype) {
        return nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command.newBuilder()
                .setType(8).setSubtype(subtype).build();
    }

    /** Ends the live-stats stream once; a running stream keeps the band measuring heart rate. */
    private void stopRealtimeStats() {
        if (!realtimeStatsStopPending) return;
        realtimeStatsStopPending = false;
        var queue = commands;
        if (queue == null || stopRequested) return;
        queue.send(realtimeStats(REALTIME_STATS_STOP));
    }

    private void tick() {
        var queue = commands;
        if (stopRequested || queue == null || historySync == null) return;
        if (!healthBound) bindHealthHost();
        if (!new BandStateRepository(this).isRegistered()) {
            requestStop();
            return;
        }
        long now = System.nanoTime();
        if (now >= nextBatteryAt) {
            nextBatteryAt = now + TimeUnit.MINUTES.toNanos(IDLE_POLL_MINUTES);
            queue.request(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command
                    .newBuilder().setType(2).setSubtype(1).build(), 2, 1)
                    .whenComplete((ignored, error) -> {
                        if (error != null) writeStatus("BATTERY_REFRESH_FAILED");
                    });
        }
        if (syncRequested || now >= nextHealthAt) {
            boolean historical = syncRequested;
            syncRequested = false;
            nextHealthAt = now + TimeUnit.MINUTES.toNanos(IDLE_POLL_MINUTES);
            historySync.request(historical);
            if (historical) {
                bindHealthHost();
                nextWeatherAt = now + TimeUnit.MINUTES.toNanos(30);
                weatherSync.refreshAndSend();
            }
        }
        // Bands that never answer 2/78 still publish sleep as a history file.
        if (sleepPauseOn && now >= nextSleepFileAt) {
            nextSleepFileAt = now + TimeUnit.MINUTES.toNanos(10);
            historySync.request(false);
        }
        if (now >= nextWeatherAt) {
            nextWeatherAt = now + TimeUnit.MINUTES.toNanos(30);
            weatherSync.sendIfChanged();
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

    private void syncDnd() {
        var queue = commands;
        if (stopRequested || queue == null) return;
        int filter = io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this);
        long now = System.nanoTime();
        synchronized (dndSyncLock) {
            long elapsed = dndSyncAtNanos == 0 ? -1 : now - dndSyncAtNanos;
            boolean phoneChanged = lastDndFilter != Integer.MIN_VALUE && lastDndFilter != filter;
            if (io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.repeatSync(lastDndFilter, elapsed, filter)) {
                return;
            }
            dndPushPhone = phoneChanged;
            lastDndFilter = filter;
            dndSyncAtNanos = now;
        }
        String detail = io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.describe(this);
        android.util.Log.i("OplusBandBridge", "DND_SYNC " + detail);
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "DND_SYNC " + detail);
        // The band drops a rule list that arrives before sync_with_phone is on.
        // Transport ACK is earlier than that; wait for the subtype 15 body.
        queue.request(io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.syncWithPhone(), 2, 15)
                .whenComplete((done, error) -> {
                    if (error != null) {
                        android.util.Log.i("OplusBandBridge", "DND_SYNC_REJECTED subtype=15");
                    }
                    sendDndFollowup();
                });
    }

    private void sendDndFollowup() {
        pushDndState(false);
    }

    private void pushDndState(boolean resend) {
        var queue = commands;
        if (stopRequested || queue == null) return;
        boolean phoneOn = io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.blocksNotifications(
                io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this));
        if (!io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.sendPhoneRule(
                dndPushPhone, phoneOn, dndBandKnown, dndBandOn)) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "DND_HOLD phone=" + phoneOn
                    + " changed=" + dndPushPhone + " bandKnown=" + dndBandKnown + " band=" + dndBandOn);
            if (!resend) sendQuietly(queue, io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.queryRules());
            return;
        }
        lastDndSentNanos = System.nanoTime();
        int activatedAt = io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.activatedAt(
                System.currentTimeMillis());
        sendQuietly(queue, io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.state(phoneOn));
        sendQuietly(queue, io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.phoneSilent(phoneOn));
        sendQuietly(queue, io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.phoneRules(phoneOn, activatedAt));
        if (resend) {
            sendQuietly(queue, io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.queryRules());
            return;
        }
        sendQuietly(queue, io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.queryRules());
        main.removeCallbacks(dndRulesAgain);
        main.postDelayed(dndRulesAgain, 2000);
    }

    private void replyMusic() {
        var queue = commands;
        if (stopRequested || queue == null) return;
        var command = lastMusic;
        if (command == null) {
            android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
            int volume = 0;
            if (audio != null) {
                int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                volume = io.github.miam1ku.mibandoplusbridge.protocol.BandMusicCommand.percent(
                        audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC), max);
            }
            command = io.github.miam1ku.mibandoplusbridge.protocol.BandMusicCommand.nothing(volume);
        }
        var info = command.getMusic().getMusicInfo();
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "MUSIC_REPLY state="
                + info.getState() + " volume=" + info.getVolume());
        sendQuietly(queue, command);
    }

    private void replySilent() {
        var queue = commands;
        if (stopRequested || queue == null) return;
        boolean on = io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.blocksNotifications(
                io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this));
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "SILENT_REQ on=" + on);
        sendQuietly(queue, io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.phoneSilent(on));
    }


    private void applyBandManual(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        Boolean bandOn = io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.pushedManualState(command);
        if (bandOn == null) return;
        if (!io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.acceptBandManual(
                bandOn, io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this),
                lastDndSentNanos, System.nanoTime())) return;
        requestPhoneDnd(bandOn);
    }

    private void requestPhoneDnd(boolean on) {
        main.post(() -> {
            if (!io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.acceptBandManual(
                    on, io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this),
                    lastDndSentNanos, System.nanoTime())) return;
            var attempt = io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.apply(this, on);
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "DND_FROM_BAND on=" + on
                    + " applied=" + attempt.applied() + " policy=" + attempt.policy()
                    + " via=" + attempt.via());
            if (attempt.applied()) return;
            Intent intent = new Intent(io.github.miam1ku.mibandoplusbridge.hook.OHealthDndHook.ACTION)
                    .setPackage("com.heytap.health")
                    .putExtra("on", on);
            sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent done) {
                    boolean applied = getResultCode() == 1;
                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(BandLiveService.this,
                            "DND_FROM_BAND_HOST on=" + on + " applied=" + applied
                                    + " via=" + getResultData());
                    if (!applied) askRootZen(on);
                }
            }, main, 0, null, null);
        });
    }

    private void askRootZen(boolean on) {
        HostKeepAlive.runRoot(() -> {
            boolean wrote = OwnershipController.setZenMode(on);
            boolean applied = wrote && io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.blocksNotifications(
                    io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this)) == on;
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "DND_FROM_BAND_ROOT on=" + on
                    + " wrote=" + wrote + " applied=" + applied);
        });
    }

    private void requestSleepState() {
        var queue = commands;
        if (stopRequested || queue == null || !sleepPauseOn) return;
        queue.request(io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.query(), 2, 78).exceptionally(error -> {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "SLEEP_MUSIC query missed");
            android.util.Log.i("OplusBandBridge", "SLEEP_MUSIC_QUERY_FAILED");
            return null;
        });
    }

    private void noteSleep(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        if (!sleepPauseOn) return;
        Boolean asleep = io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.asleep(command);
        if (asleep == null) return;
        long now = System.currentTimeMillis();
        var report = command.getSubtype() == 79 ? sleepMusic.push(asleep, now) : sleepMusic.observe(asleep, now);
        if ((report.effect() == io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.Effect.BASELINE && asleep)
                || report.effect() == io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.Effect.WOKE) {
            if (io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.rememberCutoff(this, now)) {
                sleepArmedAtMs = Math.max(sleepArmedAtMs, now);
            }
        }
        applySleep(report, switch (report.effect()) {
            case PAUSE -> "SLEEP_MUSIC pause";
            case BASELINE -> "SLEEP_MUSIC baseline asleep=" + asleep;
            case WOKE -> "SLEEP_MUSIC awake";
            case UNCHANGED -> null;
        });
    }

    private void noteSleepFile(java.util.List<io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser.Measurement> measurements) {
        if (!sleepPauseOn || measurements == null) return;
        long now = System.currentTimeMillis();
        for (var measurement : measurements) {
            if (!"sleep_interval".equals(measurement.kind)) continue;
            var report = sleepMusic.currentNight(measurement.startMs, measurement.endMs, sleepArmedAtMs, now);
            if (report.effect() != io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.Effect.PAUSE) continue;
            applySleep(report, "SLEEP_MUSIC pause file");
        }
    }

    private void applySleep(io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.Report report, String line) {
        if (line == null) return;
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, line);
        android.util.Log.i("OplusBandBridge", line);
        if (report.effect() != io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.Effect.PAUSE) return;
        int generation = report.generation();
        main.post(() -> {
            if (!sleepPauseOn || sleepMusic.generation() != generation) return;
            io.github.miam1ku.mibandoplusbridge.notify.NativeMusic.pause(this);
        });
    }

    private void sendDndRulesAgain() {
        pushDndState(true);
    }

    private void noteBandRules(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        Boolean on = io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.manualState(command);
        if (on == null) return;
        dndBandKnown = true;
        dndBandOn = on;
    }

    private void adoptStoredBand() {
        if (dndPushPhone || !dndBandKnown) return;
        boolean phoneOn = io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.blocksNotifications(
                io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this));
        if (dndBandOn == phoneOn || !dndAdopted.compareAndSet(false, true)) return;
        requestPhoneDnd(dndBandOn);
    }

    private static void sendQuietly(
            io.github.miam1ku.mibandoplusbridge.protocol.LiveCommandQueue queue,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        int subtype = command.getSubtype();
        queue.send(command).exceptionally(error -> {
            android.util.Log.i("OplusBandBridge", "DND_SYNC_REJECTED subtype=" + subtype);
            return null;
        });
    }

    private static String describeSystem(
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        byte[] raw = command.toByteArray();
        StringBuilder hex = new StringBuilder(Math.min(raw.length, 120) * 2);
        int shown = Math.min(raw.length, 120);
        for (int i = 0; i < shown; i++) {
            hex.append(Character.forDigit((raw[i] >> 4) & 0xf, 16))
                    .append(Character.forDigit(raw[i] & 0xf, 16));
        }
        StringBuilder rules = new StringBuilder();
        if (command.hasSystem() && command.getSystem().hasPhoneZenRules()) {
            var list = command.getSystem().getPhoneZenRules().getRuleList();
            rules.append(" rules=").append(list.size());
            for (var rule : list) {
                rules.append(" {").append(rule.getName()).append(" manual=").append(rule.getManual())
                        .append(" state=").append(rule.getState()).append('}');
            }
        }
        return "rx type=2 subtype=" + command.getSubtype() + " status=" + command.getStatus()
                + " bytes=" + raw.length + rules + " hex=" + hex;
    }

    private void show(String title) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "手环连接", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        Notification notice = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
        synchronized (this) {
            if (title.equals(noticeTitle)) return;
            startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            noticeTitle = title;
        }
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        unbindHealthHost();
        if (receiverRegistered) {
            unregisterReceiver(bluetoothEvents);
            receiverRegistered = false;
        }
        if (dndReceiverRegistered) {
            unregisterReceiver(dndEvents);
            dndReceiverRegistered = false;
        }
        try { getContentResolver().unregisterContentObserver(zenMode); } catch (RuntimeException ignored) { }
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
