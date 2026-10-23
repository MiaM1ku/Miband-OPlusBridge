// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.HandlerThread;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedBridge;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;
import io.github.miam1ku.mibandoplusbridge.integration.NativePanel;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;

/**
 * Registers the bridge in 「设备空间」(device space).
 *
 * <p>ColorOS 16 MyDevices 17.4 shrinks the companion table and observer to R8 short names
 * ({@code n}/{@code j}/{@code c}/{@code aa.Nw}). ColorOS 17 MyDevices 17.25 restored the stable
 * names {@code getSupportDeviceApplications}, {@code addDeviceObserver(String, IDeviceAppObserver)},
 * and {@code onDeviceAdd}/{@code onDeviceRemoved}/{@code onDeviceUpdated}.
 * {@code DeviceAppConfigManager}, {@code AppAgentManager}, and {@code DeviceApp} kept their names.
 * The injection point is resolved from those shapes at runtime, so one build's obfuscated name is not pinned.
 */
public final class MyDevicesHook {
    private static final String PACKAGE = "io.github.miam1ku.mibandoplusbridge";
    private static final String MANAGER = "com.heytap.mydevices.core.config.DeviceAppConfigManager";
    private static final String APP_AGENT = "com.heytap.mydevices.core.agent.AppAgentManager";
    private static final String CARRIED_OBSERVER = "mydevices.cardObserver";
    private static final String CARRIED_CARD = "mydevices.cardId";
    private static volatile ObserverSession active;

    private MyDevicesHook() {}

    public static void install(Context context, ClassLoader hostLoader) throws Exception {
        String process = Application.getProcessName();
        if (process == null
                || !(process.equals("com.heytap.mydevices") || process.startsWith("com.heytap.mydevices:"))) {
            return;
        }
        hookDetailJump(context);
        hookIconPackage(hostLoader);
        hookPlaceholderIcon(context, hostLoader);
        hookCardIcon(context, hostLoader);
        hookCardPicture(context, hostLoader);
        if (!"com.heytap.mydevices".equals(process)) return;
        Class<?> deviceApp = Class.forName("com.oplus.mydevices.domain.entities.config.DeviceApp", false, hostLoader);
        Class<?> deletion = Class.forName("com.oplus.mydevices.domain.entities.config.DelDeviceMethod", false, hostLoader);
        // Min app and OS versions stay 0. Build 17.4 filters on the installed versionCode and ColorOS version, so a pinned 36 drops the card on a newer OS.
        Object app = deviceApp.getConstructor(String.class, String.class, long.class,
                        int.class, boolean.class, boolean.class, Boolean.class, deletion)
                .newInstance("小米手环桥接", PACKAGE, 0L, 0,
                        true, true, Boolean.TRUE, deletion.getField("NONE").get(null));
        Method packageName = deviceApp.getMethod("getPackageName");
        Class<?> manager = Class.forName(MANAGER, false, hostLoader);
        Class<?> agent = Class.forName(APP_AGENT, false, hostLoader);
        Method cache = cacheList(manager);
        Method support = method(manager, "getSupportDeviceApplications");
        Method register = registerMethod(agent);
        Method unregister = unregisterMethod(agent);
        Class<?> observerType = register.getParameterTypes()[1];
        Method removed = callback(observerType, "onDeviceRemoved", String.class, boolean.class, String.class);
        Method added = singleStringCallback(observerType, "onDeviceAdd", true);
        Method updated = singleStringCallback(observerType, "onDeviceUpdated", false);
        XC_MethodHook include = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.hasThrowable() || !(param.getResult() instanceof List<?> original)) return;
                // f() is declared to return CopyOnWriteArrayList. An ArrayList makes queryAppConfig fail, and device space then shows no cards.
                boolean cache = original instanceof CopyOnWriteArrayList<?>;
                if (!cache && (original.isEmpty() || !deviceApp.isInstance(original.get(0)))) return;
                List<Object> copy = cache ? new CopyOnWriteArrayList<>() : new ArrayList<>(original.size() + 1);
                boolean present = false;
                for (Object item : original) {
                    copy.add(item);
                    if (deviceApp.isInstance(item) && PACKAGE.equals(packageName.invoke(item))) present = true;
                }
                if (!present) copy.add(app);
                param.setResult(copy);
            }
        };
        XposedBridge.hookMethod(cache, include);
        if (support != null && !support.equals(cache)) XposedBridge.hookMethod(support, include);
        // Membership is decided from this companion table. ColorOS 16 has two (String)boolean methods; 17 names them isSupport and isSupportDetailPanel.
        for (Method method : manager.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != boolean.class) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != String.class) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable() && PACKAGE.equals(param.args[0])) param.setResult(Boolean.TRUE);
                }
            });
        }
        // A hot reload stops the old observer thread. The host keeps the observer it already handed over,
        // so this generation resumes with that same observer.
        HookRuntime.track(MyDevicesHook::unregister);
        Object carried = HookRuntime.carried(CARRIED_OBSERVER);
        if (observerType.isInstance(carried)) register(context, carried, removed, added, updated);
        XposedBridge.hookMethod(register, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable() && PACKAGE.equals(param.args[0])
                        && observerType.isInstance(param.args[1])) {
                    register(context, param.args[1], removed, added, updated);
                }
            }
        });
        XposedBridge.hookMethod(unregister, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!PACKAGE.equals(param.args[0])) return;
                HookRuntime.carry(CARRIED_OBSERVER, null);
                unregister();
            }
        });
        HookRuntime.log("DEVICE_CARD_BIND cache=" + cache.getName()
                + " register=" + register.getName()
                + " observer=" + observerType.getName());
    }

    /** Device space reads this cache. The method is {@code j} on 16 and {@code f} on 17; the return type stayed. */
    private static Method cacheList(Class<?> manager) {
        Method found = null;
        for (Method method : manager.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) continue;
            if (!CopyOnWriteArrayList.class.isAssignableFrom(method.getReturnType())) continue;
            if (found != null) throw new IllegalStateException("DEVICE_CARD_CACHE_AMBIGUOUS");
            found = method;
        }
        if (found == null) throw new IllegalStateException("DEVICE_CARD_CACHE_MISSING");
        return found;
    }

    /** 17 uses the stable name. 16 has one {@code (String, interface)} register method, and the second argument is the observer. */
    private static Method registerMethod(Class<?> agent) throws NoSuchMethodException {
        for (Method method : agent.getDeclaredMethods()) {
            if ("addDeviceObserver".equals(method.getName()) && method.getParameterCount() == 2
                    && method.getParameterTypes()[0] == String.class
                    && method.getParameterTypes()[1].isInterface()) return method;
        }
        Method found = null;
        for (Method method : agent.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 2 && params[0] == String.class && params[1].isInterface()) {
                if (found != null) throw new IllegalStateException("DEVICE_CARD_REGISTER_AMBIGUOUS");
                found = method;
            }
        }
        if (found == null) throw new NoSuchMethodException("DEVICE_CARD_REGISTER_MISSING");
        return found;
    }

    /**
     * 17 names this {@code removeDeviceObserver}. On 16 the unregister method is the only non-final
     * {@code (String)void} ({@code d}). {@code n} has the same signature and is final, so it is not the unregister.
     */
    private static Method unregisterMethod(Class<?> agent) throws NoSuchMethodException {
        Method named = method(agent, "removeDeviceObserver", String.class);
        if (named != null) return named;
        Method found = null;
        for (Method method : agent.getDeclaredMethods()) {
            int flags = method.getModifiers();
            if (Modifier.isStatic(flags) || Modifier.isFinal(flags) || method.getReturnType() != void.class) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != String.class) continue;
            if (found != null) throw new IllegalStateException("DEVICE_CARD_UNREGISTER_AMBIGUOUS");
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("DEVICE_CARD_UNREGISTER_MISSING");
        return found;
    }

    private static Method callback(Class<?> observer, String stableName, Class<?>... params) throws NoSuchMethodException {
        Method named = method(observer, stableName, params);
        if (named != null) return named;
        for (Method method : observer.getMethods()) {
            if (method.getReturnType() != void.class) continue;
            Class<?>[] actual = method.getParameterTypes();
            if (actual.length != params.length) continue;
            boolean same = true;
            for (int i = 0; i < params.length; i++) if (actual[i] != params[i]) same = false;
            if (same) return method;
        }
        throw new NoSuchMethodException("DEVICE_CARD_CALLBACK_MISSING");
    }

    /** Add and update are both {@code (String)void}. 17 names them. 16 declares {@code b} as add and {@code c} as update. */
    private static Method singleStringCallback(Class<?> observer, String stableName, boolean add) throws NoSuchMethodException {
        Method named = method(observer, stableName, String.class);
        if (named != null) return named;
        ArrayList<Method> found = new ArrayList<>();
        for (Method method : observer.getMethods()) {
            if (method.getDeclaringClass() == Object.class || method.getReturnType() != void.class) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0] == String.class) found.add(method);
        }
        found.sort(Comparator.comparing(Method::getName));
        if (found.size() != 2) throw new NoSuchMethodException("DEVICE_CARD_CALLBACK_AMBIGUOUS");
        return add ? found.get(0) : found.get(1);
    }

    private static Method method(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException ignored) {
            try {
                return type.getMethod(name, params);
            } catch (NoSuchMethodException missing) {
                return null;
            }
        }
    }



    /**
     * A control-center tap ends in {@code startActivity}. The redirect matches the detail action and the band id,
     * then points the intent at the Health panel registered for that action, so one build's obfuscated class is not pinned.
     */
    private static void hookDetailJump(Context context) {
        XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null) return;
                for (Object arg : param.args) {
                    if (!(arg instanceof Intent intent)) continue;
                    if (NativePanel.redirect(context, intent)) HookRuntime.log("DETAIL_JUMP_NATIVE");
                    return;
                }
            }
        });
    }

    /**
     * Device center looks up the icon plugin by the package that provides the device.
     * The bridge package has no plugin, so the lookup falls through to the default phone picture.
     * When the row is this band, the package becomes Health's, and the icons Health already registered are used.
     */
    private static void hookIconPackage(ClassLoader loader) {
        Class<?> type;
        try {
            type = Class.forName("com.oplus.mydevices.domain.entities.device.DeviceInfoKt", false, loader);
        } catch (ClassNotFoundException missing) {
            HookRuntime.log("ICON_PACKAGE_HOOK_UNAVAILABLE");
            return;
        }
        Method found = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != String.class) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0].isPrimitive()) continue;
            String name = method.getName();
            if (!name.contains("Plugin") && !name.contains("plugin") && !name.contains("Package")) continue;
            found = method;
            if ("getPluginLookupPackage".equals(name)) break;
        }
        if (found == null) {
            HookRuntime.log("ICON_PACKAGE_HOOK_UNAVAILABLE");
            return;
        }
        XposedBridge.hookMethod(found, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || param.args.length == 0 || !ourBand(param.args[0])) return;
                param.setResult(NativePanel.HEALTH);
            }
        });
        HookRuntime.log("ICON_PACKAGE_HOOK " + found.getName());
    }

    private static boolean ourBand(Object device) {
        if (device == null) return false;
        for (String name : new String[]{"getDeviceAppPackage", "getPackageName"}) {
            try {
                if (PACKAGE.equals(String.valueOf(XposedHelpers.callMethod(device, name)))) return true;
            } catch (Throwable ignored) { }
        }
        for (String name : new String[]{"getMyDeviceId", "getDeviceId", "getId"}) {
            try {
                String id = String.valueOf(XposedHelpers.callMethod(device, name));
                if (id.startsWith("miband11_")) return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    /**
     * The placeholder method is {@code (String, DeviceType, Integer) -> int}. The class name changes by version.
     * With no plugin registered it returns {@code icon_default_device}. A band uses {@code default_wristband};
     * a watch shape uses {@code default_watch}.
     */
    private static void hookPlaceholderIcon(Context context, ClassLoader loader) {
        Class<?> deviceType;
        try {
            deviceType = Class.forName("com.oplus.mydevices.domain.entities.device.DeviceType", false, loader);
        } catch (ClassNotFoundException missing) {
            HookRuntime.log("ICON_PLACEHOLDER_HOOK_UNAVAILABLE");
            return;
        }
        int watch = context.getResources().getIdentifier("default_watch", "drawable", "com.heytap.mydevices");
        int band = context.getResources().getIdentifier("default_wristband", "drawable", "com.heytap.mydevices");
        if (watch == 0 && band == 0) {
            HookRuntime.log("ICON_PLACEHOLDER_HOOK_UNAVAILABLE");
            return;
        }
        int hooked = 0;
        android.content.pm.ApplicationInfo info = context.getApplicationInfo();
        java.util.List<String> apks = new java.util.ArrayList<>();
        if (info.sourceDir != null) apks.add(info.sourceDir);
        if (info.splitSourceDirs != null) {
            for (String split : info.splitSourceDirs) apks.add(split);
        }
        for (String apk : apks) {
            try {
                for (String name : DexAnchors.classNames(apk)) {
                    if (!name.startsWith("aa.") || name.indexOf('$') >= 0 || name.length() > 6) continue;
                    Class<?> type;
                    try {
                        type = Class.forName(name, false, loader);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    for (Method method : type.getDeclaredMethods()) {
                        if (method.getReturnType() != int.class) continue;
                        Class<?>[] params = method.getParameterTypes();
                        if (params.length != 3 || params[0] != String.class || params[1] != deviceType
                                || params[2] != Integer.class) continue;
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                int icon = placeholderIcon(param.args, watch, band);
                                if (icon == 0) return;
                                param.setResult(icon);
                            }
                        });
                        hooked++;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        HookRuntime.log(hooked == 0 ? "ICON_PLACEHOLDER_HOOK_UNAVAILABLE" : "ICON_PLACEHOLDER_HOOK " + hooked);
    }

    /**
     * Device space rewrites a picture URL to its own file provider, using the last path segment.
     * The picture is copied into that cache file. Returning another app's file descriptor here
     * takes down the whole device-space process.
     */
    private static void hookCardIcon(Context context, ClassLoader loader) {
        try {
            Class<?> provider = Class.forName(
                    "com.oplus.mydevices.quickapp.provider.DeviceQuickAppCardWidgetProvider", false, loader);
            XposedHelpers.findAndHookMethod(provider, "openFile", android.net.Uri.class, String.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            android.net.Uri uri = (android.net.Uri) param.args[0];
                            java.util.List<String> segments = uri == null ? java.util.List.of() : uri.getPathSegments();
                            if (segments.isEmpty() || !segments.get(0).matches("miband-body-\\d+\\.png")) return;
                            try {
                                android.os.Bundle display = context.getContentResolver().call(
                                        DeviceCardProvider.URI, "bandDisplay", null, null);
                                if (display == null) return;
                                String image = display.getString("imageUri", "");
                                if (image == null || !image.startsWith("content://" + DeviceCardProvider.AUTHORITY + "/")) return;
                                java.io.File dir = new java.io.File(context.getCacheDir(), "device_image");
                                java.io.File dest = new java.io.File(dir, segments.get(0));
                                if (dest.isFile() && dest.length() > 0) return;
                                if (!dir.isDirectory() && !dir.mkdirs()) return;
                                try (java.io.InputStream in = context.getContentResolver().openInputStream(
                                        android.net.Uri.parse(image));
                                     java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                                    if (in == null) return;
                                    byte[] buffer = new byte[8192];
                                    int read;
                                    while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
                                }
                            } catch (Throwable failure) {
                                HookRuntime.log("SPACE_IMAGE_UNAVAILABLE " + failure.getClass().getSimpleName());
                            }
                        }
                    });
            HookRuntime.log("SPACE_IMAGE_HOOK openFile");
        } catch (Throwable failure) {
            HookRuntime.log("SPACE_IMAGE_HOOK_UNAVAILABLE " + failure.getClass().getSimpleName());
        }
    }

    /** The card the user sees belongs to Health. Its picture URL is replaced with the preview. */
    private static void hookCardPicture(Context context, ClassLoader loader) {
        try {
            Class<?> info = Class.forName(
                    "com.oplus.mydevices.domain.entities.device.DeviceInfo", false, loader);
            XposedHelpers.findAndHookMethod(info, "getDevicePictureUrl", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    String image = pictureFor(context, param.thisObject);
                    if (!image.isEmpty()) param.setResult(image);
                }
            });
            HookRuntime.log("SPACE_PICTURE_HOOK getDevicePictureUrl");
        } catch (Throwable failure) {
            HookRuntime.log("SPACE_PICTURE_HOOK_UNAVAILABLE " + failure.getClass().getSimpleName());
        }
    }

    private static volatile String pictureMac = "";
    private static volatile String pictureUri = "";
    private static volatile long pictureAt;

    private static String pictureFor(Context context, Object device) {
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - pictureAt > 2_000) {
                pictureAt = now;
                android.os.Bundle display = context.getContentResolver().call(
                        DeviceCardProvider.URI, "bandDisplay", null, null);
                if (display != null) {
                    String mac = display.getString("mac", "");
                    String image = display.getString("imageUri", "");
                    pictureMac = mac == null ? "" : mac;
                    pictureUri = image == null ? "" : image;
                }
            }
            if (pictureUri.isEmpty() || pictureMac.isEmpty()) return "";
            String mac = String.valueOf(XposedHelpers.callMethod(device, "getMac"));
            if (!io.github.miam1ku.mibandoplusbridge.data.MacIds.same(pictureMac, mac)) return "";
            return pictureUri;
        } catch (Throwable failure) {
            return "";
        }
    }

    private static int placeholderIcon(Object[] args, int watch, int band) {
        if (args == null || args.length < 2 || args[1] == null) return 0;
        String pkg = args[0] instanceof String text ? text : "";
        if (!PACKAGE.equals(pkg) && !NativePanel.HEALTH.equals(pkg)) return 0;
        try {
            String typeName = String.valueOf(XposedHelpers.callMethod(args[1], "getTypeName"));
            if ("wristband".equals(typeName)) return band != 0 ? band : watch;
            if ("watch".equals(typeName)) return watch != 0 ? watch : band;
        } catch (Throwable ignored) {
            return 0;
        }
        return 0;
    }


    private static synchronized void register(Context context, Object observer,
                                               Method removed, Method added, Method updated) {
        if (active != null && active.observer == observer) return;
        unregister();
        HookRuntime.carry(CARRIED_OBSERVER, observer);
        HandlerThread thread = new HandlerThread("band-device-card-observer");
        thread.start();
        ObserverSession session = new ObserverSession(context.getApplicationContext(), observer,
                removed, added, updated, thread);
        active = session;
        try {
            session.context.getContentResolver().registerContentObserver(DeviceCardProvider.URI, false, session.changes);
            session.handler.post(session::refresh);
        } catch (RuntimeException unavailable) {
            active = null;
            session.thread.quitSafely();
            HookRuntime.log("DEVICE_CARD_OBSERVER_UNAVAILABLE", unavailable);
        }
    }

    private static synchronized void unregister() {
        ObserverSession session = active;
        active = null;
        if (session != null) {
            session.context.getContentResolver().unregisterContentObserver(session.changes);
            session.handler.removeCallbacksAndMessages(null);
            session.thread.quitSafely();
        }
    }

    private record Snapshot(String id, long revision) {}

    private static final class ObserverSession {
        final Context context;
        final Object observer;
        final Method removed;
        final Method added;
        final Method updated;
        final HandlerThread thread;
        final Handler handler;
        final ContentObserver changes;
        Snapshot last;
        String withdrawn;

        ObserverSession(Context context, Object observer, Method removed, Method added,
                        Method updated, HandlerThread thread) {
            this.context = context;
            this.observer = observer;
            this.removed = removed;
            this.added = added;
            this.updated = updated;
            this.thread = thread;
            handler = new Handler(thread.getLooper());
            changes = new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange) { refresh(); }
            };
        }

        void refresh() {
            if (active != this) return;
            try (Cursor cursor = context.getContentResolver().query(DeviceCardProvider.URI,
                    new String[]{"device_id", "device_data"}, null, null, null)) {
                if (cursor == null) return;
                Snapshot now = null;
                if (cursor.moveToFirst()) {
                    String id = cursor.getString(0);
                    if (id != null && !id.isBlank()) {
                        now = new Snapshot(id, new JSONObject(cursor.getString(1)).optLong("revision"));
                    }
                }
                if (active != this) return;
                Snapshot before = last;
                // Hot reload drops this session. The host still shows the card the previous generation added.
                if (before == null) {
                    Object remembered = HookRuntime.carried(CARRIED_CARD);
                    if (remembered instanceof String id && !id.isBlank()) before = new Snapshot(id, Long.MIN_VALUE);
                }
                if (now == null && before == null) before = published();
                if (before != null && (now == null || !before.id().equals(now.id()))
                        && !before.id().equals(withdrawn)) {
                    removed.invoke(observer, before.id(), false, PACKAGE);
                    withdrawn = before.id();
                    HookRuntime.carry(CARRIED_CARD, null);
                }
                if (now != null) {
                    withdrawn = null;
                    HookRuntime.carry(CARRIED_CARD, now.id());
                    if (before == null || !before.id().equals(now.id())) {
                        added.invoke(observer, now.id());
                    } else if (before.revision() != now.revision()) {
                        updated.invoke(observer, now.id());
                    }
                }
                last = now;
            } catch (Exception unavailable) {
                HookRuntime.log("DEVICE_CARD_OBSERVER_UNAVAILABLE", unavailable);
            }
        }

        /** The registered band, even when the card query is empty because Health already has one. */
        private Snapshot published() {
            try {
                android.os.Bundle display = context.getContentResolver().call(
                        DeviceCardProvider.URI, "bandDisplay", null, null);
                if (display == null || !display.getBoolean("registered", false)) return null;
                String id = display.getString("deviceId", "");
                if (id == null || id.isBlank()) return null;
                return new Snapshot(id, Long.MIN_VALUE);
            } catch (RuntimeException unavailable) {
                return null;
            }
        }
    }
}
