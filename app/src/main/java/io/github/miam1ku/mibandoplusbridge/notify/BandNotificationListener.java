// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** Android adapter: metadata gates precede any extras/body access. */
public final class BandNotificationListener extends NotificationListenerService {
    public static final String SETTINGS = "notification-settings";
    private static volatile BandNotificationListener instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean listenerConnected;
    private NotificationRelay relay;
    private SharedPreferences settings;
    private long appliedSession;
    private boolean appliedEnabled, appliedBody;
    private Set<String> appliedPackages = Set.of();
    private final NotifyHold hold = new NotifyHold();
    private String lastWake = "";
    private String lastSkip = "";
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged = (prefs, key) -> {
        if (!"observedPackages".equals(key)) main.post(this::resetSession);
    };
    private final BroadcastReceiver lockChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            relay.cancelPending();
            BandLiveService.cancelNotifications(BandNotificationListener.this);
        }
    };

    public static boolean accessGranted(Context context) {
        ComponentName component = new ComponentName(context, BandNotificationListener.class);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null && manager.isNotificationListenerAccessGranted(component)) return true;
        String enabled = android.provider.Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        if (enabled == null || enabled.isBlank()) return false;
        String flat = component.flattenToString();
        String shortName = component.flattenToShortString();
        for (String item : enabled.split(":")) {
            if (flat.equals(item) || shortName.equals(item)) return true;
        }
        return false;
    }

    public static boolean listenerConnected() {
        BandNotificationListener current = instance;
        return current != null && current.listenerConnected;
    }

    /** The manifest disables the service so the system hides it until native mode. */
    public static void ensureEnabled(Context context) {
        PackageManager packages = context.getPackageManager();
        ComponentName component = new ComponentName(context, BandNotificationListener.class);
        if (packages.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            packages.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        }
        if (!listenerConnected()) {
            try { requestRebind(component); } catch (RuntimeException ignored) { }
        }
    }

    public static void connectionChanged() {
        BandNotificationListener current = instance;
        if (current != null) current.main.post(current::resetSession);
    }

    @Override public void onCreate() {
        super.onCreate();
        settings = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        relay = new NotificationRelay(command -> BandLiveService.sendNotification(this, command),
                BandLiveService::notificationPayloadLimit, main::post);
        settings.registerOnSharedPreferenceChangeListener(settingsChanged);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(lockChanged, filter, Context.RECEIVER_NOT_EXPORTED);
        instance = this;
    }

    @Override public void onListenerConnected() {
        listenerConnected = true;
        wakeLive();
        resetSession();
        PhoneMusic.attach(this, main);
    }

    @Override public void onListenerDisconnected() {
        PhoneMusic.detach();
        appliedSession = 0;
        listenerConnected = false;
        relay.disconnected();
        BandLiveService.cancelNotifications(this);
        if (!BandLiveService.isRunning()) return;
        main.postDelayed(() -> {
            if (listenerConnected || !BandLiveService.isRunning() || !accessGranted(this)) return;
            try {
                requestRebind(new ComponentName(this, BandNotificationListener.class));
                SessionLog.line(this, "NOTIFY_LISTENER rebind");
            } catch (RuntimeException ignored) { }
        }, 1_000);
    }

    private boolean sessionAllowed() {
        return listenerConnected && accessGranted(this) && BandLiveService.notificationSessionReady(this);
    }

    private boolean notificationsAllowed() {
        return sessionAllowed() && settings.getBoolean("enabled", true);
    }

    private void wakeLive() {
        try {
            BandLiveService.start(this);
        } catch (RuntimeException failure) {
            String name = failure.getClass().getSimpleName();
            if (name.equals(lastWake)) return;
            lastWake = name;
            SessionLog.line(this, "NOTIFY_WAKE " + name);
        }
    }

    private void resetSession() {
        boolean enabled = settings.getBoolean("enabled", true);
        boolean body = settings.getBoolean("showBody", true);
        Set<String> packages = Set.copyOf(settings.getStringSet("packages", Set.of()));
        long session = notificationsAllowed() ? BandLiveService.notificationSessionId() : 0;
        if (session == appliedSession && enabled == appliedEnabled && body == appliedBody
                && packages.equals(appliedPackages)) return;
        appliedSession = session;
        appliedEnabled = enabled;
        appliedBody = body;
        appliedPackages = packages;
        relay.disconnected();
        BandLiveService.cancelNotifications(this);
        relay.configure(enabled, packages, body);
        if (!enabled) hold.clear();
        if (!notificationsAllowed()) return;
        HashMap<String, Long> baseline = new HashMap<>();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification item : active) {
                observePackage(item.getPackageName());
                if (hold.contains(item.getKey())) continue;
                baseline.put(item.getKey(), item.getPostTime());
                if (baseline.size() > NotificationRelay.CAPACITY) break;
            }
            relay.connected(baseline);
            if (!"NOTIFICATION_BASELINE_CAPACITY".equals(relay.lastFailureCode())) {
                for (NotificationRelay.Event event : hold.drain()) relay.posted(event);
            }
        } catch (SecurityException unavailable) { relay.disconnected(); }
    }

    @Override public void onNotificationPosted(StatusBarNotification item, RankingMap rankings) {
        if (!listenerConnected || item == null) return;
        observePackage(item.getPackageName()); // Package names only, even while disabled.
        Notification posted = item.getNotification();
        if (posted != null && Notification.CATEGORY_CALL.equals(posted.category)
                && CallPresentation.connected(posted)) {
            BandLiveService.noteCallAnswered();
        }
        if (!accessGranted(this)) {
            skip("access", item.getPackageName());
            return;
        }
        if (!settings.getBoolean("enabled", true)) {
            skip("switch", item.getPackageName());
            hold.clear();
            relay.disconnected();
            BandLiveService.cancelNotifications(this);
            return;
        }
        if (PhoneAlarmNotice.ringing(item)) {
            if (!BandLiveService.notificationSessionReady(this)) wakeLive();
            PhoneAlarmNotice.posted(this, item);
            return;
        }
        Notification call = item.getNotification();
        if (call != null && Notification.CATEGORY_CALL.equals(call.category)) {
            if (!BandLiveService.notificationSessionReady(this)) wakeLive();
            skip("call", item.getPackageName());
            return;
        }
        Notification n = item.getNotification();
        Ranking ranking = new Ranking();
        if (!admit(item, n, rankings, ranking)) {
            skip(skipReason(item, n, rankings, ranking), item.getPackageName());
            hold.remove(item.getKey());
            relay.removed(item.getKey());
            return;
        }
        NotificationRelay.Event event = event(item, n, ranking);
        if (!BandLiveService.notificationSessionReady(this)) {
            hold.put(event);
            skip("session", item.getPackageName());
            wakeLive();
            return;
        }
        hold.remove(item.getKey());
        String appName = event.appName();
        SessionLog.line(this, "NOTIFY_POST pkg=" + item.getPackageName()
                + " app=" + (appName.equals(item.getPackageName()) ? "package" : "label"));
        relay.posted(event);
    }

    private boolean admit(StatusBarNotification item, Notification notification, RankingMap rankings, Ranking ranking) {
        if (getPackageName().equals(item.getPackageName())) return false;
        if (PhoneAlarmNotice.CLOCK.equals(item.getPackageName())) return false;
        if (!NotifyAdmission.packageAllowed(settings.getStringSet("packages", Set.of()), item.getPackageName())) return false;
        if (!NotifyAdmission.healthAllows(settings.getBoolean("mainSwitchKnown", false),
                settings.getBoolean("mainSwitch", true), settings.getStringSet("deniedPackages", Set.of()),
                item.getPackageName())) return false;
        if (notification == null) return false;
        if ((notification.flags & (Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_GROUP_SUMMARY)) != 0) return false;
        if (notification.visibility == Notification.VISIBILITY_SECRET) return false;
        if (rankings == null || !rankings.getRanking(item.getKey(), ranking)) return false;
        if (ranking.getImportance() <= NotificationManager.IMPORTANCE_LOW) return false;
        return !screenBlocks();
    }

    private boolean screenBlocks() {
        PowerManager power = getSystemService(PowerManager.class);
        boolean interactive = power != null && power.isInteractive();
        return NotifyAdmission.screenBlocks(settings.getBoolean("screenOnPush", true), interactive, locked(this));
    }

    private NotificationRelay.Event event(StatusBarNotification item, Notification n, Ranking ranking) {
        boolean locked = locked(this);
        Notification visible = n;
        boolean bodyAllowed = settings.getBoolean("showBody", true);
        if (locked && n.visibility == Notification.VISIBILITY_PRIVATE) {
            if (n.publicVersion != null && n.publicVersion.visibility != Notification.VISIBILITY_SECRET) visible = n.publicVersion;
            else bodyAllowed = false;
        }
        String title = extra(visible, Notification.EXTRA_TITLE);
        String body = bodyAllowed ? extra(visible, Notification.EXTRA_BIG_TEXT) : "";
        if (bodyAllowed && body.isBlank()) body = extra(visible, Notification.EXTRA_TEXT);
        String appName = AppLabels.label(this, item.getPackageName());
        if (appName.isBlank()) appName = item.getPackageName();
        return new NotificationRelay.Event(item.getPackageName(), appName, item.getKey(),
                title, body, visible != n ? title : null, visible != n ? body : null,
                item.getPostTime(), n.visibility, locked, false, false, ranking.getImportance());
    }

    private String skipReason(StatusBarNotification item, Notification notification, RankingMap rankings, Ranking ranking) {
        if (getPackageName().equals(item.getPackageName())) return "self";
        if (PhoneAlarmNotice.CLOCK.equals(item.getPackageName())) return "clock";
        if (!NotifyAdmission.packageAllowed(settings.getStringSet("packages", Set.of()), item.getPackageName())) return "package";
        if (!NotifyAdmission.healthAllows(settings.getBoolean("mainSwitchKnown", false),
                settings.getBoolean("mainSwitch", true), settings.getStringSet("deniedPackages", Set.of()),
                item.getPackageName())) return "health";
        if (notification == null) return "empty";
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return "summary";
        if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return "foreground";
        if (notification.visibility == Notification.VISIBILITY_SECRET) return "secret";
        if (rankings == null || !rankings.getRanking(item.getKey(), ranking)) return "ranking";
        if (ranking.getImportance() <= NotificationManager.IMPORTANCE_LOW) return "importance";
        if (screenBlocks()) return "screen";
        return "filtered";
    }


    private void skip(String reason, String pkg) {
        String line = reason + "|" + pkg;
        if (line.equals(lastSkip)) return;
        lastSkip = line;
        SessionLog.line(this, "NOTIFY_SKIP reason=" + reason + " pkg=" + (pkg == null || pkg.isBlank() ? "none" : pkg));
    }

    @Override public void onNotificationRemoved(StatusBarNotification item, RankingMap rankings, int reason) {
        PhoneAlarmNotice.removed(this, item);
        if (item != null) {
            hold.remove(item.getKey());
            relay.removed(item.getKey());
        }
    }

    @Override public void onNotificationRankingUpdate(RankingMap rankings) {
        if (!notificationsAllowed()) { relay.disconnected(); BandLiveService.cancelNotifications(this); return; }
        // Ranking changes can only withdraw existing delivery, never replay an active baseline.
        for (String key : rankings.getOrderedKeys()) {
            Ranking ranking = new Ranking();
            if (rankings.getRanking(key, ranking) && ranking.getImportance() <= NotificationManager.IMPORTANCE_LOW) {
                hold.remove(key);
                relay.removed(key);
            }
        }
    }

    /** Called only for a currently ringing SIM; never reads contacts, numbers or notification bodies. */
    public static String currentIncomingCallTitle(Context context) {
        BandNotificationListener current = instance;
        if (current == null || !current.sessionAllowed()
                || !current.settings.getBoolean("callsEnabled", false)
                || context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null;
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        String dialer = telecom == null ? null : telecom.getDefaultDialerPackage();
        if (dialer == null) return null;
        try {
            StatusBarNotification[] active = current.getActiveNotifications();
            if (active == null) return null;
            StatusBarNotification latest = null;
            for (StatusBarNotification item : active) {
                Notification n = item.getNotification();
                if (dialer.equals(item.getPackageName()) && n != null
                        && Notification.CATEGORY_CALL.equals(n.category)
                        && n.visibility != Notification.VISIBILITY_SECRET
                        && (latest == null || item.getPostTime() > latest.getPostTime())) latest = item;
            }
            if (latest == null) return null;
            Notification n = latest.getNotification();
            if (locked(context) && n.visibility == Notification.VISIBILITY_PRIVATE && n.publicVersion != null) {
                if (n.publicVersion.visibility == Notification.VISIBILITY_SECRET) return null;
                n = n.publicVersion;
            }
            String title = extra(n, Notification.EXTRA_TITLE);
            return title.isBlank() ? null : title;
        } catch (SecurityException unavailable) { return null; }
    }

    public static String currentIncomingCallNumber(Context context) {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        BandNotificationListener current = instance;
        if (current == null || !current.sessionAllowed()
                || !current.settings.getBoolean("callsSmsReply", false)) return null;
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        String dialer = telecom == null ? null : telecom.getDefaultDialerPackage();
        if (dialer == null) return null;
        try {
            StatusBarNotification[] active = current.getActiveNotifications();
            if (active == null) return null;
            for (StatusBarNotification item : active) {
                Notification n = item.getNotification();
                if (!dialer.equals(item.getPackageName()) || n == null
                        || !Notification.CATEGORY_CALL.equals(n.category)) continue;
                String text = extra(n, Notification.EXTRA_TEXT);
                if (io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.usableNumber(text)) return text;
                String title = extra(n, Notification.EXTRA_TITLE);
                if (io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.usableNumber(title)) return title;
            }
            return null;
        } catch (SecurityException unavailable) { return null; }
    }

    private static String extra(Notification notification, String key) {
        return notification.extras == null ? "" : NotificationRelay.sanitize(notification.extras.getCharSequence(key));
    }


    private static boolean locked(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard == null || keyguard.isDeviceLocked();
    }

    private void observePackage(String packageName) {
        if (packageName == null || getPackageName().equals(packageName)) return;
        Set<String> observed = settings.getStringSet("observedPackages", Set.of());
        if (observed.contains(packageName) || observed.size() >= NotificationRelay.CAPACITY) return;
        Set<String> copy = new HashSet<>(observed);
        copy.add(packageName);
        settings.edit().putStringSet("observedPackages", copy).apply();
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        listenerConnected = false;
        relay.disconnected();
        BandLiveService.cancelNotifications(this);
        settings.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        unregisterReceiver(lockChanged);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
