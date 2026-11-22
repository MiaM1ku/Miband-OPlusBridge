// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider;
import io.github.miam1ku.mibandoplusbridge.notify.FindPhone;

/** Band find-phone uses OHealth's ring. The device-page find row rings this band. */
public final class OHealthFindPhoneHook {
    private static final String HANDLER =
            "com.heytap.health.watch.commonsync.messagehandler.FindPhoneHandler";
    private static final String HANDLER_6117 =
            "com.heytap.health.watch.commonsync.messagehandler.g";
    private static final String UTIL = "com.heytap.health.watch.commonsync.util.FindPhoneUtil";
    private static final String UTIL_6117 = "ne0.d";
    private static final String FIND_ROW =
            "com.heytap.health.device.tab.itemview.wearable.MenuFindDeviceItem";
    private static final String FIND_ROW_6117 =
            "com.heytap.health.device.tab.itemview.wearable.f0";
    private static boolean findingWatch;

    private OHealthFindPhoneHook() {}

    public static void install(Context context, ClassLoader loader) {
        if (!"com.heytap.health".equals(android.app.Application.getProcessName())) return;
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        IntentFilter filter = new IntentFilter(FindPhone.ACTION);
        HookRuntime.receive(app, new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || !FindPhone.ACTION.equals(intent.getAction())) return;
                if (intent.getBooleanExtra("start", false)) play(loader);
                else stop(loader);
            }
        }, filter, FindPhone.PERMISSION, null, Context.RECEIVER_EXPORTED);
        XC_MethodHook click = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!ourBand(param.thisObject)) return;
                param.setResult(null);
                findingWatch = !findingWatch;
                Bundle extras = new Bundle();
                extras.putBoolean("start", findingWatch);
                try {
                    Bundle result = app.getContentResolver().call(
                            HostNotifyProvider.URI, "findWatch", null, extras);
                    if (result == null || !"QUEUED".equals(result.getString("status"))) {
                        findingWatch = !findingWatch;
                        HookRuntime.log("FIND_WATCH native unavailable");
                        return;
                    }
                    HookRuntime.log(findingWatch ? "FIND_WATCH start" : "FIND_WATCH stop");
                } catch (RuntimeException failure) {
                    findingWatch = !findingWatch;
                    HookRuntime.log("FIND_WATCH native unavailable");
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(FIND_ROW, loader, "itemClick", click);
        } catch (Throwable renamed) {
            try {
                XposedHelpers.findAndHookMethod(FIND_ROW_6117, loader, "J", click);
            } catch (Throwable failure) {
                HookRuntime.log("FIND_WATCH native unavailable");
            }
        }
    }

    private static void play(ClassLoader loader) {
        try {
            Object handler = XposedHelpers.newInstance(XposedHelpers.findClass(HANDLER, loader));
            XposedHelpers.callMethod(handler, "playRing");
            return;
        } catch (Throwable renamed) { }
        try {
            Class<?> handler = XposedHelpers.findClass(HANDLER_6117, loader);
            java.lang.reflect.Method ring = handler.getDeclaredMethod("a", String.class);
            ring.setAccessible(true);
            ring.invoke(null, (Object) null);
        } catch (Throwable failure) {
            HookRuntime.log("FIND_PHONE native unavailable");
        }
    }

    private static void stop(ClassLoader loader) {
        try {
            Class<?> util = XposedHelpers.findClass(UTIL, loader);
            Object instance = XposedHelpers.getStaticObjectField(util, "INSTANCE");
            XposedHelpers.callMethod(instance, "stopPlayRing");
            XposedHelpers.callMethod(instance, "setVolumeToOrigin");
            return;
        } catch (Throwable renamed) { }
        try {
            Class<?> util = XposedHelpers.findClass(UTIL_6117, loader);
            Object instance = XposedHelpers.getStaticObjectField(util, "INSTANCE");
            XposedHelpers.callMethod(instance, "d");
        } catch (Throwable failure) {
            HookRuntime.log("FIND_PHONE native unavailable");
        }
    }

    private static boolean ourBand(Object item) {
        Bundle band = OHealthDeviceHook.registeredSnapshot();
        if (item == null || band == null || !band.getBoolean("registered", false)) return false;
        String expected = band.getString("mac", "");
        if (expected.isBlank()) return false;
        try {
            Object info;
            try {
                info = XposedHelpers.callMethod(item, "getCurrSelectWearableDevice");
            } catch (Throwable renamed) {
                Object controller = XposedHelpers.getObjectField(item, "i");
                info = XposedHelpers.callMethod(controller, "k");
            }
            if (info == null) return false;
            Object mac = XposedHelpers.callMethod(info, "getMac");
            return mac != null && expected.equalsIgnoreCase(String.valueOf(mac));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
