// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedBridge;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;
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
        main = HookRuntime.main();
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
                            HookRuntime.log("OHEALTH_DEVICE_ROW_UNAVAILABLE "
                                    + failure.getClass().getSimpleName());
                        } finally {
                            APPENDING.set(false);
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getThrowable() != null) {
                            HookRuntime.log("OHEALTH_DEVICE_REFRESH_FAILED "
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
        installEmptyCloudCleanup(loader);
        installDeviceImage(loader);
        observeSnapshots(loader);
        guardBandBond(loader);
    }

    /**
     * Opening the app used to cost the system bond. Health forgets a watch by removing the bond:
     * the panel's disconnect path reaches {@code PairManager.cancelBond(mac)} and the device
     * manager reaches {@code DMManagerImpl.removeBond(...)}. This band belongs to the bridge, so
     * a removal here only leaves the user a pairing prompt on the wrist and a dead SPP link.
     * Nothing in this module wants that bond gone, so removal for this MAC is refused and logged.
     */
    private static void guardBandBond(ClassLoader loader) {
        // 6.9.42 declares the removal entries as vararg arrays, but the class that owns them at
        // runtime differs between host processes, so match by name and accept any signature.
        HookRuntime.tryHook("OHEALTH_BOND_GUARD_PAIR_UNAVAILABLE", () -> {
            Class<?> pair = XposedHelpers.findClass("com.heytap.accessory.pair.PairManager", loader);
            XposedBridge.hookAllMethods(pair, "cancelBond", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!bandIn(param.args.length == 0 ? null : param.args[0])) return;
                    param.setResult(false);
                    HookRuntime.log("OHEALTH_BOND_GUARD cancelBond");
                }
            });
        });
        HookRuntime.tryHook("OHEALTH_BOND_GUARD_NATIVE_UNAVAILABLE", () -> {
            Class<?> nativePair = XposedHelpers.findClass(
                    "com.heytap.accessory.pair.apiadapter.BluetoothNative", loader);
            XC_MethodHook guard = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!bandIn(param.args.length == 0 ? null : param.args[0])) return;
                    param.setResult(false);
                    HookRuntime.log("OHEALTH_BOND_GUARD removeBond");
                }
            };
            XposedBridge.hookAllMethods(nativePair, "removeBond", guard);
            XposedBridge.hookAllMethods(nativePair, "cancelBondProcess", guard);
        });
        HookRuntime.tryHook("OHEALTH_BOND_GUARD_MANAGER_UNAVAILABLE", () -> {
            // The device-manager entry point takes the MAC as text and returns void.
            Class<?> manager = XposedHelpers.findClass(
                    "com.heytap.health.devicemanager.client.impl.DMManagerImpl", loader);
            XposedBridge.hookAllMethods(manager, "removeBond", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        if (!bandIn(arg)) continue;
                        param.setResult(null);
                        HookRuntime.log("OHEALTH_BOND_GUARD managerRemoveBond");
                        return;
                    }
                }
            });
        });
    }

    /** True when any MAC in a vararg array is this band. */
    private static boolean bandIn(Object values) {
        if (!(values instanceof Object[] items)) return ourMac(values);
        for (Object item : items) {
            if (item == null) continue;
            Object mac = item;
            if (item instanceof android.bluetooth.BluetoothDevice device) {
                try {
                    mac = device.getAddress();
                } catch (SecurityException denied) {
                    continue;
                }
            }
            if (ourMac(mac)) return true;
        }
        return false;
    }


    /** Replaces the OPPO body and detail picture with the Mi Fitness server image. The face layer stays hidden. */
    private static void installDeviceImage(ClassLoader loader) {
        try {
            Class<?> model = XposedHelpers.findClass(
                    "com.heytap.health.linkage.ui.DeviceDetailsViewModel", loader);
            XposedBridge.hookAllMethods(model, "parseImage", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length != 4) return;
                    String uri = imageUri();
                    if (uri.isEmpty()) return;
                    Object mac = XposedHelpers.getObjectField(param.thisObject, "mMac");
                    Object deviceId = XposedHelpers.getObjectField(param.thisObject, "mDeviceId");
                    if (!ourBand(mac, deviceId)) return;
                    Class<?> beanClass = XposedHelpers.findClass(
                            "com.heytap.health.linkage.ui.ImageBean", loader);
                    Object bean = XposedHelpers.newInstance(beanClass, false);
                    XposedHelpers.callMethod(bean, "setIcon2DUrl", uri);
                    Object live = XposedHelpers.getObjectField(param.thisObject, "mImageLiveData");
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        XposedHelpers.callMethod(live, "setValue", bean);
                    } else {
                        XposedHelpers.callMethod(live, "postValue", bean);
                    }
                    param.setResult(null);
                }
            });
            HookRuntime.log("OHEALTH_DEVICE_IMAGE_HOOK parseImage");
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_DEVICE_IMAGE_HOOK_UNAVAILABLE parseImage "
                    + failure.getClass().getSimpleName());
        }
        try {
            Class<?> watch = XposedHelpers.findClass(
                    "com.heytap.health.watchpair.view.WatchView", loader);
            XC_MethodHook paint = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    applyBody(param.thisObject);
                }
            };
            XposedHelpers.findAndHookMethod(watch, "updateId", boolean.class, paint);
            XposedHelpers.findAndHookMethod(watch, "setBlackFaceView", paint);
            XposedHelpers.findAndHookMethod(watch, "runOrPostponeUntilResReady", Runnable.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.args[0] instanceof Runnable host)) return;
                            Object view = param.thisObject;
                            param.args[0] = (Runnable) () -> {
                                host.run();
                                applyBody(view);
                            };
                        }
                    });
            HookRuntime.log("OHEALTH_DEVICE_IMAGE_HOOK watch");
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_DEVICE_IMAGE_HOOK_UNAVAILABLE watch "
                    + failure.getClass().getSimpleName());
        }
    }

    private static String imageUri() {
        Bundle shown = display();
        if (shown == null) return "";
        String uri = shown.getString("imageUri", "");
        return uri == null ? "" : uri;
    }

    private static void applyBody(Object watch) {
        try {
            String uri = imageUri();
            if (uri.isEmpty() || watch == null) return;
            Bundle shown = display();
            if (shown == null) return;
            Object mac = XposedHelpers.getObjectField(watch, "mDeviceMac");
            if (!io.github.miam1ku.mibandoplusbridge.data.MacIds.same(
                    shown.getString("mac", ""), String.valueOf(mac))) return;
            Object shell = XposedHelpers.callMethod(watch, "getShellView");
            if (!(shell instanceof android.widget.ImageView image)) return;
            image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            ClassLoader loader = watch.getClass().getClassLoader();
            Class<?> glide = Class.forName("com.bumptech.glide.Glide", false, loader);
            Object request = glide.getMethod("with", android.view.View.class).invoke(null, image);
            Object builder;
            try {
                builder = request.getClass().getMethod("load", String.class).invoke(request, uri);
            } catch (NoSuchMethodException missing) {
                builder = request.getClass().getMethod("load", Object.class).invoke(request, uri);
            }
            builder.getClass().getMethod("into", android.widget.ImageView.class).invoke(builder, image);
            try {
                XposedHelpers.callMethod(watch, "setFaceViewVisibility", android.view.View.GONE);
            } catch (Throwable ignored) { }
            try {
                Object video = XposedHelpers.getObjectField(watch, "mFlVideoLayout");
                if (video instanceof android.view.View view) view.setVisibility(android.view.View.GONE);
            } catch (Throwable ignored) { }
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_DEVICE_IMAGE_FAILED " + failure.getClass().getSimpleName());
        }
    }

    /**
     * An empty cloud list calls this on the main thread and disconnects every wearable,
     * including this band. That binder call is what freezes the page.
     */
    static boolean skipEmptyCloudCleanup(boolean registered) {
        return registered;
    }

    private static void installEmptyCloudCleanup(ClassLoader loader) {
        try {
            Class<?> fragment = XposedHelpers.findClass(
                    "com.heytap.health.device.tab.NewDeviceFragment", loader);
            XposedBridge.hookAllMethods(fragment, "cleanAndDisconnect", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!skipEmptyCloudCleanup(registeredBand())) return;
                    param.setResult(null);
                    HookRuntime.log("OHEALTH_CLEAN_DISCONNECT_SKIPPED");
                }
            });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_CLEAN_DISCONNECT_HOOK_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    /**
     * UI hooks read the process-local snapshot reference. The {@code Bundle} can still be mutated,
     * and {@link #liveSnapshot()} replaces it. {@link #registeredSnapshot()} returns a stable copy.
     */
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
            HookRuntime.log("OHEALTH_DEVICE_REMEMBERED");
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_DEVICE_REMEMBER_FAILED "
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
                    io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers.callMethod(manager, "getThirdpartySelectMac")))) return true;
            return allRole != null && io.github.miam1ku.mibandoplusbridge.data.MacIds.same(mac, String.valueOf(
                    io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers.callMethod(manager, "getCurrActiveMacByRole", allRole)));
        } catch (Throwable unavailable) { return false; }
    }

    private static void observeSnapshots(ClassLoader loader) {
        HandlerThread thread = HookRuntime.thread("OplusBandDeviceSnapshot");
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
                worker.post(() -> dropDuplicateSpaceCard(loader));
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
                if (snapshotAttempts == 0) HookRuntime.log("OHEALTH_DEVICE_SNAPSHOT_RETRY");
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
                HookRuntime.observe(hostContext.getContentResolver(), DeviceCardProvider.URI, true, observer);
            } catch (Throwable failure) {
                HookRuntime.log("OHEALTH_OBSERVER_UNAVAILABLE " + failure.getClass().getSimpleName());
            }
            refresh[0].run();
        });
    }

    private static Bundle fromProvider() {
        try {
            Bundle result = hostContext.getContentResolver().call(DeviceCardProvider.URI, "bandDisplay", null, null);
            // A missing or malformed IPC snapshot stays unknown. Removal waits for an explicit clear.
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
            HookRuntime.log("OHEALTH_PROVIDER_UNAVAILABLE " + failure.getClass().getSimpleName()
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
                // refreshView leaves an empty wearable list empty. Feed the same host path that fills it.
                java.util.ArrayList<Object> seed = new java.util.ArrayList<>();
                seed.add(info);
                XposedHelpers.callMethod(controller, "refreshWearableDeviceList", seed, new java.util.ArrayList<>(), "band snapshot");
            }
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_DEVICE_ROW_UNAVAILABLE " + failure.getClass().getSimpleName());
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
                HookRuntime.log("OHEALTH_SYNC_UNAVAILABLE " + failure.getClass().getSimpleName());
            }
            if ("OPEN_CONFIG_REQUIRED".equals(status)) {
                HookRuntime.log("OHEALTH_SYNC_DEFERRED");
            }
        });
    }



    private static Object deviceInfo(ClassLoader loader, Bundle display) throws Exception {
        Class<?> constants = XposedHelpers.findClass(CONSTANTS, loader);
        // Native pages only load for an OPPO band model. The Xiaomi MAC and device id stay real.
        String model = (String) XposedHelpers.getStaticObjectField(constants, "BAND_DEVICE_MODEL");
        int type = wearableType(constants, model);
        if (type < 0 || model == null || model.isBlank()) {
            HookRuntime.log("OHEALTH_DEVICE_TYPE_MISSING");
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
        // This host method removes the local row. Remote unbind stays on the official path.
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
            HookRuntime.log("OHEALTH_WEARABLE_SCAN_EMPTY");
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
            HookRuntime.log("OHEALTH_NOTIFY_EXTRA_UNAVAILABLE "
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
            HookRuntime.log("OHEALTH_NOTIFY_DEVICE_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        HookRuntime.tryHook("OHEALTH_BOUND_INFO_BY_MAC_UNAVAILABLE", () -> XposedHelpers.findAndHookMethod(
                "com.heytap.health.devicemanager.devicetype.DeviceTypeUtil", loader,
                "getBoundDeviceInfoByMac", String.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object info = bandDevice(loader, param.args.length == 0 ? null : param.args[0]);
                        if (info != null) param.setResult(info);
                    }
                }));
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
                        HookRuntime.log("OHEALTH_ACTIVE_MAC empty");
                        return;
                    }
                    param.setResult(mac);
                    HookRuntime.log("OHEALTH_ACTIVE_MAC filled");
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
            HookRuntime.log("OHEALTH_BOUND_INFO_UNAVAILABLE "
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
                                HookRuntime.log("OHEALTH_NOTIFY_ABILITY_UNAVAILABLE "
                                        + failure.getClass().getSimpleName());
                            }
                        }
                    });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NOTIFY_ABILITY_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        HookRuntime.tryHook("OHEALTH_FLUID_HOOK_UNAVAILABLE", () -> XposedHelpers.findAndHookMethod(
                "com.heytap.health.deviceability.NotificationBaseAbilityDevice", loader,
                "isSupportFluid", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (isBand(param.thisObject)) param.setResult(false);
                    }
                }));
        XC_MethodHook bandFalse = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (isBand(param.thisObject)) param.setResult(false);
            }
        };
        for (String method : new String[]{"isSecondaryDevice", "isFamilyDevice"}) {
            HookRuntime.tryHook("OHEALTH_DEVICE_ROLE_HOOK_UNAVAILABLE " + method, () ->
                    XposedHelpers.findAndHookMethod(
                            "com.heytap.health.devicemanager.deviceability.DeviceInfo", loader,
                            method, bandFalse));
        }
        try {
            Class<?> fragment = XposedHelpers.findClass(
                    "com.heytap.health.watch.notification.impl.ui.NotificationSyncFragment", loader);
            XposedBridge.hookAllMethods(fragment, "showScreen", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object vm = XposedHelpers.callMethod(param.thisObject, "getVm");
                        if (!(XposedHelpers.callMethod(vm, "successData") instanceof java.util.List<?>)) {
                            HookRuntime.log("OHEALTH_NOTIFY_SCREEN empty");
                            param.setResult(null);
                        }
                    } catch (Throwable failure) {
                        HookRuntime.log("OHEALTH_NOTIFY_SCREEN "
                                + failure.getClass().getSimpleName());
                    }
                }
            });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NOTIFY_SCREEN_UNAVAILABLE "
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
            HookRuntime.log("OHEALTH_PANEL_FUNCTION_UNAVAILABLE "
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
            HookRuntime.log("OHEALTH_PANEL_INIT_UNAVAILABLE "
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
            HookRuntime.log("OHEALTH_PANEL_DATA_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        HookRuntime.tryHook("OHEALTH_RECONNECT_HOOK_UNAVAILABLE", () -> XposedHelpers.findAndHookMethod(
                "com.heytap.health.linkage.ui.DeviceDetailsViewModel", loader,
                "reconnectDevice", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!ourBand(XposedHelpers.getObjectField(param.thisObject, "mMac"),
                                XposedHelpers.getObjectField(param.thisObject, "mDeviceId"))) return;
                        param.setResult(null);
                        requestSync();
                    }
                }));
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
            HookRuntime.log("OHEALTH_PANEL_APP_UNAVAILABLE "
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
            HookRuntime.log("OHEALTH_PANEL_WATCHFACE_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        HookRuntime.tryHook("OHEALTH_DEVICE_QUERY_HOOK_UNAVAILABLE", () -> XposedHelpers.findAndHookMethod(
                "com.oplus.mydevices.sdk.DeviceInfoManager", loader,
                "queryDeviceById", String.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length == 0) return;
                        Bundle shown = liveSnapshot();
                        Object info = param.getResult();
                        if (shown == null || info == null || !duplicateSpaceCard(info)) return;
                        patchSdkDevice(info, shown);
                    }
                }));
        guardDuplicateSpaceCard(loader);
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
            HookRuntime.log("OHEALTH_PANEL_OPEN_APP");
            return true;
        } catch (RuntimeException failure) {
            HookRuntime.log("OHEALTH_PANEL_OPEN_APP_UNAVAILABLE "
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
            HookRuntime.log("OHEALTH_PANEL_STATE connected=" + shown.getBoolean("connected")
                    + " battery=" + battery);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_PANEL_STATE_UNAVAILABLE "
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
            HookRuntime.log("OHEALTH_PANEL_FILTER_UNAVAILABLE "
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
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_WATCH_FACE_STRIP_UNAVAILABLE", failure);
        }
    }

    /**
     * Health already puts this band in device space. Keep that card (it uses OPPO's own glyph)
     * and copy the live connection onto it. The bridge card stays hidden while this row exists.
     */
    private static void guardDuplicateSpaceCard(ClassLoader loader) {
        Class<?> infoType;
        try {
            infoType = XposedHelpers.findClass("com.oplus.mydevices.sdk.device.DeviceInfo", loader);
        } catch (Throwable missing) {
            HookRuntime.log("DEVICE_SPACE_GUARD_UNAVAILABLE");
            return;
        }
        XC_MethodHook patch = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || !duplicateSpaceCard(param.args[0])) return;
                Bundle shown = liveSnapshot();
                if (shown != null) patchSdkDevice(param.args[0], shown);
            }
        };
        int hooked = 0;
        for (String typeName : new String[]{
                "com.oplus.mydevices.sdk.internal.DeviceRepositoryImpl",
                "com.oplus.mydevices.sdk.DeviceInfoManager"}) {
            Class<?> type;
            try {
                type = XposedHelpers.findClass(typeName, loader);
            } catch (Throwable missing) {
                continue;
            }
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                String name = method.getName();
                if (!name.equals("add") && !name.equals("update") && !name.equals("insertOrUpdate")
                        && !name.equals("insertOrUpdateOnly") && !name.equals("insertOrUpdateDevice")) continue;
                if (method.getParameterCount() < 1 || !infoType.isAssignableFrom(method.getParameterTypes()[0])) continue;
                XposedBridge.hookMethod(method, patch);
                hooked++;
            }
        }
        int[] guarded = { hooked };
        HookRuntime.tryHook("OHEALTH_WATCH_STATE_HOOK_UNAVAILABLE", () -> {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.linkage.watch.WatchController", loader,
                    "updateDeviceState", infoType, boolean.class, patch);
            guarded[0]++;
        });
        hooked = guarded[0];
        HookRuntime.log(hooked == 0 ? "DEVICE_SPACE_GUARD_UNAVAILABLE" : "DEVICE_SPACE_GUARD " + hooked);
    }

    private static volatile Boolean yielded;
    private static int pushedBattery = Integer.MIN_VALUE;
    private static boolean pushedConnected;
    private static String pushedImage = "";

    /** A missing Health row must stay missing this long before the bridge card comes back. */
    private static final long YIELD_SETTLE_MS = 30_000L;
    private static long absentSinceMs;

    /**
     * Hide the bridge card only while health's own device-space row is still there.
     * Only the main process decides. Health's other processes and a provider that is still
     * starting see no row for a moment, and each of those used to bring the second card back.
     */
    private static void dropDuplicateSpaceCard(ClassLoader loader) {
        Bundle shown = snapshot;
        if (shown == null || hostContext == null) return;
        if (!"com.heytap.health".equals(android.app.Application.getProcessName())) return;
        boolean registered = shown.getBoolean("registered", false);
        String mac = shown.getString("mac", "");
        Boolean found = registered && !mac.isBlank() ? healthCardPresent(mac) : Boolean.FALSE;
        if (found == null) {
            if (registered && !mac.isBlank()) refreshHealthCard(loader, mac, shown);
            return;
        }
        boolean present = found;
        if (present) {
            absentSinceMs = 0;
            refreshHealthCard(loader, mac, shown);
        } else {
            pushedBattery = Integer.MIN_VALUE;
            if (registered) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (absentSinceMs == 0) absentSinceMs = now;
                long waited = now - absentSinceMs;
                if (waited < YIELD_SETTLE_MS) {
                    Handler again = worker;
                    if (again != null) again.postDelayed(() -> dropDuplicateSpaceCard(loader),
                            YIELD_SETTLE_MS - waited + 500);
                    return;
                }
            }
        }
        if (yielded != null && yielded == present) return;
        // While Health's row is listed, the bridge card steps aside so the band shows once.
        // When that row goes away, the bridge card comes back and keeps the band in device space.
        yielded = present;
        try {
            Bundle extras = new Bundle();
            extras.putBoolean("yield", present);
            hostContext.getContentResolver().call(DeviceCardProvider.URI, "spaceYield", null, extras);
            HookRuntime.log(present ? "DEVICE_SPACE_YIELD" : "DEVICE_SPACE_RESTORE");
        } catch (Throwable failure) {
            HookRuntime.log("DEVICE_SPACE_YIELD_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    /** True or false when Health's provider answered; null when it gave no cursor or failed. */
    private static Boolean healthCardPresent(String mac) {
        String id = healthCardId(mac);
        if (id.isEmpty()) return false;
        android.net.Uri uri = android.net.Uri.parse(
                "content://com.heytap.health.WatchProvider/device_info_table_new");
        // DeviceAppProvider decrypts every row from device_id and device_data. With a projection the
        // decrypt has no device_data and the row comes back with blank columns, so read the full row.
        try (android.database.Cursor cursor = hostContext.getContentResolver().query(
                uri, null, "device_id=?", new String[] {id}, null)) {
            // DeviceAppProvider returns null when its database is not open or the query times out.
            if (cursor == null) return null;
            int column = cursor.getColumnIndex("device_id");
            if (column < 0) return null;
            while (cursor.moveToNext()) {
                if (id.equals(cursor.getString(column))) return true;
            }
            return false;
        } catch (Throwable failure) {
            HookRuntime.log("DEVICE_SPACE_LOOKUP_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
            return null;
        }
    }

    private static void refreshHealthCard(ClassLoader loader, String mac, Bundle shown) {
        boolean connected = shown.getBoolean("connected");
        int battery = shown.getInt("battery", -1);
        String image = shown.getString("imageUri", "");
        if (image == null) image = "";
        if (pushedBattery == battery && pushedConnected == connected && image.equals(pushedImage)) return;
        try {
            Class<?> managerType = XposedHelpers.findClass(
                    "com.oplus.mydevices.sdk.DeviceInfoManager", loader);
            Object manager = XposedHelpers.callStaticMethod(managerType, "getInstance");
            Object info = XposedHelpers.callMethod(manager, "queryDeviceById", healthCardId(mac));
            if (info == null) return;
            patchSdkDevice(info, shown);
            XposedHelpers.callMethod(manager, "update", info);
            pushedBattery = battery;
            pushedConnected = connected;
            pushedImage = image;
        } catch (Throwable failure) {
            HookRuntime.log("DEVICE_SPACE_REFRESH_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    /** The device-space card id is the MD5 of the MAC text. The string is hashed as given, so the caller supplies uppercase colon form. */
    private static String healthCardId(String mac) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(mac.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException missing) {
            return "";
        }
    }

    /** Health's card for this band. The bridge card lives in another process, so this lookup only sees Health's row. */
    private static boolean duplicateSpaceCard(Object info) {
        Bundle shown = liveSnapshot();
        if (shown == null || info == null || !shown.getBoolean("registered", false)) return false;
        try {
            return io.github.miam1ku.mibandoplusbridge.data.MacIds.same(
                    shown.getString("mac", ""),
                    String.valueOf(XposedHelpers.callMethod(info, "getMacAddress")));
        } catch (Throwable ignored) {
            return false;
        }
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
            String image = shown.getString("imageUri", "");
            if (image != null && !image.isEmpty()) {
                try {
                    XposedHelpers.callMethod(info, "setIconUrl", image);
                } catch (Throwable ignored) { }
                try {
                    XposedHelpers.callMethod(info, "setMIconUrl", image);
                } catch (Throwable ignored) { }
                HookRuntime.log("DEVICE_SPACE_IMAGE");
            }
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
            HookRuntime.log("OHEALTH_PANEL_SDK_UNAVAILABLE "
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
            HookRuntime.log("OHEALTH_NATIVE_PAGE_UNAVAILABLE "
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
                HookRuntime.log("OHEALTH_WATCH_FACE_HOOK_UNAVAILABLE "
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
                HookRuntime.log("OHEALTH_WATCH_FACE_HOOK_UNAVAILABLE "
                        + failure.getClass().getSimpleName());
            }
        }
        try {
            Class<?> data = XposedHelpers.findClass(dataType, loader);
            XposedHelpers.findAndHookMethod(className, loader, "initData", data, hideRow);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_WATCH_FACE_ROW_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        if ("DeviceWatchFaceItem".equals(className.substring(className.lastIndexOf('.') + 1))) {
            HookRuntime.tryHook("OHEALTH_WATCH_FACE_ROW_ALT_UNAVAILABLE initData", () ->
                    XposedHelpers.findAndHookMethod(className, loader, "initData", Object.class, hideRow));
            HookRuntime.tryHook("OHEALTH_WATCH_FACE_ROW_ALT_UNAVAILABLE initView", () ->
                    XposedHelpers.findAndHookMethod(className, loader, "initView", hideRow));
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
            HookRuntime.log("OHEALTH_NOTIFICATION_HOOK process="
                    + android.app.Application.getProcessName());
            trace("OHEALTH_NOTIFICATION_HOOK process=" + android.app.Application.getProcessName());
            XposedHelpers.findAndHookMethod(center, loader, "onNotificationRemoved", bean, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    forwardHostNotification(param.args[0], true);
                }
            });
            // Forward before skipping. Once this call is swallowed, the post stops at the event manager.
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
            try {
                XposedHelpers.findAndHookMethod(
                        "com.heytap.health.watch.notification.impl.transceiver.BaseInterceptWorker",
                        loader, "sendInterceptMessage", new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                if (registeredBand()) param.setResult(null);
                            }
                        });
            } catch (Throwable failure) {
                HookRuntime.log("OHEALTH_INTERCEPT_MESSAGE_UNAVAILABLE "
                        + failure.getClass().getSimpleName());
            }
            installPolicyPush(loader);
            XposedHelpers.findAndHookMethod("com.heytap.health.base.app.ToastUtil", loader,
                    "showShort", String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof String text && deferredConnectToast(text)) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NOTIFICATION_HOOK_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> HOST_NOTIFICATIONS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService ALLOWLIST =
            HookRuntime.worker("OplusBandAllowlist", 0);
    /**
     * Policy pushes used to run on ALLOWLIST and wait with {@code Thread.sleep} for 0.5 to 5 seconds.
     * Notification forwarding and the allowlist query share that thread, so one debounce held notifications
     * for the same time. The delay now lives on this scheduler, and ALLOWLIST only forwards and queries.
     */
    private static final java.util.concurrent.ScheduledExecutorService POLICY = policyScheduler();
    private static final Map<String, Boolean> FORWARDED_EVENTS = new java.util.LinkedHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger policyGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger policyAttempts =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final Object POLICY_LOCK = new Object();
    private static java.util.concurrent.ScheduledFuture<?> policyTask;
    /** Retries after one policy push fails. A switch change in Health clears the count through schedulePolicy. */
    static final int MAX_POLICY_ATTEMPTS = 6;

    static boolean registeredBand() {
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

    /**
     * Translates a Health notification into a local command and sends it through the bridge provider.
     * After the register center's {@code onNotificationPosted}, Health has already admitted it.
     * The event manager's {@code onNotificationPosted} is also hooked before Health's own filter, and that call is then swallowed.
     */
    private static void forwardHostNotification(Object bean, boolean removed) {
        String process = android.app.Application.getProcessName();
        Bundle shown = liveSnapshot();
        if (bean == null || hostContext == null || shown == null || !shown.getBoolean("registered", false)) {
            notifyDrop("snapshot bean=" + (bean != null) + " context=" + (hostContext != null)
                    + " registered=" + (shown != null && shown.getBoolean("registered", false)), "", process);
            return;
        }
        try {
            String pkg = text(bean, "getPackageName");
            String key = text(bean, "getKey");
            if (pkg.isBlank() || key.isBlank()) {
                notifyDrop("identity pkg=" + !pkg.isBlank() + " key=" + !key.isBlank(), pkg, process);
                return;
            }
            if (deliverPhoneAlarm(bean, pkg, key, removed)) return;
            Object posted = XposedHelpers.callMethod(bean, "getPostTimeMillis");
            long when = posted instanceof Number time && time.longValue() > 0
                    ? time.longValue() : System.currentTimeMillis();
            String title = text(bean, "getTitle");
            String body = text(bean, "getContent");
            String token = (removed ? "r|" : "p|") + key + "|" + when + "|" + title + "|" + body;
            if (!OHealthNotifyFilter.claim(FORWARDED_EVENTS, token, 64)) {
                notifyDrop("duplicate", pkg, process);
                return;
            }
            ALLOWLIST.execute(() -> deliverForward(bean, removed, process, pkg, key, when, title, body));
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NOTIFICATION_FORWARD_FAILED "
                    + failure.getClass().getSimpleName());
            trace("OHEALTH_NOTIFICATION_FORWARD_FAILED " + failure.getClass().getSimpleName());
        }
    }

    private static void deliverForward(Object bean, boolean removed, String process, String pkg, String key,
            long when, String title, String body) {
        try {
            boolean call = isIncomingCall(bean);
            if (!removed && !call) {
                Object importance = XposedHelpers.callMethod(bean, "getImportance");
                if (importance instanceof Number level && level.intValue() <= 2) {
                    notifyDrop("importance level=" + level.intValue(), pkg, process);
                    return;
                }
                Boolean blocked = healthIntercept(bean);
                if (blocked == null) {
                    String fallback = readAllowBlock(bean);
                    if (fallback != null) {
                        notifyDrop(fallback, pkg, process);
                        return;
                    }
                    if (screenOnBlocks(bean)) {
                        notifyDrop("screen", pkg, process);
                        return;
                    }
                } else if (blocked) {
                    notifyDrop("intercept", pkg, process);
                    return;
                }
            }
            Integer id = OHealthNotifyFilter.id(HOST_NOTIFICATIONS, key, removed && !call);
            if (id == null) {
                notifyDrop("not-forwarded", pkg, process);
                return;
            }
            if (removed && call) HOST_NOTIFICATIONS.remove(key);
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
            extras.putString("title", title);
            extras.putString("body", body);
            extras.putLong("when", when);
            Bundle result = hostContext.getContentResolver().call(
                    io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider.URI, "forward", null, extras);
            String status = result == null ? "NULL" : result.getString("status", "");
            HookRuntime.log("OHEALTH_NOTIFICATION_FORWARD removed=" + removed
                    + " call=" + call + " pkg=" + pkg + " status=" + status);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NOTIFICATION_FORWARD_FAILED "
                    + failure.getClass().getSimpleName());
            trace("OHEALTH_NOTIFICATION_FORWARD_FAILED " + failure.getClass().getSimpleName());
        }
    }

    /** Null when Health's own intercept cannot be called. True means Health would drop it. */
    private static Boolean healthIntercept(Object bean) {
        try {
            Class<?> manager = bean.getClass().getClassLoader().loadClass(
                    "com.heytap.health.watch.notification.impl.transceiver.NotificationEventManager");
            Object intercept = XposedHelpers.getStaticObjectField(manager, "mIntercept");
            Object blocked = XposedHelpers.callMethod(intercept, "intercept", bean, true);
            return Boolean.TRUE.equals(blocked);
        } catch (Throwable failure) {
            trace("OHEALTH_INTERCEPT_UNAVAILABLE " + failure.getClass().getSimpleName());
            return null;
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
            HookRuntime.log("CLOCK_ALARM_FORWARD_FAILED "
                    + failure.getClass().getSimpleName());
            return true;
        }
    }

    private static android.service.notification.StatusBarNotification clockNotification(Object bean) {
        try {
            Object origin = io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers.callMethod(bean, "getOrigin");
            if (origin instanceof android.service.notification.StatusBarNotification posted) return posted;
        } catch (Throwable ignored) { }
        return null;
    }

    private static String clockShape(Object bean) {
        try {
            Object origin = io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers.callMethod(bean, "getOrigin");
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
        if (io.github.miam1ku.mibandoplusbridge.notify.CallPresentation.alreadyBlocked(
                text(bean, "getTitle"), text(bean, "getContent"))) return false;
        try {
            Object origin = XposedHelpers.callMethod(bean, "getOrigin");
            if (origin instanceof StatusBarNotification posted) {
                android.app.Notification notification = posted.getNotification();
                if (notification != null) {
                    if (io.github.miam1ku.mibandoplusbridge.notify.CallPresentation.alreadyBlocked(notification)) {
                        return false;
                    }
                    // A dialer notice without CATEGORY_CALL is the shade card, not a ringing call.
                    return android.app.Notification.CATEGORY_CALL.equals(notification.category);
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

    /** Health switch {@code screen_on_push} false holds the push while the screen is on. */
    private static boolean screenOnBlocks(Object bean) {
        try {
            ClassLoader loader = bean.getClass().getClassLoader();
            Object holder = XposedHelpers.getStaticObjectField(loader.loadClass(
                    "com.heytap.health.watch.notification.impl.whitelist.NotificationRoomHolder"), "INSTANCE");
            boolean on = io.github.miam1ku.mibandoplusbridge.notify.NotifySwitch.on(
                    XposedHelpers.callMethod(holder, "getPackageSwitchStatus", "screen_on_push"));
            if (on) return false;
            Object utils = XposedHelpers.getStaticObjectField(loader.loadClass(
                    "com.heytap.health.watch.notification.impl.utils.NotificationScreenUtils"), "INSTANCE");
            Object offOrLocked = XposedHelpers.callMethod(utils, "isScreenOffOrLocked", hostContext);
            return !Boolean.TRUE.equals(offOrLocked);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NOTIFICATION_SCREEN_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
            return false;
        }
    }
    /**
     * Null means the Health allowlist admits this package. A query timeout, an interrupt, and most
     * exceptions also return null, so a failed lookup admits the package too.
     * The Room query runs on {@code ALLOWLIST}, and the main thread waits here for the result.
     */
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
                return "main_switch value=" + String.valueOf(mainSwitch);
            }
            String pkg = text(bean, "getPackageName");
            Object appSwitch = XposedHelpers.callMethod(holder, "getPackageSwitchStatus", pkg);
            boolean packageOn = io.github.miam1ku.mibandoplusbridge.notify.NotifySwitch.on(appSwitch);
            if (!packageOn) return "package value=" + String.valueOf(appSwitch);
            return null;
        } catch (Throwable unavailable) {
            String kind = OHealthNotifyFilter.allowlistFailure(unavailable);
            if (OHealthNotifyFilter.blocks(kind)) return kind;
            noteAllowlist(kind);
            return null;
        }
    }

    private static void installPolicyPush(ClassLoader loader) {
        String process = android.app.Application.getProcessName();
        if (!"com.heytap.health:transport".equals(process)) return;
        try {
            Class<?> holder = XposedHelpers.findClass(
                    "com.heytap.health.watch.notification.impl.whitelist.NotificationRoomHolder", loader);
            XC_MethodHook later = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { schedulePolicy(500); }
            };
            for (String name : new String[] {"update", "updateWithSync", "batchUpdate", "batchUpdateWithSync",
                    "insert", "delete", "updateDbData"}) {
                XposedBridge.hookAllMethods(holder, name, later);
            }
            schedulePolicy(3_000);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_POLICY_PUSH_UNAVAILABLE " + failure.getClass().getSimpleName());
            trace("OHEALTH_POLICY_PUSH_UNAVAILABLE " + failure.getClass().getSimpleName());
        }
    }

    private static void schedulePolicy(long delayMs) {
        policyAttempts.set(0);
        enqueuePolicy(policyGeneration.incrementAndGet(), delayMs);
    }

    /** First retry waits 5 seconds, then doubles, and stops growing at 60 seconds. */
    static long policyRetryDelayMs(int attempt) {
        int shift = Math.max(0, Math.min(attempt - 1, 4));
        return Math.min(60_000L, 5_000L << shift);
    }

    private static void retryPolicy(String reason) {
        int attempt = policyAttempts.incrementAndGet();
        if (attempt > MAX_POLICY_ATTEMPTS) {
            String line = "OHEALTH_POLICY_PUSH_STOPPED attempts=" + MAX_POLICY_ATTEMPTS + " reason=" + reason;
            HookRuntime.log(line);
            trace(line);
            return;
        }
        enqueuePolicy(policyGeneration.incrementAndGet(), policyRetryDelayMs(attempt));
    }

    private static void enqueuePolicy(int generation, long delayMs) {
        try {
            synchronized (POLICY_LOCK) {
                if (policyTask != null) policyTask.cancel(false);
                policyTask = POLICY.schedule(() -> {
                    if (generation != policyGeneration.get()) return;
                    pushPolicy();
                }, Math.max(0, delayMs), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // Hot reload shuts this generation's scheduler down. The new generation installs the push again. 
        }
    }

    private static java.util.concurrent.ScheduledExecutorService policyScheduler() {
        java.util.concurrent.ScheduledThreadPoolExecutor executor =
                new java.util.concurrent.ScheduledThreadPoolExecutor(1, runnable -> {
                    Thread thread = new Thread(runnable, "OplusBandPolicy");
                    thread.setDaemon(true);
                    return thread;
                });
        executor.setRemoveOnCancelPolicy(true);
        HookRuntime.track(executor::shutdownNow);
        return executor;
    }

    /** True once {@code GlobalApplicationHolder} holds a context whose application context is set. */
    private static boolean healthContextReady(ClassLoader loader) {
        try {
            Object context = XposedHelpers.callStaticMethod(loader.loadClass(
                    "com.heytap.health.base.base.GlobalApplicationHolder"), "getAppContext");
            return context instanceof Context app && app.getApplicationContext() != null;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static void pushPolicy() {
        int generation = policyGeneration.get();
        if (hostContext == null) {
            if (generation == policyGeneration.get()) retryPolicy("context");
            return;
        }
        // NotificationRoom builds its database in a static initializer from Health's application
        // context. Touching it before Health sets that context fails the initializer for good,
        // and Health's own notification module then crashes :transport with NoClassDefFoundError.
        if (!healthContextReady(hostContext.getClassLoader())) {
            if (generation == policyGeneration.get()) retryPolicy("app-context");
            return;
        }
        try {
            ClassLoader loader = hostContext.getClassLoader();
            Object holder = XposedHelpers.getStaticObjectField(loader.loadClass(
                    "com.heytap.health.watch.notification.impl.whitelist.NotificationRoomHolder"), "INSTANCE");
            boolean main = io.github.miam1ku.mibandoplusbridge.notify.NotifySwitch.on(
                    XposedHelpers.callMethod(holder, "getPackageSwitchStatus", "main_switch"));
            boolean screen = io.github.miam1ku.mibandoplusbridge.notify.NotifySwitch.on(
                    XposedHelpers.callMethod(holder, "getPackageSwitchStatus", "screen_on_push"));
            ArrayList<String> open = new ArrayList<>();
            ArrayList<String> closed = new ArrayList<>();
            Object beans = XposedHelpers.callMethod(holder, "queryAll");
            if (beans instanceof List<?> list) {
                for (Object bean : list) {
                    Object name = XposedHelpers.callMethod(bean, "getPackageName");
                    if (!(name instanceof String pkg) || pkg.isBlank()) continue;
                    Object enabled = XposedHelpers.callMethod(bean, "isOpen");
                    (Boolean.TRUE.equals(enabled) ? open : closed).add(pkg);
                }
            }
            Object notificationHolder = XposedHelpers.getStaticObjectField(loader.loadClass(
                    "com.heytap.health.watch.notification.impl.module.NotificationHolder"), "INSTANCE");
            String[] defaults = stringArray(whitelist(notificationHolder));
            String[] mms = stringArray(XposedHelpers.callMethod(notificationHolder, "mms"));
            Bundle extras = new Bundle();
            extras.putBoolean("main", main);
            extras.putBoolean("screenOnPush", screen);
            extras.putStringArray("open", open.toArray(String[]::new));
            extras.putStringArray("closed", closed.toArray(String[]::new));
            if (defaults != null) extras.putStringArray("defaults", defaults);
            if (mms != null) extras.putStringArray("mms", mms);
            hostContext.getContentResolver().call(
                    io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider.URI,
                    "policySnapshot", null, extras);
            String pushed = "OHEALTH_POLICY_PUSH main=" + main + " screen=" + screen
                    + " open=" + open.size() + " closed=" + closed.size();
            HookRuntime.log(pushed);
            trace(pushed);
        } catch (Throwable failure) {
            String detail = String.valueOf(failure);
            if (detail.length() > 180) detail = detail.substring(0, 180);
            HookRuntime.log("OHEALTH_POLICY_PUSH_FAILED " + detail);
            trace("OHEALTH_POLICY_PUSH_FAILED " + failure.getClass().getSimpleName());
            if (generation == policyGeneration.get()) retryPolicy(failure.getClass().getSimpleName());
        }
    }

    private static Object whitelist(Object notificationHolder) {
        try {
            Object config = XposedHelpers.callMethod(notificationHolder, "getMConfig");
            return config == null ? null : XposedHelpers.callMethod(config, "getWhiteList");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String[] stringArray(Object value) {
        if (!(value instanceof List<?> list)) return null;
        ArrayList<String> items = new ArrayList<>();
        for (Object item : list) if (item instanceof String text && !text.isBlank()) items.add(text);
        return items.toArray(String[]::new);
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
        HookRuntime.log(line);
        trace(line);
    }

    private static final java.util.HashMap<String, long[]> notifyDropSeen = new java.util.HashMap<>();

    /** One line per reason/package/process per minute. The counts keep a repeated drop visible
     *  without filling the session trace, which otherwise loses the connection events. */
    private static void notifyDrop(String reason, String pkg, String process) {
        String line = reason + "|" + pkg + "|" + process;
        long now = android.os.SystemClock.elapsedRealtime();
        int suppressed = 0;
        synchronized (notifyDropSeen) {
            long[] slot = notifyDropSeen.get(line);
            if (slot == null) {
                if (notifyDropSeen.size() > 64) notifyDropSeen.clear();
                notifyDropSeen.put(line, new long[]{now, 0});
            } else if (now - slot[0] < 60_000) {
                slot[1]++;
                return;
            } else {
                suppressed = (int) slot[1];
                slot[0] = now;
                slot[1] = 0;
            }
        }
        String text = "OHEALTH_NOTIFICATION_DROP reason=" + reason
                + " pkg=" + (pkg == null || pkg.isBlank() ? "none" : pkg)
                + " process=" + process
                + (suppressed > 0 ? " suppressed=" + suppressed : "");
        HookRuntime.log(text);
        trace(text);
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
