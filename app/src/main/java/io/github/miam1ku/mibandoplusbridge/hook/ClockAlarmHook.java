// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rings and dismissals between the OPPO clock and the band. Hook targets come from log strings, not renamed types. */
public final class ClockAlarmHook {
    public static final String CLOCK = "com.coloros.alarmclock";
    static final String AUTHORITY = "io.github.miam1ku.mibandoplusbridge.phone-alarm";
    private static final String RING_ANCHOR = "AlarmKlaxon.start() alarmSchedule:";
    private static final String STOP_ANCHOR = "AlarmService.onStartCommand() with";
    private static final Pattern SCHEDULE_ID = Pattern.compile("\\bmId=(\\d+)");
    private static final Pattern SCHEDULE_LABEL = Pattern.compile("mLabel='([^']*)'");
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static volatile int ringingScheduleId = -1;

    private ClockAlarmHook() {}

    public static void install(Context context, ClassLoader loader) {
        if (context == null || loader == null || !installed.compareAndSet(false, true)) return;
        List<String> apks = new ArrayList<>();
        if (context.getApplicationInfo() != null) {
            if (context.getApplicationInfo().sourceDir != null) apks.add(context.getApplicationInfo().sourceDir);
            String[] splits = context.getApplicationInfo().splitSourceDirs;
            if (splits != null) for (String split : splits) if (split != null) apks.add(split);
        }
        int ring = 0;
        int stop = 0;
        try {
            ring = hookRing(apks, loader);
        } catch (Throwable failure) {
            skip(failure);
        }
        try {
            stop = hookStop(apks, loader);
        } catch (Throwable failure) {
            skip(failure);
        }
        report(context, ring, stop);
    }

    private static int hookRing(List<String> apks, ClassLoader loader) throws Exception {
        List<Method> methods = anchored(apks, loader, RING_ANCHOR);
        if (methods.isEmpty()) methods = named(loader, "com.oplus.alarmclock.alert.AlarmKlaxon", "start");
        if (methods.isEmpty()) throw new NoSuchMethodException(RING_ANCHOR);
        for (Method method : methods) {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        forwardRing(contextOf(param), param.args);
                    } catch (Throwable failure) {
                        skip(failure);
                    }
                }
            });
        }
        return methods.size();
    }

    private static int hookStop(List<String> apks, ClassLoader loader) throws Exception {
        List<Method> methods = anchored(apks, loader, STOP_ANCHOR);
        if (methods.isEmpty()) methods = named(loader, "com.oplus.alarmclock.alert.AlarmService", "onStartCommand");
        if (methods.isEmpty()) throw new NoSuchMethodException(STOP_ANCHOR);
        for (Method method : methods) {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        forwardStop(contextOf(param), param.args);
                    } catch (Throwable failure) {
                        skip(failure);
                    }
                }
            });
        }
        return methods.size();
    }


    private static void forwardRing(Context context, Object[] args) {
        Object schedule = null;
        String text = null;
        if (args != null) for (Object arg : args) {
            if (!(arg instanceof Context) && arg != null) {
                String rendered = String.valueOf(arg);
                if (rendered.contains("mId=")) {
                    schedule = arg;
                    text = rendered;
                    break;
                }
            }
        }
        if (schedule == null) return;
        Integer id = alarmId(schedule, text);
        if (id == null) {
            android.util.Log.i("OplusBandBridge", "CLOCK_ALARM_ID_MISSING");
            return;
        }
        ringingScheduleId = id;
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        call(app, 0, id, labelOf(schedule, text));
    }

    private static void forwardStop(Context context, Object[] args) {
        Intent intent = null;
        if (args != null) for (Object arg : args) {
            if (arg instanceof Intent found) {
                intent = found;
                break;
            }
        }
        if (intent == null || !stopAction(intent.getAction()) || intent.getData() == null) return;
        long raw;
        try {
            raw = android.content.ContentUris.parseId(intent.getData());
        } catch (RuntimeException bad) {
            return;
        }
        if (raw < 0 || raw > Integer.MAX_VALUE) return;
        ringingScheduleId = -1;
        int op = intent.getBooleanExtra("IS_ALARM_DISMISSED", false) ? 1 : 2;
        call(context, op, (int) raw, "");
    }

    static boolean stopAction(String action) {
        return "STOP_ALARM".equals(action) || "com.oplus.alarmclock.STOP_ALARM".equals(action)
                || (action != null && action.endsWith(".STOP_ALARM"));
    }

    /** Prefer getAlarmId() when the clock still has it. Otherwise the schedule toString keeps mId=. Never call p() or read mId. */
    private static Integer alarmId(Object schedule, String text) {
        try {
            Method named = schedule.getClass().getMethod("getAlarmId");
            Object value = named.invoke(schedule);
            if (value instanceof Number number && number.intValue() >= 0 && number.longValue() <= Integer.MAX_VALUE) {
                return number.intValue();
            }
        } catch (NoSuchMethodException missing) {
            // Renamed builds print the id in toString instead.
        } catch (ReflectiveOperationException failure) {
            return null;
        }
        Matcher matcher = SCHEDULE_ID.matcher(text == null ? "" : text);
        if (!matcher.find()) return null;
        try {
            long raw = Long.parseLong(matcher.group(1));
            if (raw < 0 || raw > Integer.MAX_VALUE) return null;
            return (int) raw;
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    private static String labelOf(Object schedule, String text) {
        try {
            Method named = schedule.getClass().getMethod("getLabel");
            Object value = named.invoke(schedule);
            return value == null ? "" : value.toString();
        } catch (ReflectiveOperationException missing) {
            Matcher matcher = SCHEDULE_LABEL.matcher(text == null ? "" : text);
            return matcher.find() ? matcher.group(1) : "";
        }
    }

    private static void call(Context context, int op, int id, String label) {
        if (context == null) return;
        Bundle extras = new Bundle();
        extras.putInt("op", op);
        extras.putInt("id", id);
        extras.putInt("alertTimeSec", (int) (System.currentTimeMillis() / 1000L));
        extras.putString("label", label == null ? "" : label);
        if (op == 0) extras.putParcelable("reply", new android.os.Messenger(
                new android.os.Handler(android.os.Looper.getMainLooper()) {
                    @Override public void handleMessage(android.os.Message message) {
                        int bandOp = message.arg1;
                        int bandId = message.arg2;
                        if (bandOp != 1 && bandOp != 2) return;
                        if (bandId >= 0 && ringingScheduleId < 0) ringingScheduleId = bandId;
                        android.util.Log.i("OplusBandBridge", "CLOCK_ALARM_REPLY op=" + bandOp
                                + " id=" + ringingScheduleId);
                        stopRinging(context, bandOp);
                        ringingScheduleId = -1;
                    }
                }));
        try {
            context.getContentResolver().call(AUTHORITY, "operation", null, extras);
        } catch (RuntimeException denied) {
            android.util.Log.i("OplusBandBridge", "CLOCK_ALARM_CALL_FAILED "
                    + denied.getClass().getSimpleName());
        }
    }

    private static Context contextOf(XC_MethodHook.MethodHookParam param) {
        if (param.args != null) for (Object arg : param.args) {
            if (arg instanceof Context context) return context;
        }
        if (param.thisObject instanceof Context context) return context;
        return null;
    }


    private static void stopRinging(Context context, int op) {
        int id = ringingScheduleId;
        if (context == null || id < 0) {
            android.util.Log.i("OplusBandBridge", "CLOCK_ALARM_STOP_NO_ID");
            return;
        }
        Intent stop = new Intent();
        stop.setClassName(CLOCK, "com.oplus.alarmclock.alert.AlarmService");
        stop.setAction("STOP_ALARM");
        stop.setData(android.content.ContentUris.withAppendedId(
                android.net.Uri.parse("content://com.coloros.alarmclock.alarmclock/schedules"), id));
        stop.putExtra("IS_ALARM_DISMISSED", op == 1);
        try {
            context.getApplicationContext().startForegroundService(stop);
            android.util.Log.i("OplusBandBridge", "CLOCK_ALARM_STOP op=" + op + " id=" + id);
        } catch (RuntimeException failure) {
            android.util.Log.i("OplusBandBridge", "CLOCK_ALARM_STOP_FAILED "
                    + failure.getClass().getSimpleName());
        }
    }

    private static List<Method> anchored(List<String> apks, ClassLoader loader, String anchor) {
        try {
            return resolve(apks, loader, anchor);
        } catch (Throwable failure) {
            skip(failure);
            return List.of();
        }
    }

    private static void report(Context context, int ring, int stop) {
        String version = "unknown";
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        Bundle extras = new Bundle();
        extras.putString("hook", "version=" + version + " ring=" + ring + " stop=" + stop);
        try {
            context.getContentResolver().call(AUTHORITY, "status", null, extras);
        } catch (RuntimeException failure) {
            android.util.Log.i("OplusBandBridge", "CLOCK_HOOK_REPORT_FAILED "
                    + failure.getClass().getSimpleName());
        }
    }

    private static List<Method> resolve(List<String> apks, ClassLoader loader, String anchor) throws Exception {
        List<Method> methods = new ArrayList<>();
        for (String apk : apks) {
            for (DexAnchors.MethodRef ref : DexAnchors.methodsReferencing(apk, anchor)) {
                Class<?> type = loadType(ref.classDescriptor(), loader);
                Class<?>[] params = new Class<?>[ref.parameters().length];
                for (int i = 0; i < params.length; i++) params[i] = loadType(ref.parameters()[i], loader);
                Method method = type.getDeclaredMethod(ref.name(), params);
                method.setAccessible(true);
                methods.add(method);
            }
        }
        return methods;
    }

    private static List<Method> named(ClassLoader loader, String className, String name) throws ClassNotFoundException {
        List<Method> methods = new ArrayList<>();
        Class<?> type = Class.forName(className, false, loader);
        Method widest = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name)) continue;
            if (widest == null || method.getParameterCount() > widest.getParameterCount()) widest = method;
        }
        if (widest != null) {
            widest.setAccessible(true);
            methods.add(widest);
        }
        return methods;
    }

    private static Class<?> loadType(String descriptor, ClassLoader loader) throws ClassNotFoundException {
        return switch (descriptor) {
            case "I" -> int.class;
            case "J" -> long.class;
            case "Z" -> boolean.class;
            case "B" -> byte.class;
            case "S" -> short.class;
            case "C" -> char.class;
            case "F" -> float.class;
            case "D" -> double.class;
            case "V" -> void.class;
            default -> {
                String name = descriptor.replace('/', '.');
                if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length() - 1);
                yield Class.forName(name, false, loader);
            }
        };
    }

    private static void skip(Throwable failure) {
        android.util.Log.i("OplusBandBridge", "CLOCK_ALARM_HOOK_SKIPPED " + failure.getClass().getSimpleName());
    }
}
