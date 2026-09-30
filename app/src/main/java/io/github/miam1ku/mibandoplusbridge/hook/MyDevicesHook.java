// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
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
 * 把桥接登记进「设备空间」。
 *
 * <p>ColorOS 16 的 MyDevices 17.4 把伴侣表和观察者收成 R8 短名（{@code n}/{@code j}/{@code c}/{@code aa.Nw}）。
 * ColorOS 17 的 MyDevices 17.25 改回稳定接口：{@code getSupportDeviceApplications}、
 * {@code addDeviceObserver(String, IDeviceAppObserver)}、{@code onDeviceAdd}/{@code onDeviceRemoved}/{@code onDeviceUpdated}。
 * 两边类名 {@code DeviceAppConfigManager}、{@code AppAgentManager}、{@code DeviceApp} 没变。
 * 注入点按这些特征在运行时解析，不写死某一版的混淆名。
 */
public final class MyDevicesHook {
    private static final String TAG = "OplusBandBridge";
    private static final String PACKAGE = "io.github.miam1ku.mibandoplusbridge";
    private static final String MANAGER = "com.heytap.mydevices.core.config.DeviceAppConfigManager";
    private static final String APP_AGENT = "com.heytap.mydevices.core.agent.AppAgentManager";
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
        if (!"com.heytap.mydevices".equals(process)) return;
        Class<?> deviceApp = Class.forName("com.oplus.mydevices.domain.entities.config.DeviceApp", false, hostLoader);
        Class<?> deletion = Class.forName("com.oplus.mydevices.domain.entities.config.DelDeviceMethod", false, hostLoader);
        // 最低版本和最低系统都写 0：17.4 用已安装 versionCode 与 ColorOS 版本过滤，写死 36 会在大版本上被丢掉。
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
                // f() 声明返回 CopyOnWriteArrayList。换成 ArrayList 会让 queryAppConfig 整段失败，设备空间一张卡都没有。
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
        // 成员判断只看这张伴侣表。16 上是两个 (String)boolean，17 上是 isSupport / isSupportDetailPanel。
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
                if (PACKAGE.equals(param.args[0])) unregister();
            }
        });
        Log.i(TAG, "DEVICE_CARD_BIND cache=" + cache.getName()
                + " register=" + register.getName()
                + " observer=" + observerType.getName());
    }

    /** 设备空间读到的就是这份缓存。16 的方法名是 {@code j}，17 是 {@code f}，返回类型没变。 */
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

    /** 17 用稳定名。16 只有一个 {@code (String, 接口)} 的注册方法，第二参就是观察者。 */
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
     * 17 的方法名是 {@code removeDeviceObserver}。16 上注销是唯一的非 final {@code (String)void}
     * （{@code d}）；同签名的 {@code n} 是 final，不做注销。
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

    /** 添加和更新都是 {@code (String)void}。17 有方法名；16 按声明序 {@code b} 添加、{@code c} 更新。 */
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
     * 控制中心的点击最终都是一次 {@code startActivity}。只认详情动作和手环设备号，
     * 把意图改到健康里注册了该动作的面板，不挂钩某一版的混淆类。
     */
    private static void hookDetailJump(Context context) {
        XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null) return;
                for (Object arg : param.args) {
                    if (!(arg instanceof Intent intent)) continue;
                    if (NativePanel.redirect(context, intent)) Log.i(TAG, "DETAIL_JUMP_NATIVE");
                    return;
                }
            }
        });
    }

    /**
     * 设备中心按「提供这台设备的包名」找图标插件。桥接包没有插件，就落到默认手机图。
     * 控制中心不走这条，直接按设备类型用自带的手表图。
     * 查到的是这只手环时，改用健康的包名，这样用到的是健康已经登记的那套图。
     */
    private static void hookIconPackage(ClassLoader loader) {
        Class<?> type;
        try {
            type = Class.forName("com.oplus.mydevices.domain.entities.device.DeviceInfoKt", false, loader);
        } catch (ClassNotFoundException missing) {
            Log.i(TAG, "ICON_PACKAGE_HOOK_UNAVAILABLE");
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
            Log.i(TAG, "ICON_PACKAGE_HOOK_UNAVAILABLE");
            return;
        }
        XposedBridge.hookMethod(found, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || param.args.length == 0 || !ourBand(param.args[0])) return;
                param.setResult(NativePanel.HEALTH);
            }
        });
        Log.i(TAG, "ICON_PACKAGE_HOOK " + found.getName());
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
     * 设备中心的占位图方法签名是 {@code (String, DeviceType, Integer) -> int}，类名会随版本变。
     * 插件没登记时它返回 {@code icon_default_device}。这只手环改成设备空间自带的手表图。
     */
    private static void hookPlaceholderIcon(Context context, ClassLoader loader) {
        Class<?> deviceType;
        try {
            deviceType = Class.forName("com.oplus.mydevices.domain.entities.device.DeviceType", false, loader);
        } catch (ClassNotFoundException missing) {
            Log.i(TAG, "ICON_PLACEHOLDER_HOOK_UNAVAILABLE");
            return;
        }
        int watch = context.getResources().getIdentifier("default_watch", "drawable", "com.heytap.mydevices");
        if (watch == 0) {
            Log.i(TAG, "ICON_PLACEHOLDER_HOOK_UNAVAILABLE");
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
                                if (!wearableLookup(param.args)) return;
                                param.setResult(watch);
                            }
                        });
                        hooked++;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        Log.i(TAG, hooked == 0 ? "ICON_PLACEHOLDER_HOOK_UNAVAILABLE" : "ICON_PLACEHOLDER_HOOK " + hooked);
    }

    private static boolean wearableLookup(Object[] args) {
        if (args == null || args.length < 2 || args[1] == null) return false;
        String pkg = args[0] instanceof String text ? text : "";
        if (!PACKAGE.equals(pkg) && !NativePanel.HEALTH.equals(pkg)) return false;
        try {
            String typeName = String.valueOf(XposedHelpers.callMethod(args[1], "getTypeName"));
            return "watch".equals(typeName) || "wristband".equals(typeName);
        } catch (Throwable ignored) {
            return false;
        }
    }


    private static synchronized void register(Context context, Object observer,
                                               Method removed, Method added, Method updated) {
        if (active != null && active.observer == observer) return;
        unregister();
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
            Log.w(TAG, "DEVICE_CARD_OBSERVER_UNAVAILABLE", unavailable);
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
                if (before != null && (now == null || !before.id().equals(now.id()))) {
                    removed.invoke(observer, before.id(), false, PACKAGE);
                }
                if (now != null) {
                    if (before == null || !before.id().equals(now.id())) {
                        added.invoke(observer, now.id());
                    } else if (before.revision() != now.revision()) {
                        updated.invoke(observer, now.id());
                    }
                }
                last = now;
            } catch (Exception unavailable) {
                Log.w(TAG, "DEVICE_CARD_OBSERVER_UNAVAILABLE", unavailable);
            }
        }
    }
}
