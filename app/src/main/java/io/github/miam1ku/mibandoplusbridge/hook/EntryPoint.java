// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.Application;
import android.content.Context;
import androidx.annotation.NonNull;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * libxposed API 102 entry. The only Java entry, so the framework can hot reload it.
 *
 * <p>A hot reload skips the package callbacks. The old generation stops its threads and
 * listeners, then hands over each host package it saw: package name, class loader, and the
 * Application context once it exists. The new generation removes the old hooks and installs its
 * own. Pages the host already built keep what the old code drew on them until they refresh.</p>
 */
public final class EntryPoint extends XposedModule {
    private static final String CLOCK_PACKAGE = "com.coloros.alarmclock";
    private static final String HEALTH_PACKAGE = "com.heytap.health";
    private static final String STATE_PROCESS = "process";
    private static final String STATE_TARGETS = "targets";
    private static final String STATE_CARRIED = "carried";
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicBoolean healthInstalled = new AtomicBoolean();

    private final List<Target> targets = new CopyOnWriteArrayList<>();
    private String processName = "";

    /** One host package in this process. Fields hold host objects only. */
    private static final class Target {
        final String packageName;
        final ClassLoader loader;
        volatile Context context;

        Target(String packageName, ClassLoader loader, Context context) {
            this.packageName = packageName;
            this.loader = loader;
            this.context = context;
        }
    }

    @Override public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        HookRuntime.attach(this);
        processName = param.getProcessName();
    }

    @Override public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!handled(pkg)) return;
        Target target = new Target(pkg, param.getClassLoader(), null);
        targets.add(target);
        dispatch(target);
    }

    @Override public boolean onHotReloading(@NonNull HotReloadingParam param) {
        HookRuntime.close();
        ArrayList<Object[]> saved = new ArrayList<>();
        for (Target target : targets) {
            saved.add(new Object[] {target.packageName, target.loader, target.context});
        }
        HashMap<String, Object> state = new HashMap<>();
        state.put(STATE_PROCESS, processName);
        state.put(STATE_TARGETS, saved);
        state.put(STATE_CARRIED, new HashMap<>(HookRuntime.carriedState()));
        param.setSavedInstanceState(state);
        probe("HOT_RELOAD_LEAVING targets=" + saved.size());
        return true;
    }

    @Override public void onHotReloaded(@NonNull HotReloadedParam param) {
        HookRuntime.attach(this);
        processName = param.getProcessName();
        int old = param.getOldHookHandles().size();
        param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);
        if (!(param.getSavedInstanceState() instanceof Map<?, ?> state)) {
            probe("HOT_RELOAD_WITHOUT_STATE unhooked=" + old);
            return;
        }
        if (state.get(STATE_CARRIED) instanceof Map<?, ?> carried) HookRuntime.restoreCarried(carried);
        if (state.get(STATE_TARGETS) instanceof List<?> saved) {
            for (Object entry : saved) {
                if (!(entry instanceof Object[] values) || values.length != 3
                        || !(values[0] instanceof String pkg) || !(values[1] instanceof ClassLoader loader)) continue;
                Target target = new Target(pkg, loader, values[2] instanceof Context context ? context : null);
                targets.add(target);
                try {
                    dispatch(target);
                } catch (Throwable failure) {
                    probe("HOT_RELOAD_DISPATCH_FAILED pkg=" + pkg + " " + failure.getClass().getSimpleName());
                }
            }
        }
        probe("HOT_RELOADED process=" + processName + " unhooked=" + old + " targets=" + targets.size());
    }

    private static boolean handled(String pkg) {
        return HostIdentity.MI_PACKAGE.equals(pkg) || HostIdentity.DEVICES_PACKAGE.equals(pkg)
                || HEALTH_PACKAGE.equals(pkg) || CLOCK_PACKAGE.equals(pkg);
    }

    /**
     * Installs hooks for one host package. On first load the Application is still missing, so most
     * hooks wait for {@code Application.attach}. After a hot reload the saved context is used directly.
     */
    private void dispatch(Target load) {
        String process = processName;
        if (HostIdentity.MI_PACKAGE.equals(load.packageName)
                || HostIdentity.DEVICES_PACKAGE.equals(load.packageName)
                || CLOCK_PACKAGE.equals(load.packageName)) {
            probe("PACKAGE_LOADED pkg=" + load.packageName + " process=" + process);
        }
        if (CLOCK_PACKAGE.equals(load.packageName)) {
            whenAttached(load, app -> {
                try {
                    ClockAlarmHook.install(app, load.loader);
                } catch (Throwable failure) {
                    probe("CLOCK_ALARM_HOOK_SKIPPED "
                            + failure.getClass().getSimpleName());
                }
            });
            return;
        }
        if (HEALTH_PACKAGE.equals(load.packageName)) {
            probe("OHEALTH_PACKAGE_LOADED process=" + process);
            OHealthLoginDebug.install(load.loader);
            if (load.context != null) {
                installHealth(load.context, load.loader);
                return;
            }
            XposedHelpers.findAndHookMethod("com.heytap.health.SportHealthApplication", load.loader,
                    "onCreate", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()) return;
                            Context context = (Context) param.thisObject;
                            load.context = context;
                            installHealth(context, load.loader);
                        }
                    });
            whenAttached(load, app -> installHealth(app, load.loader));
            return;
        }
        whenAttached(load, context -> {
            if (!installed.compareAndSet(false, true)) return;
            probe("ATTACH process=" + process);
            if (HostIdentity.DEVICES_PACKAGE.equals(load.packageName)) {
                try {
                    MyDevicesHook.install(context, load.loader);
                    probe("DEVICE_CARD_HOOK_INSTALLED");
                } catch (Throwable skipped) {
                    probe("DEVICE_CARD_HOOK_SKIPPED "
                            + skipped.getClass().getSimpleName()
                            + (skipped.getMessage() == null ? "" : " " + skipped.getMessage()));
                }
                return;
            }
            if (!HostIdentity.installed(context, HostIdentity.MI_PACKAGE)) {
                probe("MI_PACKAGE_ABSENT");
                return;
            }
            try {
                MiFitnessImportHook.install(context, load.loader);
                probe("OplusBandBridge: IMPORT_HOOK_INSTALLED");
            } catch (Throwable incompatible) {
                probe("OplusBandBridge: HOST_VERSION_UNSUPPORTED");
            }
            try {
                TransportProbeHook.install(context, load.loader);
            } catch (Throwable incompatible) {
                probe("TRANSPORT_PROBE_UNAVAILABLE");
            }
            try {
                ProtocolCaptureHook.install(context, load.loader);
            } catch (Throwable incompatible) {
                probe("PROTOCOL_CAPTURE_UNAVAILABLE");
            }
        });
    }

    /** Runs now if the host Application is known, else after {@code Application.attach}. */
    private static void whenAttached(Target load, java.util.function.Consumer<Context> action) {
        Context known = load.context;
        if (known != null) {
            action.accept(known);
            return;
        }
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) param.args[0];
                if (context == null) return;
                Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
                if (load.context == null) load.context = app;
                action.accept(app);
            }
        });
    }

    /**
     * LSPosed only relays the framework log into its own log on this device. A plain
     * android.util.Log call from this module stayed off that page. Write to both so a
     * missing line means the hook was not injected, rather than a log on the invisible channel.
     */
    private static void probe(String line) {
        HookRuntime.log(line);
    }

    private static void installHealth(Context context, ClassLoader loader) {
        if (context == null || !healthInstalled.compareAndSet(false, true)) return;
        probe("OHEALTH_HOOKS_BEGIN");
        try {
            OHealthWeatherHook.install(context, loader);
            probe("OHEALTH_WEATHER_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            probe("HOST_VERSION_UNSUPPORTED_OHEALTH");
        }
        try {
            OHealthDeviceHook.install(context, loader);
            probe("OHEALTH_DEVICE_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            probe("OHEALTH_DEVICE_HOOK_UNAVAILABLE");
        }
        try {
            OHealthFindPhoneHook.install(context, loader);
            probe("OHEALTH_FIND_PHONE_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            probe("OHEALTH_FIND_PHONE_HOOK_UNAVAILABLE");
        }
        try {
            OHealthMusicHook.install(context, loader);
            probe("OHEALTH_MUSIC_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            probe("OHEALTH_MUSIC_HOOK_UNAVAILABLE");
        }
        String process = Application.getProcessName();
        if (process != null && process.endsWith(":SportDaemonService")) {
            try {
                installSleepRowDelete(context, loader);
                installSleepStatReplace(loader);
                probe("OHEALTH_SLEEP_DELETE_HOOKED");
            } catch (Throwable incompatible) {
                probe("OHEALTH_SLEEP_DELETE_HOOK_UNAVAILABLE "
                        + incompatible.getClass().getSimpleName());
            }
        }
        try {
            OHealthStepAggregateHook.install(context, loader);
        } catch (Throwable incompatible) {
            probe("OHEALTH_STEP_AUDIT_HOOK_UNAVAILABLE " + incompatible.getClass().getSimpleName());
        }
        try {
            OHealthHealthImportHook.install(context, loader);
            probe("OHEALTH_IMPORT_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            probe("OHEALTH_IMPORT_HOOK_UNAVAILABLE");
        }
        try {
            OHealthLockCardHook.install(context, loader);
            probe("OHEALTH_LOCK_CARD_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            probe("OHEALTH_LOCK_CARD_HOOK_UNAVAILABLE");
        }
        try {
            OHealthSleepHook.install(context, loader);
            probe("OHEALTH_SLEEP_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            probe("OHEALTH_SLEEP_HOOK_UNAVAILABLE");
        }
        try {
            OHealthHomeMetricHook.install(context, loader);
            probe("OHEALTH_HOME_METRIC_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            probe("OHEALTH_HOME_METRIC_HOOK_UNAVAILABLE");
        }
        try {
            OHealthNotificationAccessHook.install(context, loader);
            probe("OHEALTH_NOTIFICATION_ACCESS_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            probe("OHEALTH_NOTIFICATION_ACCESS_HOOK_UNAVAILABLE");
        }
        try {
            OHealthDndHook.install(context);
            probe("OHEALTH_DND_HOOK_INSTALLED");
        } catch (Throwable incompatible) {
            probe("OHEALTH_DND_HOOK_UNAVAILABLE");
        }
    }

    /**
     * Table 1010 delete returns 0 and removes nothing. This process owns the database,
     * so drop rows by start time. The caller's end is inclusive.
     */
    private static void installSleepRowDelete(Context context, ClassLoader loader) {
        XposedHelpers.findAndHookMethod("com.heytap.databaseengineservice.store.SportDataStore", loader,
                "delete", "com.heytap.databaseengine.option.DataDeleteOption", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object option = param.args[0];
                        if (option == null) return;
                        if ((Integer) option.getClass().getMethod("getDataTable").invoke(option)
                                != OHealthSleepWriter.TABLE_SLEEP) return;
                        String account = (String) option.getClass().getMethod("getSsoid").invoke(option);
                        String device = (String) option.getClass().getMethod("getDeviceUniqueId").invoke(option);
                        long start = (Long) option.getClass().getMethod("getStartTime").invoke(option);
                        long end = (Long) option.getClass().getMethod("getEndTime").invoke(option);
                        if (account == null || account.isBlank() || device == null || device.isBlank()
                                || end < start) return;
                        Class<?> dbClass = Class.forName(
                                "com.heytap.databaseengineservice.db.AppDatabase", false, loader);
                        Object database = dbClass.getMethod("getInstance", Context.class).invoke(null, context);
                        Object helper = database.getClass().getMethod("getOpenHelper").invoke(database);
                        Object sqlite = helper.getClass().getMethod("getWritableDatabase").invoke(helper);
                        sqlite.getClass().getMethod("execSQL", String.class, Object[].class).invoke(sqlite,
                                new Object[] {
                                        "DELETE FROM DBSleepTable WHERE ssoid = ? AND device_unique_id = ?"
                                                + " AND start_time >= ? AND start_time <= ?",
                                        new Object[] {account, device, start, end}
                                });
                        param.setResult(0);
                    }
                });
    }

    /**
     * An API save of table 1011 passes keep-old and leaves the existing totals in place.
     * The corrected night overwrites the stacked summary.
     */
    private static void installSleepStatReplace(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(
                "com.heytap.databaseengineservice.store.stat.SleepStatProcess", loader,
                "getUpdateData", long.class, long.class,
                "com.heytap.databaseengineservice.db.table.DBSleepDataStat",
                "com.heytap.databaseengineservice.db.table.DBSleepDataStat",
                boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(param.args[4])) param.setResult(param.args[1]);
                    }
                });
    }


}
