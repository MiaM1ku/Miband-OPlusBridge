// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.Activity;
import android.content.Intent;
import android.os.Process;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.util.Locale;

/** Logs the health login jump and the crash that restarts the process. No account ids or tokens. */
public final class OHealthLoginDebug {
    private static volatile boolean wrapping;

    private OHealthLoginDebug() {}

    public static void install(ClassLoader loader) {
        wrapHandler();
        XposedBridge.hookAllMethods(Thread.class, "setDefaultUncaughtExceptionHandler", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { wrapHandler(); }
        });
        XposedBridge.hookAllMethods(Activity.class, "startActivity", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Intent intent = null;
                for (Object arg : param.args) {
                    if (arg instanceof Intent) { intent = (Intent) arg; break; }
                }
                if (intent == null) return;
                String target = intent.getComponent() == null ? "" : intent.getComponent().getClassName();
                String action = intent.getAction() == null ? "" : intent.getAction();
                String blob = (target + " " + action).toLowerCase(Locale.US);
                if (!blob.contains("login") && !blob.contains("signin") && !blob.contains("account")) return;
                log("OHEALTH_LOGIN_START from=" + param.thisObject.getClass().getSimpleName()
                        + " to=" + simple(target) + " action=" + clip(action, 80));
            }
        });
        XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                String name = param.thisObject.getClass().getName().toLowerCase(Locale.US);
                if (!name.contains("login") && !name.contains("signin") && !name.contains("account")) return;
                Throwable failure = param.getThrowable();
                log("OHEALTH_LOGIN_SCREEN name=" + param.thisObject.getClass().getSimpleName()
                        + (failure == null ? "" : " failed=" + failure.getClass().getSimpleName()));
            }
        });
        XposedBridge.hookAllMethods(Process.class, "killProcess", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                log("OHEALTH_PROCESS_EXIT kind=kill" + caller());
            }
        });
        XposedBridge.hookAllMethods(System.class, "exit", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                log("OHEALTH_PROCESS_EXIT kind=exit" + caller());
            }
        });
        log("OHEALTH_LOGIN_DEBUG_INSTALLED loader=" + (loader == null ? "none" : "app"));
    }

    private static void wrapHandler() {
        if (wrapping) return;
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof Tap) return;
        wrapping = true;
        try {
            Thread.setDefaultUncaughtExceptionHandler(new Tap(current));
        } finally {
            wrapping = false;
        }
    }

    private static void log(String line) {
        android.util.Log.i("OplusBandBridge", line);
        XposedBridge.log("OplusBandBridge " + line);
    }

    private static String caller() {
        StackTraceElement[] frames = new Throwable().getStackTrace();
        for (StackTraceElement frame : frames) {
            String type = frame.getClassName();
            if (type.startsWith("io.github.miam1ku.") || type.startsWith("de.robv.android.xposed.")) continue;
            if (type.startsWith("com.heytap.") || type.startsWith("com.oplus.")
                    || type.startsWith("com.platform.")) {
                return " from=" + frame.getClassName() + "." + frame.getMethodName();
            }
        }
        return " from=unknown";
    }

    private static String simple(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        String flat = value.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max);
    }

    private static final class Tap implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler next;

        Tap(Thread.UncaughtExceptionHandler next) { this.next = next; }

        @Override public void uncaughtException(Thread thread, Throwable failure) {
            StringBuilder line = new StringBuilder("OHEALTH_CRASH thread=")
                    .append(thread == null ? "none" : thread.getName())
                    .append(" type=").append(failure == null ? "none" : failure.getClass().getSimpleName())
                    .append(" msg=").append(clip(failure == null ? "" : String.valueOf(failure.getMessage()), 160));
            Throwable cursor = failure;
            int shown = 0;
            while (cursor != null && shown < 6) {
                for (StackTraceElement frame : cursor.getStackTrace()) {
                    if (shown == 6) break;
                    String type = frame.getClassName();
                    if (type.startsWith("de.robv.android.xposed.")) continue;
                    line.append(" at ").append(type).append('.').append(frame.getMethodName());
                    shown++;
                }
                cursor = cursor.getCause();
                if (cursor != null && shown < 6) {
                    line.append(" cause=").append(cursor.getClass().getSimpleName());
                }
            }
            log(line.toString());
            if (next != null) next.uncaughtException(thread, failure);
        }
    }
}
