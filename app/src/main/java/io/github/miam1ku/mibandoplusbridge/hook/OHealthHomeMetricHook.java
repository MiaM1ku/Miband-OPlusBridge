// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** Asks home cards already on screen to read again. Does not rewrite their text. */
public final class OHealthHomeMetricHook {
    private static final String HOST = "com.heytap.health";
    private static final String BASE = "com.heytap.health.main.card.common.HealthBaseCard";
    private static final String CARD_PREFIX = "com.heytap.health.main.card.";
    private static final WeakHashMap<Object, Boolean> CARDS = new WeakHashMap<>();
    private static Handler main;
    private static boolean reloadPosted;
    private static final XC_MethodHook BIND = new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam param) {
            Object card = param.thisObject;
            if (card == null) return;
            try {
                synchronized (OHealthHomeMetricHook.class) {
                    CARDS.put(card, Boolean.TRUE);
                }
            } catch (Throwable failure) {
                Log.i("OplusBandBridge", "OHEALTH_HOME_RELOAD_HOOK_FAIL");
            }
        }
    };

    private OHealthHomeMetricHook() {}

    public static synchronized void install(Context supplied, ClassLoader loader) {
        if (supplied == null || !HOST.equals(supplied.getPackageName())) return;
        Class<?> base;
        try {
            base = Class.forName(BASE, false, loader);
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_HOME_RELOAD_HOOK_FAIL");
            return;
        }
        hookBind(base);
        ApplicationInfo info = supplied.getApplicationInfo();
        List<String> apks = new ArrayList<>();
        if (info.sourceDir != null) apks.add(info.sourceDir);
        if (info.splitSourceDirs != null) {
            for (String split : info.splitSourceDirs) apks.add(split);
        }
        Set<String> seen = new HashSet<>();
        for (String apk : apks) {
            try {
                for (String name : DexAnchors.classNames(apk)) {
                    if (!name.startsWith(CARD_PREFIX) || name.indexOf('$') >= 0 || !seen.add(name)) continue;
                    Class<?> type;
                    try {
                        type = Class.forName(name, false, loader);
                    } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                        continue;
                    }
                    if (type != base && base.isAssignableFrom(type)
                            && !Modifier.isAbstract(type.getModifiers())) {
                        hookBind(type);
                    }
                }
            } catch (Throwable failure) {
                Log.i("OplusBandBridge", "OHEALTH_HOME_RELOAD_HOOK_FAIL");
            }
        }
    }

    /** One main-thread pass. A second call while that pass is queued does not add another. */
    public static void requestReload() {
        synchronized (OHealthHomeMetricHook.class) {
            if (reloadPosted) return;
            if (main == null) main = new Handler(Looper.getMainLooper());
            reloadPosted = true;
            main.post(OHealthHomeMetricHook::reload);
        }
    }

    private static void reload() {
        List<Object> cards;
        synchronized (OHealthHomeMetricHook.class) {
            reloadPosted = false;
            cards = new ArrayList<>(CARDS.keySet());
        }
        for (Object card : cards) {
            if (card == null) continue;
            Class<?> type = card.getClass();
            try {
                Method refresh = declaredVoid(type, "refresh");
                if (refresh == null) refresh = declaredVoid(type, "refreshViewIfNeed");
                if (refresh == null) {
                    if (declares(type, "refresh") || declares(type, "refreshViewIfNeed")) {
                        Log.i("OplusBandBridge", "OHEALTH_HOME_RELOAD_SKIP " + type.getName());
                    }
                    continue;
                }
                refresh.invoke(card);
            } catch (Throwable failure) {
                Log.i("OplusBandBridge", "OHEALTH_HOME_RELOAD_FAIL " + type.getName());
            }
        }
    }

    private static void hookBind(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals("onCommonBindViewHolder")) continue;
            try {
                XposedBridge.hookAllMethods(type, "onCommonBindViewHolder", BIND);
            } catch (Throwable failure) {
                Log.i("OplusBandBridge", "OHEALTH_HOME_RELOAD_HOOK_FAIL");
            }
            return;
        }
    }

    /** No-arg void declared on this class, not an inherited or abstract method. */
    private static Method declaredVoid(Class<?> type, String name) {
        Method synthetic = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 0
                    || method.getReturnType() != void.class
                    || Modifier.isAbstract(method.getModifiers())) continue;
            if (method.isSynthetic()) {
                synthetic = method;
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        if (synthetic == null) return null;
        synthetic.setAccessible(true);
        return synthetic;
    }

    private static boolean declares(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) return true;
        }
        return false;
    }
}
