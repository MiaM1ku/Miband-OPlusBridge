// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** OHealth's listener API stays false on this ROM and then disable/enable-loops the service. */
public final class OHealthNotificationAccessHook {
    private static final String UTIL = "com.heytap.health.watch.notification.NotificationListenerUtil";
    private static final String COMPANION = UTIL + "$Companion";
    private static final String LISTENER =
            "com.heytap.health.watch.commonnotification.HeytapNotificationListenerService";
    private static final String COMPONENT = "com.heytap.health/" + LISTENER;

    private OHealthNotificationAccessHook() {}

    public static void install(ClassLoader loader) throws ClassNotFoundException {
        XC_MethodHook grant = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!Boolean.TRUE.equals(param.getResult())) param.setResult(true);
            }
        };
        Class<?> companion = Class.forName(COMPANION, false, loader);
        XposedBridge.hookAllMethods(companion, "isNotificationListenerEnabled", grant);
        XposedBridge.hookAllMethods(Class.forName(UTIL, false, loader), "isNotificationListenerEnabled", grant);
        XC_MethodHook keep = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Log.i("OplusBandBridge", "OHEALTH_NOTIFICATION_LISTENER_KEEP");
                param.setResult(null);
            }
        };
        XposedBridge.hookAllMethods(companion, "runNotificationService", keep);
        XposedBridge.hookAllMethods(Class.forName(UTIL, false, loader), "runNotificationService", keep);
        XposedBridge.hookAllMethods(companion, "stopNotificationService", keep);
        XposedHelpers.findAndHookMethod(NotificationManager.class, "isNotificationListenerAccessGranted",
                ComponentName.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(param.getResult())) return;
                        if (!(param.args[0] instanceof ComponentName name)) return;
                        if (LISTENER.equals(name.getClassName())) param.setResult(true);
                    }
                });
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
