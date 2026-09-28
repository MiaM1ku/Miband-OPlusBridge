// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.Application;
import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EntryPoint implements IXposedHookLoadPackage {
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicBoolean healthInstalled = new AtomicBoolean();

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if ("com.coloros.alarmclock".equals(load.packageName)) {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Context context = (Context) param.args[0];
                    if (context == null) return;
                    Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
                    try {
                        ClockAlarmHook.install(app, load.classLoader);
                    } catch (Throwable failure) {
                        android.util.Log.i("OplusBandBridge", "CLOCK_ALARM_HOOK_SKIPPED "
                                + failure.getClass().getSimpleName());
                    }
                }
            });
            return;
        }
        if (!HostIdentity.MI_PACKAGE.equals(load.packageName)
                && !"com.heytap.mydevices".equals(load.packageName)
                && !"com.heytap.health".equals(load.packageName)) return;
        if ("com.heytap.health".equals(load.packageName)) {
            android.util.Log.i("OplusBandBridge", "OHEALTH_PACKAGE_LOADED process=" + load.processName);
            de.robv.android.xposed.XposedBridge.log("OplusBandBridge OHEALTH_PACKAGE_LOADED process="
                    + load.processName);
            OHealthLoginDebug.install(load.classLoader);
            XposedHelpers.findAndHookMethod("com.heytap.health.SportHealthApplication", load.classLoader,
                    "onCreate", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()) return;
                            installHealth((Context) param.thisObject, load.classLoader);
                        }
                    });
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Context context = (Context) param.args[0];
                    if (context != null) installHealth(context.getApplicationContext() == null
                            ? context : context.getApplicationContext(), load.classLoader);
                }
            });
            return;
        }
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!installed.compareAndSet(false, true)) return;
                Context context = (Context) param.args[0];
                if ("com.heytap.mydevices".equals(load.packageName)) {
                    try {
                        MyDevicesHook.install(context, load.classLoader);
                        android.util.Log.i("OplusBandBridge", "DEVICE_CARD_HOOK_INSTALLED");
                    } catch (Throwable skipped) {
                        android.util.Log.i("OplusBandBridge", "DEVICE_CARD_HOOK_SKIPPED "
                                + skipped.getClass().getSimpleName()
                                + (skipped.getMessage() == null ? "" : " " + skipped.getMessage()));
                    }
                    return;
                }
                if (!HostIdentity.installed(context, HostIdentity.MI_PACKAGE)) {
                    android.util.Log.i("OplusBandBridge", "MI_PACKAGE_ABSENT");
                    return;
                }
                try {
                    MiFitnessImportHook.install(context, load.classLoader);
                    android.util.Log.i("OplusBandBridge", "OplusBandBridge: IMPORT_HOOK_INSTALLED");
                } catch (Throwable incompatible) {
                    android.util.Log.i("OplusBandBridge", "OplusBandBridge: HOST_VERSION_UNSUPPORTED");
                }
                try {
                    TransportProbeHook.install(context, load.classLoader);
                } catch (Throwable incompatible) {
                    android.util.Log.i("OplusBandBridge", "TRANSPORT_PROBE_UNAVAILABLE");
                }
                try {
                    ProtocolCaptureHook.install(context, load.classLoader);
                } catch (Throwable incompatible) {
                    android.util.Log.i("OplusBandBridge", "PROTOCOL_CAPTURE_UNAVAILABLE");
                }
            }
        });
    }

    private static void installHealth(Context context, ClassLoader loader) {
        if (context == null || !healthInstalled.compareAndSet(false, true)) return;
        android.util.Log.i("OplusBandBridge", "OHEALTH_HOOKS_BEGIN");
        try {
            OHealthWeatherHook.install(context, loader);
            android.util.Log.i("OplusBandBridge", "OHEALTH_WEATHER_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            android.util.Log.i("OplusBandBridge", "HOST_VERSION_UNSUPPORTED_OHEALTH");
        }
        try {
            OHealthDeviceHook.install(context, loader);
            android.util.Log.i("OplusBandBridge", "OHEALTH_DEVICE_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            android.util.Log.i("OplusBandBridge", "OHEALTH_DEVICE_HOOK_UNAVAILABLE");
        }
        try {
            OHealthFindPhoneHook.install(context, loader);
            android.util.Log.i("OplusBandBridge", "OHEALTH_FIND_PHONE_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            android.util.Log.i("OplusBandBridge", "OHEALTH_FIND_PHONE_HOOK_UNAVAILABLE");
        }
        try {
            OHealthMusicHook.install(context, loader);
            android.util.Log.i("OplusBandBridge", "OHEALTH_MUSIC_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            android.util.Log.i("OplusBandBridge", "OHEALTH_MUSIC_HOOK_UNAVAILABLE");
        }
        try {
            OHealthHealthImportHook.install(context, loader);
            android.util.Log.i("OplusBandBridge", "OHEALTH_IMPORT_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            android.util.Log.i("OplusBandBridge", "OHEALTH_IMPORT_HOOK_UNAVAILABLE");
        }
        try {
            OHealthSleepHook.install(context, loader);
            android.util.Log.i("OplusBandBridge", "OHEALTH_SLEEP_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            android.util.Log.i("OplusBandBridge", "OHEALTH_SLEEP_HOOK_UNAVAILABLE");
        }
        try {
            OHealthHomeMetricHook.install(context, loader);
            android.util.Log.i("OplusBandBridge", "OHEALTH_HOME_METRIC_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            android.util.Log.i("OplusBandBridge", "OHEALTH_HOME_METRIC_HOOK_UNAVAILABLE");
        }
        try {
            OHealthNotificationAccessHook.install(loader);
            android.util.Log.i("OplusBandBridge", "OHEALTH_NOTIFICATION_ACCESS_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            android.util.Log.i("OplusBandBridge", "OHEALTH_NOTIFICATION_ACCESS_HOOK_UNAVAILABLE");
        }
    }
}
