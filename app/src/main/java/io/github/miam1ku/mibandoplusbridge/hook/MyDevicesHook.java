// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;
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
    private static final String HEALTH = "com.heytap.health";
    private static final String NATIVE_PANEL = "com.heytap.health.linkage.ui.DeviceDetailsPanelActivity";
    private static final String OPPO_BAND_MODEL = "OB19B1";
    private static final String MANAGER = "com.heytap.mydevices.core.config.DeviceAppConfigManager";
    private static final String APP_AGENT = "com.heytap.mydevices.core.agent.AppAgentManager";
    private static final ThreadLocal<Boolean> OPENING_PANEL = new ThreadLocal<>();
    private static volatile ObserverSession active;

    private MyDevicesHook() {}

    public static void install(Context context, ClassLoader hostLoader) throws Exception {
        String process = Application.getProcessName();
        if (process == null
                || !(process.equals("com.heytap.mydevices") || process.startsWith("com.heytap.mydevices:"))) {
            return;
        }
        hookDetailJump(context, hostLoader);
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
     * 点卡片打开 OHealth 原生手环详情面板。只拦设备号以 {@code miband11_} 开头的跳转。
     */
    private static void hookDetailJump(Context context, ClassLoader loader) {
        String[] types = {
                "com.oplus.mydevices.opsynergy.OpDeviceSdkProxy",
                "com.heytap.mydevices.plugin.linker.core.OPSynergyScannerImpl",
                "com.heytap.mydevices.plugin.linker.core.CoreOpDeviceAction",
                "com.oplus.mydevices.quickapp.action.QuickAppCardAction",
                "com.oplus.mydevices.quickapp.provider.DeviceQuickAppCardWidgetProvider"
        };
        XC_MethodHook open = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                String deviceId = bandDeviceId(param.args);
                if (deviceId == null) return;
                if (!(param.method instanceof Method method)) return;
                Class<?> ret = method.getReturnType();
                String methodName = method.getName();
                boolean cardAction = method.getDeclaringClass().getName().endsWith("QuickAppCardAction");
                boolean jump = methodName.contains("Detail") || methodName.contains("detail")
                        || methodName.contains("Jump") || methodName.contains("jump")
                        || "A".equals(methodName) || "x".equals(methodName);
                if (cardAction && !jump) return;
                if (!stealJump(ret) && !jump) return;
                if (!openNativePanel(context, deviceId)) return;
                if (ret == boolean.class || ret == Boolean.class) param.setResult(Boolean.TRUE);
                else if (Bundle.class.isAssignableFrom(ret)) param.setResult(new Bundle());
                else param.setResult(null);
            }
        };
        for (String name : types) {
            Class<?> type;
            try {
                type = Class.forName(name, false, loader);
            } catch (ClassNotFoundException missing) {
                continue;
            }
            boolean cardAction = name.endsWith("QuickAppCardAction");
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 0) continue;
                boolean hasString = false;
                for (Class<?> paramType : params) {
                    if (paramType == String.class) { hasString = true; break; }
                }
                if (!hasString) continue;
                String methodName = method.getName();
                boolean named = methodName.contains("Detail") || methodName.contains("detail")
                        || methodName.contains("Jump") || methodName.contains("jump");
                if (!named && !cardAction) continue;
                XposedBridge.hookMethod(method, open);
                if (cardAction) {
                    Log.i(TAG, "DETAIL_HOOKED " + method.getName()
                            + " args=" + params.length + " ret=" + method.getReturnType().getSimpleName());
                }
            }
        }
        XC_MethodHook rewrite = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(OPENING_PANEL.get())) return;
                Intent intent = null;
                for (Object arg : param.args) {
                    if (arg instanceof Intent found) {
                        intent = found;
                        break;
                    }
                }
                if (intent == null) return;
                ComponentName cmp = intent.getComponent();
                if (cmp != null) {
                    String cls = cmp.getClassName();
                    if (cls.endsWith(".BandDetailsActivity") || cls.endsWith(".DeviceDetailsPanelActivity")) return;
                }
                String pkg = intent.getPackage();
                if (pkg == null && cmp != null) pkg = cmp.getPackageName();
                if (!PACKAGE.equals(pkg)) return;
                String action = intent.getAction();
                if (!"com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL".equals(action)
                        && !"com.oplus.mydevices.ACTION_DEVICE_DETAILED_PAGE".equals(action)) return;
                String deviceId = intent.getStringExtra("device_id");
                if (deviceId == null) deviceId = intent.getStringExtra("key_device_id");
                if (!openNativePanel(context, deviceId)) return;
                param.setResult(null);
            }
        };
        XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", rewrite);
    }

    private static boolean stealJump(Class<?> ret) {
        if (ret == null || ret == void.class || ret == Void.class) return true;
        if (ret == boolean.class || ret == Boolean.class) return true;
        if (Bundle.class.isAssignableFrom(ret)) return true;
        if (ret == Object.class) return true;
        return "kotlin.Unit".equals(ret.getName());
    }

    private static String bandDeviceId(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String id && id.matches("miband11_[0-9a-f]{64}")) return id;
        }
        return null;
    }
    private static boolean openNativePanel(Context context, String deviceId) {
        if (deviceId == null || !deviceId.matches("miband11_[0-9a-f]{64}")) return false;
        return openBridgeDetails(context, deviceId);
    }


    private static boolean openBridgeDetails(Context context, String deviceId) {
        if (Boolean.TRUE.equals(OPENING_PANEL.get())) return false;
        String mac = "";
        String name = "";
        try (Cursor cursor = context.getContentResolver().query(DeviceCardProvider.URI,
                new String[]{"device_mac", "device_data"}, "device_id=?", new String[]{deviceId}, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                mac = cursor.getString(cursor.getColumnIndexOrThrow("device_mac"));
                name = new JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("device_data")))
                        .optString("mDeviceName", "");
            }
        } catch (RuntimeException | org.json.JSONException ignored) {
            mac = "";
        }
        if (mac == null || mac.isBlank()) return false;
        try {
            Intent intent = new Intent("com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL");
            intent.setClassName(HEALTH, NATIVE_PANEL);
            intent.putExtra("device_id", deviceId);
            intent.putExtra("device_title", name);
            intent.putExtra("model_id", OPPO_BAND_MODEL);
            intent.putExtra("device_mac_info", mac);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            OPENING_PANEL.set(Boolean.TRUE);
            try {
                context.getApplicationContext().startActivity(intent);
            } finally {
                OPENING_PANEL.remove();
            }
            Log.i(TAG, "DETAIL_JUMP_NATIVE");
            return true;
        } catch (RuntimeException failure) {
            Log.i(TAG, "DETAIL_JUMP_NATIVE_UNAVAILABLE " + failure.getClass().getSimpleName());
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
