// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
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
    private static final AtomicBoolean boundLogged = new AtomicBoolean();
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
                        if (Boolean.TRUE.equals(param.getResult())) return;
                        if (!(param.args[0] instanceof ComponentName name)) return;
                        if (!LISTENER.equals(name.getClassName())) return;
                        Context host = contextArg(param);
                        if (host != null && granted(host)) param.setResult(true);
                    }
                });
        try {
            XposedHelpers.findAndHookMethod(NotificationListenerService.class, "onListenerConnected",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject == null
                                    || !LISTENER.equals(param.thisObject.getClass().getName())) return;
                            if (!boundLogged.compareAndSet(false, true)) return;
                            Context host = param.thisObject instanceof Context service ? service : contextArg(param);
                            note(host, "OHEALTH_NOTIFICATION_LISTENER bound");
                        }
                    });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_NOTIFICATION_LISTENER bound-hook "
                    + failure.getClass().getSimpleName());
        }
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
    }

    /** {@code stop} skips the original call. Run rebinds, then skips the disable/enable toggle. */
    private static XC_MethodHook keep(boolean stop) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Context context = contextArg(param);
                if (context == null || !granted(context)) return;
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
