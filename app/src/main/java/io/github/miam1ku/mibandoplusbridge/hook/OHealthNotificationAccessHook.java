// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OHealth's listener API stays false on this ROM and then disable/enable-loops the service.
 * Once the secure setting lists it, stop is ignored. Run is replaced with {@code requestRebind}
 * so a listed but unbound service still starts.
 */
public final class OHealthNotificationAccessHook {
    private static final String UTIL = "com.heytap.health.watch.notification.NotificationListenerUtil";
    private static final String COMPANION = UTIL + "$Companion";
    private static final String LISTENER =
            "com.heytap.health.watch.commonnotification.HeytapNotificationListenerService";
    private static final String COMPONENT = "com.heytap.health/" + LISTENER;
    private static final AtomicBoolean rebound = new AtomicBoolean();
    private static final AtomicBoolean stopLogged = new AtomicBoolean();
    private static final AtomicBoolean serviceConnected = new AtomicBoolean();
    private static volatile boolean rawGranted;
    private static volatile boolean rawKnown;
    private static String lastAccess = "";
    private static String lastRebindFailure = "";

    private OHealthNotificationAccessHook() {}

    public static void install(Context context, ClassLoader loader) throws ClassNotFoundException {
        XC_MethodHook grant = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(param.getResult())) return;
                Context host = contextArg(param);
                if (host != null && granted(host)) param.setResult(true);
            }
        };
        Class<?> companion = Class.forName(COMPANION, false, loader);
        Class<?> util = Class.forName(UTIL, false, loader);
        XposedBridge.hookAllMethods(companion, "isNotificationListenerEnabled", grant);
        XposedBridge.hookAllMethods(util, "isNotificationListenerEnabled", grant);
        XposedBridge.hookAllMethods(companion, "runNotificationService", keep(false));
        XposedBridge.hookAllMethods(util, "runNotificationService", keep(false));
        XposedBridge.hookAllMethods(companion, "stopNotificationService", keep(true));
        XposedBridge.hookAllMethods(util, "stopNotificationService", keep(true));
        XposedHelpers.findAndHookMethod(NotificationManager.class, "isNotificationListenerAccessGranted",
                ComponentName.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.args[0] instanceof ComponentName name)) return;
                        if (!LISTENER.equals(name.getClassName())) return;
                        boolean raw = Boolean.TRUE.equals(param.getResult());
                        Context host = contextArg(param);
                        if (host == null) return;
                        noteAccess(host, raw);
                        if (!raw && granted(host)) param.setResult(true);
                    }
                });
        hookServiceLifecycle(loader);
        noteExistingService(context, loader);
        Class<?> item = Class.forName(
                "com.heytap.health.device.tab.itemview.wearable.MenuNotificationItem", false, loader);
        XposedBridge.hookAllMethods(item, "initData", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object controller = XposedHelpers.callMethod(param.thisObject, "getController");
                    Object label = XposedHelpers.callMethod(param.thisObject, "getMTvRight");
                    XposedHelpers.callMethod(label, "setText", "");
                } catch (Throwable ignored) { }
            }
        });
        rebind(context);
        scheduleRetry(context);
    }

    /** The service may already be bound before this hook is installed. */
    private static void noteExistingService(Context context, ClassLoader loader) {
        try {
            Class<?> center = Class.forName(
                    "com.heytap.health.watch.notification.HealthNotificationRegisterCenter", false, loader);
            if (XposedHelpers.getStaticObjectField(center, "mListenerService") == null) return;
            serviceConnected.set(true);
            note(context, "OHEALTH_LISTENER state=already-connected process="
                    + android.app.Application.getProcessName());
            report(context, true);
        } catch (Throwable ignored) { }
    }

    /** The framework method is empty and may be inlined. Hook the service's own overrides. */
    private static void hookServiceLifecycle(ClassLoader loader) {
        try {
            Class<?> service = Class.forName(LISTENER, false, loader);
            XposedBridge.hookAllMethods(service, "onCreate", lifecycle("created", false));
            XposedBridge.hookAllMethods(service, "onListenerConnected", lifecycle("connected", true));
            XposedBridge.hookAllMethods(service, "onListenerDisconnected", lifecycle("disconnected", false));
            XposedBridge.hookAllMethods(service, "onDestroy", lifecycle("destroyed", false));
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_NOTIFICATION_LISTENER lifecycle-hook "
                    + failure.getClass().getSimpleName());
        }
    }

    private static XC_MethodHook lifecycle(String state, boolean connected) {
        return new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (connected) serviceConnected.set(true);
                else if ("disconnected".equals(state) || "destroyed".equals(state)) serviceConnected.set(false);
                Context host = param.thisObject instanceof Context service ? service : contextArg(param);
                note(host, "OHEALTH_LISTENER state=" + state + " process=" + android.app.Application.getProcessName());
                report(host, true);
            }
        };
    }

    /** Stop is skipped once the band is registered or Health is already listed. Run never toggles the component. */
    private static XC_MethodHook keep(boolean stop) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Context context = contextArg(param);
                if (context == null) return;
                boolean hold = granted(context) || OHealthDeviceHook.registeredBand();
                if (stop && !hold) return;
                if (stop) {
                    if (stopLogged.compareAndSet(false, true)) {
                        note(context, "OHEALTH_NOTIFICATION_LISTENER stop-blocked");
                    }
                } else {
                    rebind(context);
                }
                param.setResult(null);
            }
        };
    }

    private static void scheduleRetry(Context context) {
        if (context == null || !"com.heytap.health:transport".equals(android.app.Application.getProcessName())) return;
        Handler handler = new Handler(Looper.getMainLooper());
        long[] waits = {15_000L, 30_000L, 60_000L};
        handler.postDelayed(() -> retry(context, handler, waits, 0), waits[0]);
    }

    private static void retry(Context context, Handler handler, long[] waits, int done) {
        if (done >= waits.length || serviceConnected.get()) return;
        rebind(context);
        note(context, "OHEALTH_LISTENER rebind-retry n=" + (done + 1));
        int next = done + 1;
        if (next < waits.length) handler.postDelayed(() -> retry(context, handler, waits, next), waits[next]);
    }

    private static void noteAccess(Context context, boolean raw) {
        int state = componentState(context);
        String line = "OHEALTH_LISTENER_ACCESS raw=" + raw + " secure=" + granted(context) + " state=" + state;
        if (line.equals(lastAccess) && rawKnown) return;
        lastAccess = line;
        rawKnown = true;
        rawGranted = raw;
        note(context, line);
        report(context, false);
    }

    private static void report(Context context, boolean includeConnection) {
        if (context == null) return;
        try {
            android.os.Bundle extras = new android.os.Bundle();
            if (includeConnection) extras.putBoolean("connected", serviceConnected.get());
            if (rawKnown) {
                extras.putBoolean("approved", rawGranted);
                extras.putBoolean("secure", granted(context));
            }
            extras.putInt("componentState", componentState(context));
            extras.putString("process", android.app.Application.getProcessName());
            context.getContentResolver().call(
                    io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider.URI,
                    "healthListener", null, extras);
        } catch (RuntimeException ignored) { }
    }

    private static int componentState(Context context) {
        try {
            return context.getPackageManager().getComponentEnabledSetting(
                    new ComponentName("com.heytap.health", LISTENER));
        } catch (RuntimeException failure) {
            return -1;
        }
    }

    private static void rebind(Context context) {
        if (context == null || !granted(context)) return;
        ComponentName component = new ComponentName("com.heytap.health", LISTENER);
        try {
            PackageManager packages = context.getPackageManager();
            int state = packages.getComponentEnabledSetting(component);
            if (disabled(state)) {
                packages.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
            }
            NotificationListenerService.requestRebind(component);
            if (rebound.compareAndSet(false, true)) {
                note(context, "OHEALTH_NOTIFICATION_LISTENER rebind state=" + state);
            }
        } catch (Throwable failure) {
            String line = "OHEALTH_NOTIFICATION_LISTENER rebind-failed "
                    + failure.getClass().getSimpleName();
            if (line.equals(lastRebindFailure)) return;
            lastRebindFailure = line;
            note(context, line);
        }
    }

    static boolean disabled(int state) {
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }

    private static void note(Context context, String line) {
        Log.i("OplusBandBridge", line);
        OHealthDeviceHook.traceLine(context, line);
    }

    private static Context contextArg(XC_MethodHook.MethodHookParam param) {
        if (param.thisObject instanceof Context context) return context;
        if (param.args != null) {
            for (Object arg : param.args) {
                if (arg instanceof Context context) return context;
            }
        }
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
            return app instanceof Context context ? context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean granted(Context context) {
        String enabled = android.provider.Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        if (enabled == null || enabled.isBlank()) return false;
        ComponentName component = ComponentName.unflattenFromString(COMPONENT);
        String flat = component == null ? COMPONENT : component.flattenToString();
        String shortName = component == null ? COMPONENT : component.flattenToShortString();
        for (String item : enabled.split(":")) {
            if (flat.equals(item) || shortName.equals(item) || COMPONENT.equals(item)) return true;
        }
        return false;
    }
}
