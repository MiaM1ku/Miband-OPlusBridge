// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Projects the registered Xiaomi device into OHealth without modifying other device identities. */
public final class OHealthDeviceHook {
    private static final String HOST = "com.heytap.health";
    private static final String CONTROLLER = "com.heytap.health.device.tab.controller.DeviceTabAdapterController";
    private static final String INFO = "com.heytap.health.devicemanager.processor.bean.UserDeviceInfo";
    private static final String WEARABLE = "com.heytap.health.device.tab.bean.WearableDevice";
    private static final String CONSTANTS = "com.heytap.health.device_manager_base.DeviceConstants";

    private static volatile Bundle snapshot;
    private static volatile String remembered;
    private static int snapshotAttempts;
    private static boolean snapshotTraced;

    private static Handler worker;
    private static Handler main;
    private static final List<WeakReference<Object>> CONTROLLERS = new ArrayList<>();
    private static final ThreadLocal<Boolean> APPENDING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> REFRESHING = ThreadLocal.withInitial(() -> false);

    private OHealthDeviceHook() {}
    private static Context hostContext;
    public static void install(Context context, ClassLoader loader) throws Exception {
        hostContext = context.getApplicationContext();
        // Application.attach runs before ActivityThread publishes the Application instance.
        if (hostContext == null) hostContext = context;
        if (!HOST.equals(context.getPackageName())) return;
        main = new Handler(Looper.getMainLooper());
        XposedHelpers.findAndHookMethod(CONTROLLER, loader, "refreshWearableDeviceList",
                List.class, List.class, String.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        remember(param.thisObject);
                        if (APPENDING.get() || !(param.args[0] instanceof List<?> current)) return;
                        APPENDING.set(true);
                        try {
                            Bundle shown = snapshot;
                            if (shown == null) return;
                            Object info = shown.getBoolean("registered", false) ? deviceInfo(loader, shown) : null;
                            ArrayList<Object> combined = new ArrayList<>(current.size() + (info == null ? 0 : 1));
                            for (Object item : current) if (!matches(item, shown)) combined.add(item);
                            if (info != null) combined.add(info);
                            param.args[0] = combined;
                        } catch (Throwable failure) {
                            Log.i("OplusBandBridge", "OHEALTH_DEVICE_ROW_UNAVAILABLE "
                                    + failure.getClass().getSimpleName());
                        } finally {
                            APPENDING.set(false);
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getThrowable() != null) {
                            Log.i("OplusBandBridge", "OHEALTH_DEVICE_REFRESH_FAILED "
                                    + param.getThrowable().getClass().getSimpleName());
                        } else {
                            project(param.thisObject, loader, null, false);
                        }
                    }
                });
        Class<?> eventType = XposedHelpers.findClass(
                "com.heytap.health.device.flexadapter.refresh.EventType", loader);
        XposedHelpers.findAndHookMethod(CONTROLLER, loader, "refreshView", eventType, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                remember(param.thisObject);
                project(param.thisObject, loader, null, false);
            }
        });
        Class<?> infoClass = XposedHelpers.findClass(INFO, loader);
        XposedHelpers.findAndHookMethod(CONTROLLER, loader, "checkDeviceIsDisable", infoClass,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (isBand(param.args.length == 0 ? null : param.args[0])) param.setResult(false);
                    }
                });
        XposedHelpers.findAndHookMethod(CONTROLLER, loader, "doConnect", infoClass, String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isBand(param.args.length == 0 ? null : param.args[0])) return;
                        param.setResult(null);
                        requestSync();
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.heytap.health.devicemanagerimpl.host.HDM2Connect", loader, "retryConnect", String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object mac = XposedHelpers.getObjectField(param.thisObject, "mCurrActiveMac");
                        Bundle shown = snapshot;
                        if (shown != null && io.github.miam1ku.mibandoplusbridge.data.MacIds.same(
                                shown.getString("mac", ""), String.valueOf(mac))) {
                            param.setResult(null);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(CONTROLLER, loader, "resetCurrSelectMac", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                remember(param.thisObject);
                // Must run before the host discards a selected MAC absent from its list.
                project(param.thisObject, loader, null, false);
            }
        });
        installDevicePages(loader);
        installNativePanel(loader);
        observeSnapshots(loader);
    }

    /** UI hooks only read this immutable, process-local IPC snapshot. */
    private static Bundle display() {
        Bundle shown = snapshot;
        return shown != null && shown.getBoolean("registered", false) ? shown : null;
    }

    public static Bundle registeredSnapshot() {
        Bundle shown = display();
        return shown == null ? null : new Bundle(shown);
    }

    /**
     * Official rows use the Bluetooth MAC as deviceUniqueId.
     * The bridge queue keeps the miband11 id.
     */
    public static String healthId(String bridgeId) {
        Bundle band = registeredSnapshot();
        if (band == null || bridgeId == null) return bridgeId;
        String mac = band.getString("mac", "");
        String id = band.getString("deviceId", "");
        if (mac.isBlank() || id.isBlank() || !id.equals(bridgeId)) return bridgeId;
        return mac;
    }

    /** Writes this band into the account's bound-device list. The list stays after the module stops. */
    public static void rememberBand(ClassLoader loader) {
        Bundle shown = registeredSnapshot();
        if (shown == null || loader == null) return;
        String mac = shown.getString("mac", "");
        if (mac.isBlank()) return;
        try {
            Class<?> accounts = Class.forName("com.heytap.health.account.AccountHelper", false, loader);
            Object manager = accounts.getMethod("getAccountManager").invoke(null);
            String ssoid = String.valueOf(manager.getClass().getMethod("getSsoid").invoke(manager));
            if (ssoid.isBlank() || "com.heytap.health".equals(ssoid) || "null".equals(ssoid)) return;
            String key = ssoid + "|" + mac;
            if (key.equals(remembered)) return;
            Object info = deviceInfo(loader, shown);
            if (info == null) return;
            Class<?> local = Class.forName(
                    "com.heytap.health.devicemanagerimpl.processor.DMLocalDeviceManager", false, loader);
            Object instance = local.getField("INSTANCE").get(null);
            local.getMethod("saveUserBoundDevice", info.getClass()).invoke(instance, info);
            remembered = key;
            Log.i("OplusBandBridge", "OHEALTH_DEVICE_REMEMBERED");
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_DEVICE_REMEMBER_FAILED "
                    + failure.getClass().getSimpleName());
        }
    }

    /** True when the health app's current or third-party selection is this band. */
    public static boolean matchesActiveDevice(Object manager, Object allRole) {
        Bundle band = registeredSnapshot();
        if (band == null || manager == null) return false;
        String mac = band.getString("mac", "");
        try {
            if (io.github.miam1ku.mibandoplusbridge.data.MacIds.same(mac, String.valueOf(
                    de.robv.android.xposed.XposedHelpers.callMethod(manager, "getThirdpartySelectMac")))) return true;
            return allRole != null && io.github.miam1ku.mibandoplusbridge.data.MacIds.same(mac, String.valueOf(
                    de.robv.android.xposed.XposedHelpers.callMethod(manager, "getCurrActiveMacByRole", allRole)));
        } catch (Throwable unavailable) { return false; }
    }

    private static void observeSnapshots(ClassLoader loader) {
        HandlerThread thread = new HandlerThread("OplusBandDeviceSnapshot");
        thread.start();
        worker = new Handler(thread.getLooper());
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            Bundle result = fromProvider();
            main.post(() -> {
                Bundle previous = snapshot;
                if (result != null) {
                    snapshot = result;
                } else if (previous != null) {
                    Bundle offline = new Bundle(previous);
                    offline.putBoolean("connected", false);
                    snapshot = offline;
                }
                for (Object controller : liveControllers()) {
                    project(controller, loader, previous, true);
                }
            });
            // The first provider call often races process start. A miss used to leave the device tab empty.
            boolean giveUp = result == null && snapshotAttempts >= 14;
            boolean nowRegistered = result != null && result.getBoolean("registered");
            Bundle shownNow = snapshot;
            boolean wasRegistered = shownNow != null && shownNow.getBoolean("registered", false);
            if ((!snapshotTraced && (result != null || giveUp)) || (result != null && nowRegistered != wasRegistered)) {
                snapshotTraced = true;
                trace(result == null ? "OHEALTH_DEVICE_SNAPSHOT missing"
                        : "OHEALTH_DEVICE_SNAPSHOT registered=" + result.getBoolean("registered")
                        + " connected=" + result.getBoolean("connected"));
            }
            if (result == null && snapshotAttempts < 15) {
                if (snapshotAttempts == 0) Log.i("OplusBandBridge", "OHEALTH_DEVICE_SNAPSHOT_RETRY");
                snapshotAttempts++;
                worker.postDelayed(refresh[0], 2_000);
            } else snapshotAttempts = 0;
        };
        ContentObserver observer = new ContentObserver(worker) {
            @Override public void onChange(boolean selfChange) {
                worker.removeCallbacks(refresh[0]);
                worker.post(refresh[0]);
            }
        };
        worker.post(() -> {
            try {
                hostContext.getContentResolver().registerContentObserver(DeviceCardProvider.URI, true, observer);
            } catch (Throwable failure) {
                Log.i("OplusBandBridge", "OHEALTH_OBSERVER_UNAVAILABLE " + failure.getClass().getSimpleName());
            }
            refresh[0].run();
        });
    }

    private static Bundle fromProvider() {
        try {
            Bundle result = hostContext.getContentResolver().call(DeviceCardProvider.URI, "bandDisplay", null, null);
            // Absence/malformed IPC is unknown, never an implicit removal.
            if (result == null || !(result.get("registered") instanceof Boolean)) return null;
            if (result.getBoolean("registered") && (result.getString("deviceId", "").isBlank()
                    || result.getString("mac", "").isBlank() || result.getString("name", "").isBlank())) return null;
            // An empty state file is not an unregister. Unregister keeps the MAC and device id.
            if (!result.getBoolean("registered") && result.getString("deviceId", "").isBlank()
                    && result.getString("mac", "").isBlank()) return null;
            return new Bundle(result);
        } catch (Throwable failure) {
            String detail = failure.getMessage() == null ? "" : failure.getMessage();
            detail = detail.replaceAll("[0-9A-Fa-f]{8,}", "#");
            if (detail.length() > 180) detail = detail.substring(0, 180);
            Log.i("OplusBandBridge", "OHEALTH_PROVIDER_UNAVAILABLE " + failure.getClass().getSimpleName()
                    + " " + detail);
            return null;
        }
    }


    private static void remember(Object controller) {
        synchronized (CONTROLLERS) {
            boolean found = false;
            for (int i = CONTROLLERS.size() - 1; i >= 0; i--) {
                Object current = CONTROLLERS.get(i).get();
                if (current == null) CONTROLLERS.remove(i);
                else if (current == controller) found = true;
            }
            if (!found) CONTROLLERS.add(new WeakReference<>(controller));
        }
    }

    private static List<Object> liveControllers() {
        ArrayList<Object> live = new ArrayList<>();
        synchronized (CONTROLLERS) {
            for (int i = CONTROLLERS.size() - 1; i >= 0; i--) {
                Object controller = CONTROLLERS.get(i).get();
                if (controller == null) CONTROLLERS.remove(i);
                else live.add(controller);
            }
        }
        return live;
    }

    private static void project(Object controller, ClassLoader loader, Bundle previous, boolean refreshView) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            WeakReference<Object> reference = new WeakReference<>(controller);
            main.post(() -> {
                Object live = reference.get();
                if (live != null) project(live, loader, previous, refreshView);
            });
            return;
        }
        if (REFRESHING.get()) return;
        REFRESHING.set(true);
        try {
            Bundle shown = snapshot;
            if (shown == null) return;
            if (previous != null && !sameIdentity(previous, shown)) removeListed(controller, previous);
            if (!shown.getBoolean("registered", false)) {
                removeListed(controller, shown);
                return;
            }
            Object info = keepListed(controller, loader, shown);
            if (refreshView && info != null) {
                // refreshView alone does not rebuild an empty wearable list. Feed the same host path.
                java.util.ArrayList<Object> seed = new java.util.ArrayList<>();
                seed.add(info);
                XposedHelpers.callMethod(controller, "refreshWearableDeviceList", seed, new java.util.ArrayList<>(), "band snapshot");
            }
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_DEVICE_ROW_UNAVAILABLE " + failure.getClass().getSimpleName());
        } finally {
            REFRESHING.set(false);
        }
    }

    private static boolean sameIdentity(Bundle first, Bundle second) {
        return first.getString("deviceId", "").equals(second.getString("deviceId", ""))
                && first.getString("mac", "").equalsIgnoreCase(second.getString("mac", ""));
    }

    private static void requestSync() {
        worker.post(() -> {
            String status = null;
            try {
                Bundle response = hostContext.getContentResolver().call(DeviceCardProvider.URI, "requestSync", null, null);
                if (response != null) status = response.getString("status");
            } catch (Throwable failure) {
                Log.i("OplusBandBridge", "OHEALTH_SYNC_UNAVAILABLE " + failure.getClass().getSimpleName());
            }
            if ("OPEN_CONFIG_REQUIRED".equals(status)) {
                Log.i("OplusBandBridge", "OHEALTH_SYNC_DEFERRED");
            }
        });
    }



    private static Object deviceInfo(ClassLoader loader, Bundle display) throws Exception {
        Class<?> constants = XposedHelpers.findClass(CONSTANTS, loader);
        // Native pages only load for an OPPO band model. The Xiaomi MAC and device id stay real.
        String model = (String) XposedHelpers.getStaticObjectField(constants, "BAND_DEVICE_MODEL");
        int type = wearableType(constants, model);
        if (type < 0 || model == null || model.isBlank()) {
            Log.i("OplusBandBridge", "OHEALTH_DEVICE_TYPE_MISSING");
            return null;
        }
        Object info = XposedHelpers.newInstance(XposedHelpers.findClass(INFO, loader));
        String mac = display.getString("mac", "");
        XposedHelpers.callMethod(info, "setMac", mac);
        XposedHelpers.callMethod(info, "setDeviceUniqueId", mac.isBlank()
                ? display.getString("deviceId") : mac);
        XposedHelpers.callMethod(info, "setModel", model);
        XposedHelpers.callMethod(info, "setDeviceType", type);
        XposedHelpers.callMethod(info, "setManufacturer", "OPPO");
        String terminal = (String) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("com.heytap.health.base.app.SystemUtils", loader), "getAndroidId");
        if (terminal != null && !terminal.isBlank()) XposedHelpers.callMethod(info, "setAppTerminalId", terminal);
        XposedHelpers.callMethod(info, "setBleMac", display.getString("mac"));
        updateInfo(info, display);
        return info;
    }

    private static boolean isBand(Object info) {
        return matches(info, snapshot);
    }

    private static boolean matches(Object info, Bundle shown) {
        if (info == null || shown == null) return false;
        String mac = shown.getString("mac", "");
        String deviceId = shown.getString("deviceId", "");
        return io.github.miam1ku.mibandoplusbridge.data.MacIds.same(mac, String.valueOf(XposedHelpers.callMethod(info, "getMac")))
                || (!deviceId.isBlank() && deviceId.equals(XposedHelpers.callMethod(info, "getDeviceUniqueId")));
    }

    private static void updateInfo(Object info, Bundle shown) {
        XposedHelpers.callMethod(info, "setDeviceName", shown.getString("name", ""));
        int battery = shown.getInt("battery", -1);
        XposedHelpers.callMethod(info, "setCapacityPercent", battery >= 0 && battery <= 100 ? battery : -1);
        XposedHelpers.callMethod(info, "setConnectionState", shown.getBoolean("connected") ? 102 : 103);
        XposedHelpers.callMethod(info, "setChargeStatus", shown.getBoolean("charging") ? 1 : 0);
        XposedHelpers.callMethod(info, "setFirmwareVersion", shown.getString("firmware", ""));
        XposedHelpers.callMethod(info, "setBindingTime", shown.getLong("registeredAtMs", 0));
    }

    private static Object keepListed(Object controller, ClassLoader loader, Bundle shown) throws Exception {
        Object list = XposedHelpers.getObjectField(controller, "deviceList");
        XposedHelpers.callMethod(list, "writeLock");
        try {
            for (Object device : (Iterable<?>) list) {
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(device, "isWearableDevice"))) continue;
                Object info = XposedHelpers.callMethod(device, "getData");
                if (matches(info, shown)) {
                    updateInfo(info, shown);
                    return info;
                }
            }
            Object info = deviceInfo(loader, shown);
            if (info != null) XposedHelpers.callMethod(list, "add",
                    XposedHelpers.newInstance(XposedHelpers.findClass(WEARABLE, loader), info));
            return info;
        } finally {
            XposedHelpers.callMethod(list, "writeUnLock");
        }
    }

    private static void removeListed(Object controller, Bundle identity) {
        Object list = XposedHelpers.getObjectField(controller, "deviceList");
        String mac = null;
        XposedHelpers.callMethod(list, "readLock");
        try {
            for (Object device : (Iterable<?>) list) {
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(device, "isWearableDevice"))) continue;
                Object info = XposedHelpers.callMethod(device, "getData");
                if (matches(info, identity)) {
                    mac = (String) XposedHelpers.callMethod(info, "getMac");
                    break;
                }
            }
        } finally {
            XposedHelpers.callMethod(list, "readUnLock");
        }
        // This host method removes only the local row; never invoke remote unbind.
        if (mac != null && !mac.isBlank()) XposedHelpers.callMethod(controller, "deleteDeviceByMac", mac);
    }

    private static final String WEARABLE_ITEM =
            "com.heytap.health.device.tab.itemview.wearable.";
    private static final Set<String> HIDE_ITEMS = Set.of(
            "DeviceWatchFaceItem", "MenuWatchFaceCenterItem", "MenuWatchFaceOfficialItem", "MenuClockItem");
    private static final Map<String, String> NATIVE_PAGES = Map.ofEntries(
            Map.entry("MenuHeartRateItem", "com.heytap.health.heartrate.ui.HeartRateHistoryActivity"),
            Map.entry("MenuSleepItem", "com.heytap.health.sleep.SleepHistoryActivity"),
            Map.entry("MenuDailyActivityItem", "com.heytap.health.daily.ui.DailyActivityDetailActivity"),
            Map.entry("MenuSportHealthItem", "com.heytap.health.daily.ui.DailyActivityDetailActivity"),
            Map.entry("MenuBloodOxygenItem", "com.heytap.health.bloodoxygen.ui.BloodOxygenHistoryActivity"),
            Map.entry("MenuPressureItem", "com.heytap.health.stress.ui.StressHistoryActivity"),
            Map.entry("MenuNotificationItem", "com.heytap.health.device.tab.notify.NotifySettingsActivity"));

    /** Discover wearable rows from BaseWearableItem. Names are matched after the scan. */
    private static void installDevicePages(ClassLoader loader) {
        Class<?> eventType = XposedHelpers.findClass(
                "com.heytap.health.device.flexadapter.refresh.EventType", loader);
        List<String> items = wearableItemNames(loader);
        if (items.isEmpty()) {
            items = new ArrayList<>();
            for (String name : HIDE_ITEMS) items.add(WEARABLE_ITEM + name);
            for (String name : NATIVE_PAGES.keySet()) items.add(WEARABLE_ITEM + name);
            Log.i("OplusBandBridge", "OHEALTH_WEARABLE_SCAN_EMPTY");
        }
        for (String name : HIDE_ITEMS) {
            String className = WEARABLE_ITEM + name;
            if (!items.contains(className)) items.add(className);
        }
        for (String className : items) {
            String simple = className.substring(className.lastIndexOf('.') + 1);
            if (hideWearableRow(simple)) {
                hideWatchFace(className, loader,
                        "DeviceWatchFaceItem".equals(simple) ? eventType : null,
                        "DeviceWatchFaceItem".equals(simple) ? "java.util.Map" : "kotlin.Unit");
            }
            String activity = NATIVE_PAGES.get(simple);
            if (activity != null) openNativeHistory(className, activity, loader);
        }
        installHostNotifications(loader);
        installNotifyExtras(loader);
    }

    private static boolean hideWearableRow(String simple) {
        return HIDE_ITEMS.contains(simple)
                || simple.contains("Ota")
                || simple.contains("Firmware")
                || simple.contains("NewFunction")
                || simple.endsWith("UpdateItem");
    }

    private static List<String> wearableItemNames(ClassLoader loader) {
        Class<?> base;
        try {
            base = Class.forName(WEARABLE_ITEM + "BaseWearableItem", false, loader);
        } catch (ClassNotFoundException missing) {
            return List.of();
        }
        android.content.pm.ApplicationInfo info = hostContext.getApplicationInfo();
        List<String> apks = new ArrayList<>();
        if (info.sourceDir != null) apks.add(info.sourceDir);
        if (info.splitSourceDirs != null) {
            for (String split : info.splitSourceDirs) apks.add(split);
        }
        List<String> names = new ArrayList<>();
        for (String apk : apks) {
            try {
                for (String name : DexAnchors.classNames(apk)) {
                    if (!name.startsWith(WEARABLE_ITEM) || name.indexOf('$') >= 0) continue;
                    Class<?> type;
                    try {
                        type = Class.forName(name, false, loader);
                    } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                        continue;
                    }
                    if (type != base && base.isAssignableFrom(type)
                            && !Modifier.isAbstract(type.getModifiers())) {
                        names.add(name);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return names;
    }

    private static void installNotifyExtras(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.device.tab.notify.NotifySettingsActivity", loader,
                    "showSuccessFragment", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof android.app.Activity activity)) return;
                            Intent intent = activity.getIntent();
                            if (intent.getBundleExtra("settingsDeviceMacBundle") != null) return;
                            Bundle shown = snapshot;
                            String mac = shown == null ? "" : shown.getString("mac", "");
                            if (mac.isBlank()) return;
                            Bundle device = new Bundle();
                            device.putString("settingsDeviceMac", mac);
                            intent.putExtra("settingsDeviceMac", mac);
                            intent.putExtra("settingsDeviceMacBundle", device);
                        }
                    });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_NOTIFY_EXTRA_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.devicemanager.devicetype.DeviceTypeUtil", loader,
                    "getBoundDeviceByMac", String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object info = bandDevice(loader, param.args.length == 0 ? null : param.args[0]);
                            if (info != null) param.setResult(info);
                        }
                    });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_NOTIFY_DEVICE_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.devicemanager.devicetype.DeviceTypeUtil", loader,
                    "getBoundDeviceInfoByMac", String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object info = bandDevice(loader, param.args.length == 0 ? null : param.args[0]);
                            if (info != null) param.setResult(info);
                        }
                    });
        } catch (Throwable ignored) { }
        try {
            Class<?> hey = XposedHelpers.findClass("com.heytap.health.devicemanager.client.DMHeytap", loader);
            java.lang.reflect.Field api = hey.getField("managerApi");
            XC_MethodHook byMac = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object info = bandDevice(loader, param.args.length == 0 ? null : param.args[0]);
                    if (info != null) param.setResult(info);
                }
            };
            XposedBridge.hookAllMethods(api.getType(), "getBoundDeviceInfoByMac", byMac);
            XposedBridge.hookAllMethods(api.getType(), "getBoundDeviceInfos", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Bundle shown = liveSnapshot();
                    if (shown == null) return;
                    Object info = bandDevice(loader, shown.getString("mac"));
                    if (info == null) return;
                    Object raw = param.getResult();
                    ArrayList<Object> combined = new ArrayList<>();
                    if (raw instanceof List<?> current) {
                        for (Object item : current) if (!matches(item, shown)) combined.add(item);
                    }
                    combined.add(info);
                    param.setResult(combined);
                }
            });
            XposedBridge.hookAllMethods(api.getType(), "getCurrActiveMacByRole", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result != null && !String.valueOf(result).isBlank()
                            && !"null".equals(String.valueOf(result))) return;
                    Bundle shown = liveSnapshot();
                    String mac = shown == null ? "" : shown.getString("mac", "");
                    if (mac.isBlank()) {
                        Log.i("OplusBandBridge", "OHEALTH_ACTIVE_MAC empty");
                        return;
                    }
                    param.setResult(mac);
                    Log.i("OplusBandBridge", "OHEALTH_ACTIVE_MAC filled");
                }
            });
            java.lang.reflect.Field business = hey.getField("businessApi");
            XposedBridge.hookAllMethods(business.getType(), "getDeviceBattery", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Bundle shown = liveSnapshot();
                    if (shown == null || param.args.length == 0) return;
                    if (!io.github.miam1ku.mibandoplusbridge.data.MacIds.same(
                            shown.getString("mac", ""), String.valueOf(param.args[0]))) return;
                    int battery = shown.getInt("battery", -1);
                    if (battery >= 0 && battery <= 100) param.setResult(battery);
                }
            });
            XposedBridge.hookAllMethods(business.getType(), "requestDeviceBattery", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Bundle shown = liveSnapshot();
                    if (shown != null && param.args.length > 0
                            && io.github.miam1ku.mibandoplusbridge.data.MacIds.same(
                                    shown.getString("mac", ""), String.valueOf(param.args[0]))) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_BOUND_INFO_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.deviceability.NotificationBaseAbilityTool", loader,
                    "buildByMac", String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object info = bandDevice(loader, param.args.length == 0 ? null : param.args[0]);
                            if (info == null) return;
                            try {
                                param.setResult(XposedHelpers.newInstance(XposedHelpers.findClass(
                                        "com.heytap.health.deviceability.NotificationBaseAbilityDevice",
                                        loader), info));
                            } catch (Throwable failure) {
                                Log.i("OplusBandBridge", "OHEALTH_NOTIFY_ABILITY_UNAVAILABLE "
                                        + failure.getClass().getSimpleName());
                            }
                        }
                    });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_NOTIFY_ABILITY_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.deviceability.NotificationBaseAbilityDevice", loader,
                    "isSupportFluid", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (isBand(param.thisObject)) param.setResult(false);
                            } catch (Throwable ignored) { }
                        }
                    });
        } catch (Throwable ignored) { }
        XC_MethodHook bandFalse = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (isBand(param.thisObject)) param.setResult(false);
                } catch (Throwable ignored) { }
            }
        };
        for (String method : new String[]{"isSecondaryDevice", "isFamilyDevice"}) {
            try {
                XposedHelpers.findAndHookMethod(
                        "com.heytap.health.devicemanager.deviceability.DeviceInfo", loader,
                        method, bandFalse);
            } catch (Throwable ignored) { }
        }
        try {
            Class<?> fragment = XposedHelpers.findClass(
                    "com.heytap.health.watch.notification.impl.ui.NotificationSyncFragment", loader);
            XposedBridge.hookAllMethods(fragment, "showScreen", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object vm = XposedHelpers.callMethod(param.thisObject, "getVm");
                        if (!(XposedHelpers.callMethod(vm, "successData") instanceof java.util.List<?>)) {
                            Log.i("OplusBandBridge", "OHEALTH_NOTIFY_SCREEN empty");
                            param.setResult(null);
                        }
                    } catch (Throwable failure) {
                        Log.i("OplusBandBridge", "OHEALTH_NOTIFY_SCREEN "
                                + failure.getClass().getSimpleName());
                    }
                }
            });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_NOTIFY_SCREEN_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    private static Object bandDevice(ClassLoader loader, Object mac) {
        Bundle shown = snapshot;
        if (shown == null || !shown.getBoolean("registered", false) || mac == null) return null;
        if (!io.github.miam1ku.mibandoplusbridge.data.MacIds.same(shown.getString("mac", ""), String.valueOf(mac))) {
            return null;
        }
        try {
            return deviceInfo(loader, shown);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Native OPPO panel reads DeviceSdk/DMHeytap, not the device-center card.
     * Project this band's connection and battery, and drop watch-face/OTA rows.
     */
    private static void installNativePanel(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.linkage.ui.DeviceDetailsPanelActivity", loader,
                    "showNewFunctionItem", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (panelIsBand(param.thisObject)) param.setResult(false);
                        }
                    });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_PANEL_FUNCTION_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.linkage.ui.DeviceDetailsViewModel", loader,
                    "initData", String.class, String.class, String.class, String.class,
                    boolean.class, boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            applyPanelState(param.thisObject,
                                    param.args.length > 2 ? param.args[2] : null,
                                    param.args.length > 0 ? param.args[0] : null);
                        }
                    });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_PANEL_INIT_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.linkage.ui.DeviceDetailsViewModel", loader,
                    "getData", boolean.class, String.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object mac = XposedHelpers.getObjectField(param.thisObject, "mMac");
                            Object deviceId = XposedHelpers.getObjectField(param.thisObject, "mDeviceId");
                            if (!ourBand(mac, deviceId)) return;
                            applyPanelState(param.thisObject, mac, deviceId);
                            XposedHelpers.setBooleanField(param.thisObject, "mShowNewFunctionItem", false);
                            if (param.args.length > 3) param.args[3] = Boolean.FALSE;
                        }
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            filterPanelButtons(param.thisObject);
                        }
                    });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_PANEL_DATA_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.linkage.ui.DeviceDetailsViewModel", loader,
                    "reconnectDevice", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!ourBand(XposedHelpers.getObjectField(param.thisObject, "mMac"),
                                    XposedHelpers.getObjectField(param.thisObject, "mDeviceId"))) return;
                            param.setResult(null);
                            requestSync();
                        }
                    });
        } catch (Throwable ignored) { }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.linkage.ui.DeviceDetailsActivity", loader,
                    "actionClick", Integer.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof android.app.Activity activity)) return;
                            if (!panelIsBand(activity)) return;
                            if (!(param.args[0] instanceof Integer action) || action != 4) return;
                            if (!openHealthApp(activity)) return;
                            param.setResult(null);
                        }
                    });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_PANEL_APP_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.linkage.ui.DeviceDetailsPanelActivity", loader,
                    "notifyDeviceInfoBeans", List.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!panelIsBand(param.thisObject)) return;
                            stripWatchFace(param.thisObject, "datas", "adapter");
                            stripWatchFace(param.thisObject, "landscapeRightDatas", "landscapeRightAdapter");
                        }
                    });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_PANEL_WATCHFACE_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.mydevices.sdk.DeviceInfoManager", loader,
                    "queryDeviceById", String.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length == 0) return;
                            Bundle shown = liveSnapshot();
                            if (shown == null) return;
                            if (!shown.getString("deviceId", "").equals(String.valueOf(param.args[0]))
                                    && !String.valueOf(param.args[0]).startsWith("miband11_")) return;
                            Object info = param.getResult();
                            if (info == null) return;
                            patchSdkDevice(info, shown);
                        }
                    });
        } catch (Throwable ignored) { }
    }

    private static Bundle liveSnapshot() {
        Bundle current = display();
        if (current != null) return current;
        Bundle fresh = fromProvider();
        if (fresh != null) snapshot = fresh;
        return display();
    }

    private static boolean ourMac(Object mac) {
        Bundle shown = liveSnapshot();
        return shown != null && io.github.miam1ku.mibandoplusbridge.data.MacIds.same(
                shown.getString("mac", ""), String.valueOf(mac));
    }

    private static boolean ourBand(Object mac, Object deviceId) {
        String id = deviceId == null ? "" : String.valueOf(deviceId);
        if (id.startsWith("miband11_")) return true;
        return ourMac(mac);
    }

    private static boolean panelIsBand(Object panel) {
        try {
            return ourBand(XposedHelpers.getObjectField(panel, "mMac"),
                    XposedHelpers.getObjectField(panel, "mDeviceId"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean openHealthApp(android.app.Activity activity) {
        try {
            Object mac = XposedHelpers.getObjectField(activity, "mMac");
            Intent intent = new Intent();
            intent.setClassName(HOST, "com.heytap.health.main.MainActivity");
            if (mac != null) {
                String address = String.valueOf(mac);
                if (!address.isBlank() && !"null".equals(address)) intent.putExtra("currentMac", address);
            }
            intent.putExtra("tab", "3");
            intent.putExtra("from_internal", false);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
            activity.finish();
            Log.i("OplusBandBridge", "OHEALTH_PANEL_OPEN_APP");
            return true;
        } catch (RuntimeException failure) {
            Log.i("OplusBandBridge", "OHEALTH_PANEL_OPEN_APP_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static void applyPanelState(Object vm, Object mac, Object deviceId) {
        if (!ourBand(mac, deviceId)) return;
        Bundle shown = liveSnapshot();
        if (shown == null) return;
        int battery = shown.getInt("battery", -1);
        try {
            XposedHelpers.setIntField(vm, "mCurrBattery", battery >= 0 && battery <= 100 ? battery : 0);
            XposedHelpers.setBooleanField(vm, "mCharge", shown.getBoolean("charging"));
            XposedHelpers.setIntField(vm, "mConnectStatus", shown.getBoolean("connected") ? 102 : 103);
            Log.i("OplusBandBridge", "OHEALTH_PANEL_STATE connected=" + shown.getBoolean("connected")
                    + " battery=" + battery);
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_PANEL_STATE_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    private static void filterPanelButtons(Object vm) {
        if (!ourBand(XposedHelpers.getObjectField(vm, "mMac"),
                XposedHelpers.getObjectField(vm, "mDeviceId"))) return;
        try {
            Object live = XposedHelpers.getObjectField(vm, "mListLiveData");
            Object value = XposedHelpers.callMethod(live, "getValue");
            if (!(value instanceof List<?> rows) || rows.isEmpty()) return;
            ArrayList<Object> kept = new ArrayList<>(rows.size());
            boolean changed = false;
            for (Object bean : rows) {
                int type = (Integer) XposedHelpers.callMethod(bean, "getType");
                if (type != 2) {
                    kept.add(bean);
                    continue;
                }
                int action = 0;
                try {
                    action = (Integer) XposedHelpers.callMethod(bean, "getClickAction");
                } catch (Throwable ignored) { }
                if (action == 4) kept.add(bean);
                else changed = true;
            }
            if (changed) XposedHelpers.callMethod(live, "postValue", kept);
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_PANEL_FILTER_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    private static void stripWatchFace(Object panel, String listField, String adapterField) {
        try {
            Object rows = XposedHelpers.getObjectField(panel, listField);
            if (!(rows instanceof List<?> current) || current.isEmpty()) return;
            ArrayList<Object> kept = new ArrayList<>();
            boolean changed = false;
            for (Object item : current) {
                if (item != null && item.getClass().getSimpleName().contains("WatchFace")) {
                    changed = true;
                    continue;
                }
                kept.add(item);
            }
            if (!changed) return;
            List<Object> mutable = (List<Object>) rows;
            mutable.clear();
            mutable.addAll(kept);
            Object adapter = XposedHelpers.getObjectField(panel, adapterField);
            if (adapter != null) XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
        } catch (Throwable ignored) { }
    }

    private static void patchSdkDevice(Object info, Bundle shown) {
        try {
            boolean connected = shown.getBoolean("connected");
            ClassLoader loader = info.getClass().getClassLoader();
            Class<?> state = XposedHelpers.findClass("com.oplus.mydevices.sdk.device.ConnectState", loader);
            Object connect = XposedHelpers.getStaticObjectField(state, connected ? "CONNECTED" : "DISCONNECTED");
            try {
                XposedHelpers.callMethod(info, "setConnectState", connect);
            } catch (Throwable ignored) { }
            try {
                Class<?> connectionType = XposedHelpers.findClass(
                        "com.oplus.mydevices.sdk.device.Connection", loader);
                long now = System.currentTimeMillis();
                Object connection = XposedHelpers.newInstance(connectionType, connect,
                        connected ? now : 0L, connected ? 0L : now);
                XposedHelpers.callMethod(info, "setConnection", connection);
            } catch (Throwable ignored) { }
            int battery = shown.getInt("battery", -1);
            if (battery < 0 || battery > 100) return;
            Class<?> batteryInfo = XposedHelpers.findClass(
                    "com.oplus.mydevices.sdk.device.BatteryInfo", loader);
            Class<?> batteryType = XposedHelpers.findClass(
                    "com.oplus.mydevices.sdk.device.BatteryType", loader);
            Object single = XposedHelpers.getStaticObjectField(batteryType, "SINGLE");
            Object cell;
            try {
                cell = XposedHelpers.newInstance(batteryInfo, single, battery, shown.getBoolean("charging"));
            } catch (Throwable ignored) {
                cell = XposedHelpers.newInstance(batteryInfo);
                XposedHelpers.callMethod(cell, "setBatteryType", single);
                XposedHelpers.callMethod(cell, "setValue", battery);
                XposedHelpers.callMethod(cell, "setCharge", shown.getBoolean("charging"));
            }
            ArrayList<Object> batteries = new ArrayList<>();
            batteries.add(cell);
            XposedHelpers.callMethod(info, "setBatteryInfoList", batteries);
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_PANEL_SDK_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    /** Device rows otherwise open a settings screen that queries an OPPO link and stays blank. */
    private static void openNativeHistory(String menuClass, String activityClass, ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(menuClass, loader, "itemClick", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!selectedBand(param.thisObject)) return;
                    Object context = XposedHelpers.callMethod(param.thisObject, "getContext");
                    if (!(context instanceof Context launch)) return;
                    Intent intent = new Intent().setClassName("com.heytap.health", activityClass);
                    try {
                        Object info = XposedHelpers.callMethod(param.thisObject, "getCurrSelectWearableDevice");
                        if (info != null) {
                            Object mac = XposedHelpers.callMethod(info, "getMac");
                            if (mac != null) {
                                String address = String.valueOf(mac);
                                Bundle device = new Bundle();
                                device.putString("settingsDeviceMac", address);
                                intent.putExtra("settingsDeviceMac", address);
                                intent.putExtra("settingsDeviceMacBundle", device);
                            }
                        }
                    } catch (Throwable ignored) { }
                    if (!(launch instanceof android.app.Activity)) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    launch.startActivity(intent);
                    param.setResult(null);
                }
            });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_NATIVE_PAGE_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    private static void hideWatchFace(String className, ClassLoader loader, Class<?> eventType, String dataType) {
        XC_MethodHook hideRow = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!selectedBand(param.thisObject)) return;
                try {
                    Object view = XposedHelpers.callMethod(param.thisObject, "getItemView");
                    if (!(view instanceof android.view.View item)) return;
                    item.setVisibility(android.view.View.GONE);
                    android.view.ViewGroup.LayoutParams params = item.getLayoutParams();
                    if (params == null) return;
                    params.height = 0;
                    params.width = 0;
                    item.setLayoutParams(params);
                } catch (Throwable ignored) { }
            }
        };
        if (eventType != null) {
            try {
                XposedHelpers.findAndHookMethod(className, loader, "interceptEvent", eventType,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                if (selectedBand(param.thisObject)) param.setResult(Boolean.TRUE);
                            }
                        });
            } catch (Throwable failure) {
                Log.i("OplusBandBridge", "OHEALTH_WATCH_FACE_HOOK_UNAVAILABLE "
                        + failure.getClass().getSimpleName());
            }
        } else {
            try {
                XposedHelpers.findAndHookMethod(className, loader, "itemClick", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (selectedBand(param.thisObject)) param.setResult(null);
                    }
                });
            } catch (Throwable failure) {
                Log.i("OplusBandBridge", "OHEALTH_WATCH_FACE_HOOK_UNAVAILABLE "
                        + failure.getClass().getSimpleName());
            }
        }
        try {
            Class<?> data = XposedHelpers.findClass(dataType, loader);
            XposedHelpers.findAndHookMethod(className, loader, "initData", data, hideRow);
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_WATCH_FACE_ROW_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        if ("DeviceWatchFaceItem".equals(className.substring(className.lastIndexOf('.') + 1))) {
            try {
                XposedHelpers.findAndHookMethod(className, loader, "initData", Object.class, hideRow);
            } catch (Throwable ignored) { }
            try {
                XposedHelpers.findAndHookMethod(className, loader, "initView", hideRow);
            } catch (Throwable ignored) { }
        }
    }


    private static void installHostNotifications(ClassLoader loader) {
        try {
            Class<?> bean = XposedHelpers.findClass(
                    "com.heytap.health.watch.notification.HealthNotificationBean", loader);
            String center = "com.heytap.health.watch.notification.HealthNotificationRegisterCenter";
            XposedHelpers.findAndHookMethod(center, loader, "onNotificationPosted", bean, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    forwardHostNotification(param.args[0], false);
                }
            });
            Log.i("OplusBandBridge", "OHEALTH_NOTIFICATION_HOOK process="
                    + android.app.Application.getProcessName());
            trace("OHEALTH_NOTIFICATION_HOOK process=" + android.app.Application.getProcessName());
            XposedHelpers.findAndHookMethod(center, loader, "onNotificationRemoved", bean, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    forwardHostNotification(param.args[0], true);
                }
            });
            // Forward before skipping. Swallowing this call never reaches the register center.
            String manager = "com.heytap.health.watch.notification.impl.transceiver.NotificationEventManager";
            XposedHelpers.findAndHookMethod(manager, loader, "onNotificationPosted", bean, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!registeredBand()) return;
                    forwardHostNotification(param.args[0], false);
                    param.setResult(null);
                }
            });
            XposedHelpers.findAndHookMethod(manager, loader, "onNotificationRemoved", bean, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!registeredBand()) return;
                    forwardHostNotification(param.args[0], true);
                    param.setResult(null);
                }
            });
            XposedHelpers.findAndHookMethod("com.heytap.health.base.app.ToastUtil", loader,
                    "showShort", String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof String text && deferredConnectToast(text)) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_NOTIFICATION_HOOK_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> HOST_NOTIFICATIONS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService ALLOWLIST =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "OplusBandAllowlist");
                thread.setDaemon(true);
                return thread;
            });

    private static boolean registeredBand() {
        Bundle shown = snapshot;
        return shown != null && shown.getBoolean("registered", false);
    }

    private static boolean deferredConnectToast(String text) {
        if (!registeredBand() || hostContext == null || text == null) return false;
        try {
            int id = hostContext.getResources().getIdentifier(
                    "settings_enable_after_connected", "string", HOST);
            return id != 0 && text.equals(hostContext.getString(id));
        } catch (RuntimeException ignored) {
            return text.contains("连接设备后生效") || text.contains("after your device is reconnected");
        }
    }

    /** OHealth already applied its own allowlist. Translate in this process, send via the bridge provider. */
    private static boolean forwardHostNotification(Object bean, boolean removed) {
        String process = android.app.Application.getProcessName();
        Bundle shown = liveSnapshot();
        if (bean == null || hostContext == null || shown == null || !shown.getBoolean("registered", false)) {
            notifyDrop("snapshot bean=" + (bean != null) + " context=" + (hostContext != null)
                    + " registered=" + (shown != null && shown.getBoolean("registered", false)), "", process);
            return false;
        }
        try {
            String pkg = text(bean, "getPackageName");
            String key = text(bean, "getKey");
            if (pkg.isBlank() || key.isBlank()) {
                notifyDrop("identity pkg=" + !pkg.isBlank() + " key=" + !key.isBlank(), pkg, process);
                return false;
            }
            if (deliverPhoneAlarm(bean, pkg, key, removed)) return true;
            boolean call = isIncomingCall(bean);
            if (!removed && !call) {
                String blocked = readAllowBlock(bean);
                if (blocked != null) {
                    notifyDrop(blocked, pkg, process);
                    return false;
                }
                if (screenOnBlocks(bean)) {
                    notifyDrop("screen", pkg, process);
                    return false;
                }
                Object importance = XposedHelpers.callMethod(bean, "getImportance");
                if (importance instanceof Number level && level.intValue() <= 2) {
                    notifyDrop("importance level=" + level.intValue(), pkg, process);
                    return false;
                }
            }
            Integer id = OHealthNotifyFilter.id(HOST_NOTIFICATIONS, key, removed && !call);
            if (id == null) {
                notifyDrop("not-forwarded", pkg, process);
                return false;
            }
            if (removed && call) HOST_NOTIFICATIONS.remove(key);
            Object posted = XposedHelpers.callMethod(bean, "getPostTimeMillis");
            long when = posted instanceof Number time && time.longValue() > 0
                    ? time.longValue() : System.currentTimeMillis();
            Bundle extras = new Bundle();
            extras.putBoolean("removed", removed);
            extras.putBoolean("call", call);
            extras.putInt("callState", call ? callState(bean) : 0);
            extras.putBoolean("connected", call && callConnected(bean));
            extras.putString("pkg", pkg);
            extras.putString("key", key);
            extras.putInt("id", id);
            String app = text(bean, "getAppName");
            if (app.isBlank() || app.equals(pkg)) {
                String resolved = io.github.miam1ku.mibandoplusbridge.notify.AppLabels.label(hostContext, pkg);
                if (!resolved.isBlank()) app = resolved;
            }
            extras.putString("app", app);
            extras.putString("title", text(bean, "getTitle"));
            extras.putString("body", text(bean, "getContent"));
            extras.putLong("when", when);
            Bundle result = hostContext.getContentResolver().call(
                    io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider.URI, "forward", null, extras);
            String status = result == null ? "NULL" : result.getString("status", "");
            Log.i("OplusBandBridge", "OHEALTH_NOTIFICATION_FORWARD removed=" + removed
                    + " call=" + call + " pkg=" + pkg + " status=" + status);
            return true;
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_NOTIFICATION_FORWARD_FAILED "
                    + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static final java.util.Set<String> phoneAlarmKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Only the klaxon foreground notification is a ring. Next-alarm ticks stay off the band. */
    private static boolean deliverPhoneAlarm(Object bean, String pkg, String key, boolean removed) {
        if (!io.github.miam1ku.mibandoplusbridge.notify.PhoneAlarmNotice.CLOCK.equals(pkg)) return false;
        boolean live = io.github.miam1ku.mibandoplusbridge.notify.PhoneAlarmNotice.ringing(clockNotification(bean));
        if (localListenerConnected()) return true;
        if (!removed) {
            trace("ALARM_CLOCK_POST " + clockShape(bean));
            if (!live) return true;
            if (!phoneAlarmKeys.add(key)) return true;
        } else if (!phoneAlarmKeys.remove(key)) {
            return true;
        }
        if (hostContext == null) return true;
        android.os.Bundle extras = new android.os.Bundle();
        extras.putBoolean("removed", removed);
        extras.putBoolean("phoneAlarm", true);
        extras.putInt("alarmOp", removed ? 1 : 0);
        extras.putString("pkg", pkg);
        extras.putString("key", key);
        extras.putString("title", removed ? "" : text(bean, "getTitle"));
        try {
            hostContext.getContentResolver().call(
                    io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider.URI,
                    "forward", null, extras);
            return true;
        } catch (RuntimeException failure) {
            android.util.Log.i("OplusBandBridge", "CLOCK_ALARM_FORWARD_FAILED "
                    + failure.getClass().getSimpleName());
            return true;
        }
    }

    private static android.service.notification.StatusBarNotification clockNotification(Object bean) {
        try {
            Object origin = de.robv.android.xposed.XposedHelpers.callMethod(bean, "getOrigin");
            if (origin instanceof android.service.notification.StatusBarNotification posted) return posted;
        } catch (Throwable ignored) { }
        return null;
    }

    private static String clockShape(Object bean) {
        try {
            Object origin = de.robv.android.xposed.XposedHelpers.callMethod(bean, "getOrigin");
            if (!(origin instanceof android.service.notification.StatusBarNotification posted)) return "origin=absent";
            android.app.Notification notification = posted.getNotification();
            String channel = notification == null || notification.getChannelId() == null ? "" : notification.getChannelId();
            return "id=" + posted.getId() + " flags=" + (notification == null ? 0 : notification.flags)
                    + " channel=" + channel;
        } catch (Throwable failure) {
            return "origin=" + failure.getClass().getSimpleName();
        }
    }

    private static boolean isIncomingCall(Object bean) {
        try {
            Object origin = XposedHelpers.callMethod(bean, "getOrigin");
            if (origin instanceof StatusBarNotification posted) {
                android.app.Notification notification = posted.getNotification();
                if (notification != null
                        && android.app.Notification.CATEGORY_CALL.equals(notification.category)) {
                    return true;
                }
            }
        } catch (Throwable ignored) { }
        try {
            Object holder = XposedHelpers.getStaticObjectField(
                    bean.getClass().getClassLoader().loadClass(
                            "com.heytap.health.watch.notification.impl.module.NotificationHolder"),
                    "INSTANCE");
            return Boolean.TRUE.equals(XposedHelpers.callMethod(holder, "isDial", text(bean, "getPackageName")));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int callState(Object bean) {
        try {
            Object origin = XposedHelpers.callMethod(bean, "getOrigin");
            if (origin instanceof StatusBarNotification posted) {
                return io.github.miam1ku.mibandoplusbridge.notify.CallPresentation.wire(posted.getNotification());
            }
        } catch (Throwable ignored) { }
        return io.github.miam1ku.mibandoplusbridge.notify.CallPresentation.wire(
                io.github.miam1ku.mibandoplusbridge.notify.CallPresentation.kind(false, false, 0,
                        text(bean, "getContent"), text(bean, "getTitle")));
    }


    private static boolean callConnected(Object bean) {
        try {
            Object origin = XposedHelpers.callMethod(bean, "getOrigin");
            if (origin instanceof StatusBarNotification posted) {
                return io.github.miam1ku.mibandoplusbridge.notify.CallPresentation.connected(posted.getNotification());
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Health switch {@code screen_on_push} false means do not push while the screen is on. */
    private static boolean screenOnBlocks(Object bean) {
        try {
            ClassLoader loader = bean.getClass().getClassLoader();
            Object holder = XposedHelpers.getStaticObjectField(loader.loadClass(
                    "com.heytap.health.watch.notification.impl.whitelist.NotificationRoomHolder"), "INSTANCE");
            boolean on = io.github.miam1ku.mibandoplusbridge.notify.NotifySwitch.on(
                    XposedHelpers.callMethod(holder, "getPackageSwitchStatus", "screen_on_push"));
            remember(null, null, null, on);
            if (on) return false;
            Object utils = XposedHelpers.getStaticObjectField(loader.loadClass(
                    "com.heytap.health.watch.notification.impl.utils.NotificationScreenUtils"), "INSTANCE");
            Object offOrLocked = XposedHelpers.callMethod(utils, "isScreenOffOrLocked", hostContext);
            return !Boolean.TRUE.equals(offOrLocked);
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_NOTIFICATION_SCREEN_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
            return false;
        }
    }
    /** Null means the health allowlist permits this package. Room is not queried on the main thread. */
    private static String readAllowBlock(Object bean) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return allowBlock(bean);
        java.util.concurrent.atomic.AtomicReference<String> result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        ALLOWLIST.execute(() -> {
            try {
                result.set(allowBlock(bean));
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
                noteAllowlist("timeout");
                return null;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            noteAllowlist("thread");
            return null;
        }
        return result.get();
    }

    private static String allowBlock(Object bean) {
        try {
            Object holder = XposedHelpers.getStaticObjectField(bean.getClass().getClassLoader()
                    .loadClass("com.heytap.health.watch.notification.impl.whitelist.NotificationRoomHolder"),
                    "INSTANCE");
            Object mainSwitch = XposedHelpers.callMethod(holder, "getPackageSwitchStatus", "main_switch");
            if (!io.github.miam1ku.mibandoplusbridge.notify.NotifySwitch.on(mainSwitch)) {
                remember(false, null, null, null);
                return "main_switch value=" + String.valueOf(mainSwitch);
            }
            String pkg = text(bean, "getPackageName");
            Object appSwitch = XposedHelpers.callMethod(holder, "getPackageSwitchStatus", pkg);
            boolean packageOn = io.github.miam1ku.mibandoplusbridge.notify.NotifySwitch.on(appSwitch);
            remember(true, pkg, packageOn, null);
            if (!packageOn) return "package value=" + String.valueOf(appSwitch);
            return null;
        } catch (Throwable unavailable) {
            String kind = OHealthNotifyFilter.allowlistFailure(unavailable);
            if (OHealthNotifyFilter.blocks(kind)) return kind;
            noteAllowlist(kind);
            return null;
        }
    }

    private static String lastPolicy = "";

    private static void remember(Boolean main, String pkg, Boolean packageOn, Boolean screenOnPush) {
        String token = String.valueOf(main) + "|" + pkg + "|" + packageOn + "|" + screenOnPush;
        if (token.equals(lastPolicy) || hostContext == null) return;
        lastPolicy = token;
        android.os.Bundle extras = new android.os.Bundle();
        if (main != null) extras.putBoolean("main", main);
        if (pkg != null && packageOn != null) {
            extras.putString("pkg", pkg);
            extras.putBoolean("packageOn", packageOn);
        }
        if (screenOnPush != null) extras.putBoolean("screenOnPush", screenOnPush);
        try {
            hostContext.getContentResolver().call(
                    io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider.URI, "policy", null, extras);
        } catch (RuntimeException failure) {
            lastPolicy = "";
        }
    }

    private static boolean localListenerConnected() {
        if (hostContext == null) return false;
        try {
            android.os.Bundle result = hostContext.getContentResolver().call(
                    io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider.URI, "listener", null, null);
            return result != null && result.getBoolean("connected", false);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static String lastAllowlist = "";

    private static void noteAllowlist(String kind) {
        String line = "OHEALTH_ALLOWLIST_UNAVAILABLE " + kind;
        if (line.equals(lastAllowlist)) return;
        lastAllowlist = line;
        Log.i("OplusBandBridge", line);
        trace(line);
    }

    private static String lastNotifyDrop = "";

    private static void notifyDrop(String reason, String pkg, String process) {
        String line = reason + "|" + pkg + "|" + process;
        if (line.equals(lastNotifyDrop)) return;
        lastNotifyDrop = line;
        Log.i("OplusBandBridge", "OHEALTH_NOTIFICATION_DROP reason=" + reason
                + " pkg=" + (pkg == null || pkg.isBlank() ? "none" : pkg)
                + " process=" + process);
        trace("OHEALTH_NOTIFICATION_DROP reason=" + reason
                + " pkg=" + (pkg == null || pkg.isBlank() ? "none" : pkg)
                + " process=" + process);
    }

    private static void trace(String line) {
        traceLine(hostContext, line);
    }

    static void traceLine(Context context, String line) {
        if (context == null || line == null || line.isBlank()) return;
        try {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString("line", line.length() > 240 ? line.substring(0, 240) : line);
            context.getContentResolver().call(
                    io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider.URI,
                    "trace", null, extras);
        } catch (RuntimeException ignored) { }
    }

    private static boolean hostAllows(Object bean) {
        return allowBlock(bean) == null;
    }


    private static String text(Object bean, String method) {
        Object value = XposedHelpers.callMethod(bean, method);
        return value instanceof String text ? text : "";
    }


    private static boolean selectedBand(Object item) {
        Bundle shown = snapshot;
        if (item == null || shown == null || !shown.getBoolean("registered", false)) return false;
        try {
            return matches(XposedHelpers.callMethod(item, "getCurrSelectWearableDevice"), shown);
        } catch (Throwable unavailable) {
            return false;
        }
    }



    private static int wearableType(Class<?> constants, String model) {
        Object companion = XposedHelpers.getStaticObjectField(constants, "Companion");
        Object mapped = XposedHelpers.callMethod(companion, "getDEVICETYPE_TO_MODELS");
        if (!(mapped instanceof Map<?, ?> types)) return -1;
        for (Map.Entry<?, ?> entry : types.entrySet()) {
            if (containsModel(entry.getValue(), model) && entry.getKey() instanceof Number type) return type.intValue();
        }
        return -1;
    }
    private static boolean containsModel(Object value, String model) {
        if (value instanceof String text) return model.equals(text);
        if (value instanceof Collection<?> values) {
            for (Object child : values) if (containsModel(child, model)) return true;
        }
        return false;
    }

}
