// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.WeakHashMap;
import org.json.JSONObject;

/** Selected-device sleep is written into OHealth. This hook only restores a projection left by an older build. */
public final class OHealthSleepHook {
    private static final String HOST = "com.heytap.health";
    private static final String CARD = "com.heytap.health.main.card.SleepCard";
    private static final String PAGE = "com.heytap.health.sleep.day.view.SleepDayViewPage2";
    private static final String DAY_CARD = "com.heytap.health.sleep.day.card.SleepDayViewPageCard";
    private static final String LOAD = "com.heytap.health.sleep.day.util.SleepDataLoadUtils";
    private static final String HISTORY = "com.heytap.health.sleep.SleepHistoryActivity";
    private static final String[] COLUMNS = {"recordId", "revision", "record"};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, Surface> HOMES = new WeakHashMap<>();
    private static final Map<Object, DaySurface> DAYS = new WeakHashMap<>();
    private static final Map<Object, Navigation> NAVIGATION = new WeakHashMap<>();
    private static final Map<Object, CharSequence> TOOLBARS = new WeakHashMap<>();
    private static final java.util.Set<Object> BINDING = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static Context context;
    private static ClassLoader loader;
    private static Handler worker;
    private static Object dateUtils;
    private static Object manager;
    private static Object allRole;
    private static Object accountCompanion;
    private static volatile String observedAccount = "";
    private static volatile Cache cache = Cache.empty();
    private static boolean installed;
    private static boolean refreshingNavigation;
    private static final Runnable RELOAD = OHealthSleepHook::load;

    private OHealthSleepHook() {}

    public static synchronized void install(Context supplied, ClassLoader hostLoader) throws Exception {
        if (installed) return;
        if (!HOST.equals(supplied.getPackageName())) return;
        Context app = supplied.getApplicationContext();
        context = app == null ? supplied : app;
        loader = hostLoader;
        dateUtils = singleton("com.heytap.health.healthbase.util.HealthDateUtils", "INSTANCE");
        manager = singleton("com.heytap.health.devicemanager.client.DMHeytap", "managerApi");
        allRole = singleton("com.heytap.health.devicemanager.client.role.DeviceBasegetRole$All", "INSTANCE");
        accountCompanion = singleton("com.heytap.device.data.storage.DataRepositoryHelper", "Companion");
        HandlerThread thread = new HandlerThread("OplusBandSleepRead");
        thread.start();
        worker = new Handler(thread.getLooper());
        hookHome();
        hookDay();
        hookNavigation();
        hookToolbar();
        XposedBridge.hookAllMethods(accountCompanion.getClass(), "getSsoId", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                String account = p.getResult() instanceof String ? (String) p.getResult() : "";
                if (!account.equals(observedAccount)) {
                    observedAccount = account;
                    cache = Cache.empty();
                    MAIN.post(OHealthSleepHook::renderAll);
                    requestLoad();
                }
            }
        });
        XC_MethodHook selection = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                MAIN.post(OHealthSleepHook::renderAll);
            }
        };
        for (Method method : manager.getClass().getMethods()) {
            if (method.getName().equals("setThirdpartySelectMac") || method.getName().equals("setCurrActiveMac")) {
                XposedBridge.hookMethod(method, selection);
            }
        }
        ContentObserver observer = new ContentObserver(worker) {
            @Override public void onChange(boolean selfChange) {
                cache = Cache.empty();
                MAIN.post(OHealthSleepHook::renderAll);
                requestLoad();
            }
        };
        context.getContentResolver().registerContentObserver(HealthQueueProvider.RECORDS_URI, true, observer);
        context.getContentResolver().registerContentObserver(DeviceCardProvider.URI, false, observer);
        installed = true;
        requestLoad();
    }

    private static Object singleton(String name, String field) throws Exception {
        return Class.forName(name, false, loader).getField(field).get(null);
    }

    private static void requestLoad() {
        if (worker == null) return;
        worker.removeCallbacks(RELOAD);
        worker.post(RELOAD);
    }

    private static String currentAccount() {
        Object value = XposedHelpers.callMethod(accountCompanion, "getSsoId");
        return value instanceof String ? (String) value : "";
    }

    private static boolean selected() {
        return OHealthDeviceHook.matchesActiveDevice(manager, allRole);
    }

    private static Cache available() {
        Cache value = cache;
        try {
            Bundle band = OHealthDeviceHook.registeredSnapshot();
            return band != null && value.account.equals(currentAccount())
                    && value.device.equals(band.getString("deviceId", "")) ? value : Cache.empty();
        } catch (Throwable unavailable) { return Cache.empty(); }
    }

    private static void load() {
        try {
            String account = currentAccount();
            Bundle band = context.getContentResolver().call(DeviceCardProvider.URI, "bandDisplay", null, null);
            if (account.isBlank() || band == null || !band.getBoolean("registered")) {
                cache = Cache.empty();
            } else {
                String device = band.getString("deviceId", "");
                if (device.isBlank()) throw new IllegalStateException("DEVICE_ID_REQUIRED");
                List<HealthRecord> records = new ArrayList<>();
                for (String kind : new String[]{"sleep_interval", "sleep_stage"}) {
                    String after = null;
                    for (;;) {
                        if (!account.equals(currentAccount())) throw new SecurityException("ACCOUNT_CHANGED");
                        Uri uri = after == null ? HealthQueueProvider.RECORDS_URI
                                : HealthQueueProvider.RECORDS_URI.buildUpon().appendQueryParameter("after", after).build();
                        int count = 0;
                        try (Cursor rows = context.getContentResolver().query(uri, COLUMNS,
                                "account=? AND deviceId=? AND kind=? AND startMs<? AND endMs>?",
                                new String[]{account, device, kind, Long.toString(Long.MAX_VALUE), "0"}, null)) {
                            if (rows == null) throw new IllegalStateException("HISTORY_UNAVAILABLE");
                            while (rows.moveToNext()) {
                                String id = rows.getString(0);
                                if (after != null && id.compareTo(after) <= 0) throw new IllegalStateException("INVALID_HISTORY_PAGE");
                                HealthRecord record = HealthRecord.fromJson(new JSONObject(rows.getString(2)));
                                if (!id.equals(record.recordId) || rows.getInt(1) != record.revision
                                        || !device.equals(record.deviceId) || !kind.equals(record.kind)) {
                                    throw new IllegalStateException("INVALID_HISTORY_RECORD");
                                }
                                records.add(record);
                                after = id;
                                count++;
                            }
                        }
                        if (count < 200) break;
                    }
                }
                if (!account.equals(currentAccount())) throw new SecurityException("ACCOUNT_CHANGED");
                records.sort(Comparator.comparingLong((HealthRecord r) -> r.startMs).thenComparingLong(r -> r.endMs));
                TreeSet<Long> dates = new TreeSet<>();
                for (HealthRecord record : records) {
                    long cursor = record.startMs;
                    while (cursor < record.endMs) {
                        long end = sleepEnd(cursor);
                        if (end <= cursor) throw new IllegalStateException("INVALID_SLEEP_BOUNDARY");
                        dates.add(dayStart(end));
                        cursor = end;
                    }
                }
                cache = new Cache(account, device, List.copyOf(records), List.copyOf(dates), true);
            }
        } catch (Throwable unavailable) {
            cache = Cache.empty();
        }
        MAIN.post(OHealthSleepHook::renderAll);
    }

    private static long sleepStart(long time) { return ((Number) XposedHelpers.callMethod(dateUtils, "getSleepStartTime", time)).longValue(); }
    private static long sleepEnd(long time) { return ((Number) XposedHelpers.callMethod(dateUtils, "getSleepEndTime", time)).longValue(); }
    private static long dayStart(long time) { return ((Number) XposedHelpers.callMethod(dateUtils, "getCurDayMinTime", time)).longValue(); }

    private static void hookHome() throws Exception {
        Class<?> card = Class.forName(CARD, false, loader);
        XposedBridge.hookAllMethods(card, "onCommonBindViewHolder", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                restoreHome(p.thisObject);
                BINDING.add(p.thisObject);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                BINDING.remove(p.thisObject);
                try {
                    Object common = field(p.thisObject, "healthCommonCardView");
                    if (common instanceof View) {
                        HOMES.put(p.thisObject, new Surface((View) common));
                        renderHome(p.thisObject);
                    }
                } catch (Throwable ignored) { }
            }
        });
        XposedBridge.hookAllMethods(card, "refreshViewIfNeed", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!BINDING.contains(p.thisObject)) restoreHome(p.thisObject);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (BINDING.contains(p.thisObject)) return;
                Object common = field(p.thisObject, "healthCommonCardView");
                if (common instanceof View) {
                    HOMES.put(p.thisObject, new Surface((View) common));
                    renderHome(p.thisObject);
                }
            }
        });
    }

    private static void restoreHome(Object card) {
        Surface old = HOMES.get(card);
        if (old != null) old.restore();
    }

    private static void renderHome(Object card) {
        Surface state = HOMES.get(card);
        if (state == null) return;
        state.restore();
    }

    private static void hookDay() throws Exception {
        Class<?> holder = Class.forName(PAGE + "$ChartPageAdapter$ViewHolder", false, loader);
        XposedBridge.hookAllMethods(holder, "init", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                DaySurface old = DAYS.get(p.thisObject);
                if (old != null) old.surface.restore();
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                DaySurface old = DAYS.get(p.thisObject);
                if (old != null) {
                    View root = (View) field(p.thisObject, "contentView");
                    DAYS.put(p.thisObject, new DaySurface(new Surface(root), old.date));
                    renderDay(p.thisObject);
                }
            }
        });
        XposedBridge.hookAllMethods(Class.forName(PAGE + "$ChartPageAdapter", false, loader), "instantiateItem", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!(p.getResult() instanceof View)) return;
                View root = (View) p.getResult();
                Object holder = root.getTag();
                Object outer = field(p.thisObject, "this$0");
                Object dates = field(outer, "dayTimeList");
                if (!(dates instanceof List)) return;
                int index = ((Number) p.args[1]).intValue();
                if (index < 0 || index >= ((List<?>) dates).size()) return;
                DaySurface old = DAYS.get(holder);
                if (old != null) old.surface.restore();
                long date = ((Number) ((List<?>) dates).get(index)).longValue();
                DAYS.put(holder, new DaySurface(new Surface(root), date));
                renderDay(holder);
            }
        });
    }

    private static void renderDay(Object holder) {
        DaySurface day = DAYS.get(holder);
        if (day == null) return;
        day.surface.restore();
    }

    private static int dp(View view, int amount) {
        return Math.round(view.getResources().getDisplayMetrics().density * amount);
    }

    private static List<HealthRecord> window(Cache data, long date) {
        long start = sleepStart(date), end = sleepEnd(date);
        List<HealthRecord> records = new ArrayList<>();
        for (HealthRecord record : data.records) if (record.startMs < end && record.endMs > start) records.add(record);
        return records;
    }

    private static void hookNavigation() throws Exception {
        XposedBridge.hookAllMethods(Class.forName(LOAD, false, loader), "getAllDataList", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!refreshingNavigation) NAVIGATION.put(p.thisObject,
                        new Navigation(((Number) p.args[0]).longValue(), ((Number) p.args[1]).longValue(), ((Number) p.args[2]).longValue()));
                Cache data = available();
                if (selected() && !data.dates.isEmpty()) {
                    p.args[0] = Math.min(((Number) p.args[0]).longValue(), data.dates.get(0));
                    p.args[1] = Math.max(((Number) p.args[1]).longValue(), data.dates.get(data.dates.size() - 1));
                }
                p.args[2] = Math.max(((Number) p.args[0]).longValue(),
                        Math.min(((Number) p.args[1]).longValue(), ((Number) p.args[2]).longValue()));
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object control = field(p.thisObject, "sleepDayControlModel");
                XposedHelpers.callMethod(control, "setBorderStartTime", p.args[0]);
                XposedHelpers.callMethod(control, "setBorderEndTime", p.args[1]);
            }
        });
        XposedBridge.hookAllMethods(Class.forName(DAY_CARD, false, loader), "refreshCardView", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length != 1 || !(p.args[0] instanceof Long)) return;
                Object fragment = field(p.thisObject, "fragment");
                Object load = XposedHelpers.callMethod(fragment, "getSleepDataLoadUtils");
                Navigation navigation = NAVIGATION.get(load);
                if (navigation != null) navigation.selectedDate = ((Number) field(p.thisObject, "lastRefreshTimestamp")).longValue();
            }
        });
        XposedBridge.hookAllMethods(Class.forName("com.heytap.health.sleep.day.viewmodel.SleepDayControlModel", false, loader),
                "changeSelectTime", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        for (Map.Entry<Object, Navigation> entry : NAVIGATION.entrySet()) {
                            if (field(entry.getKey(), "sleepDayControlModel") == p.thisObject) {
                                entry.getValue().selectedDate = ((Number) p.args[0]).longValue();
                            }
                        }
                    }
                });
    }

    private static void hookToolbar() throws Exception {
        XposedBridge.hookAllMethods(Class.forName(HISTORY, false, loader), "onResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object toolbar = field(p.thisObject, "toolbar");
                if (toolbar != null && !TOOLBARS.containsKey(toolbar)) {
                    TOOLBARS.put(toolbar, (CharSequence) XposedHelpers.callMethod(toolbar, "getSubtitle"));
                }
                renderAll();
                requestLoad();
            }
        });
    }

    private static void renderAll() {
        for (Object card : new ArrayList<>(HOMES.keySet())) try { renderHome(card); } catch (Throwable ignored) { }
        for (Object holder : new ArrayList<>(DAYS.keySet())) try { renderDay(holder); } catch (Throwable ignored) { }
        boolean band = selected();
        for (Map.Entry<Object, CharSequence> toolbar : new ArrayList<>(TOOLBARS.entrySet())) {
            try { XposedHelpers.callMethod(toolbar.getKey(), "setSubtitle",
                    band ? "手环区间未计入周/月统计" : toolbar.getValue()); } catch (Throwable ignored) { }
        }
        refreshingNavigation = true;
        try {
            for (Map.Entry<Object, Navigation> entry : new ArrayList<>(NAVIGATION.entrySet())) {
                try {
                    Navigation value = entry.getValue();
                    Object dates = XposedHelpers.callMethod(entry.getKey(), "getAllDataList", value.start, value.end, value.selectedDate);
                    XposedHelpers.callMethod(entry.getKey(), "updateChartData", dates,
                            XposedHelpers.getIntField(entry.getKey(), "currentItemIndex"));
                } catch (Throwable ignored) { }
            }
        } finally { refreshingNavigation = false; }
    }

    private static Object field(Object owner, String name) {
        if (owner == null) return null;
        try { return XposedHelpers.getObjectField(owner, name); } catch (Throwable unavailable) { return null; }
    }
    private static void hide(Object owner, String... names) {
        for (String name : names) { Object view = field(owner, name); if (view instanceof View) ((View) view).setVisibility(View.GONE); }
    }
    private static void setText(Object owner, String name, String text) {
        Object view = field(owner, name);
        if (view instanceof TextView) { ((TextView) view).setText(text); ((TextView) view).setVisibility(View.VISIBLE); }
    }

    /** Lists source records independently; union length is never described as total sleep. */
    public static String describe(List<HealthRecord> records) {
        return describe(records, false);
    }

    private static String describe(List<HealthRecord> records, boolean sourceStages) {
        if (records.isEmpty()) return "尚未同步到睡眠记录";
        StringBuilder text = new StringBuilder();
        boolean stages = sourceStages, unknown = false;
        for (HealthRecord record : records) {
            if (text.length() > 0) text.append('\n');
            if ("sleep_stage".equals(record.kind)) {
                stages = true;
                text.append(stageName(record.stage)).append(' ');
            } else {
                unknown |= record.value == null;
                text.append(record.complete ? "完整区间 " : "未完整区间 ");
            }
            text.append(format(record, record.startMs, "MM-dd HH:mm")).append("–")
                    .append(format(record, record.endMs, "MM-dd HH:mm")).append(" · ")
                    .append(duration(record.endMs - record.startMs));
            if (record.timezone != null) text.append(" (").append(record.timezone).append(')');
            if (record.value != null) text.append(" · 已知总睡眠 ").append(duration(record.value.longValue()));
        }
        if (unknown || records.stream().noneMatch(r -> "sleep_interval".equals(r.kind))) text.append("\n总睡眠时长未知");
        if (!stages) text.append(" · 手环未提供分期");
        else if (sourceStages) text.append("\n已有真实分期，见日历史");
        return text.toString();
    }

    public static long unionDuration(List<HealthRecord> records) {
        List<HealthRecord> sorted = new ArrayList<>(records);
        sorted.sort(Comparator.comparingLong(r -> r.startMs));
        long total = 0, start = -1, end = -1;
        for (HealthRecord record : sorted) {
            if (start < 0) { start = record.startMs; end = record.endMs; }
            else if (record.startMs > end) { total += end - start; start = record.startMs; end = record.endMs; }
            else end = Math.max(end, record.endMs);
        }
        return start < 0 ? 0 : total + end - start;
    }

    private static String duration(long millis) {
        long minutes = millis / 60_000;
        return minutes >= 60 ? minutes / 60 + "小时" + minutes % 60 + "分钟" : minutes + "分钟";
    }
    private static String format(HealthRecord record, long time, String pattern) {
        ZoneId zone = record.timezone == null ? ZoneId.systemDefault() : ZoneId.of(record.timezone);
        return DateTimeFormatter.ofPattern(pattern).withZone(zone).format(Instant.ofEpochMilli(time));
    }
    private static String stageName(Integer stage) {
        return stage == null ? "阶段未知" : switch (stage) {
            case 2 -> "深睡";
            case 3 -> "浅睡";
            case 4 -> "快速眼动（REM）";
            case 5 -> "清醒";
            default -> "阶段未知";
        };
    }

    private record Cache(String account, String device, List<HealthRecord> records, List<Long> dates, boolean ready) {
        private static final Cache EMPTY = new Cache("", "", List.of(), List.of(), false);
        static Cache empty() { return EMPTY; }
    }
    private record DaySurface(Surface surface, long date) {}
    private static final class Navigation {
        final long start, end;
        long selectedDate;
        Navigation(long start, long end, long selected) { this.start = start; this.end = end; selectedDate = selected; }
    }
    private static final class Surface {
        final WeakReference<View> root;
        final List<ViewState> nativeState = new ArrayList<>();
        WeakReference<View> extra;
        Surface(View view) { root = new WeakReference<>(view); capture(view); }
        void capture(View view) {
            nativeState.add(new ViewState(view));
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) capture(group.getChildAt(i));
            }
        }
        void restore() {
            View added = extra == null ? null : extra.get();
            if (added != null && added.getParent() instanceof ViewGroup) ((ViewGroup) added.getParent()).removeView(added);
            extra = null;
            for (ViewState value : nativeState) value.restore();
        }
    }
    private static final class ViewState {
        final WeakReference<View> view;
        final int visibility;
        final CharSequence text;
        final int width, height;
        final android.widget.RelativeLayout.LayoutParams relativeLayout;
        ViewState(View value) {
            view = new WeakReference<>(value);
            visibility = value.getVisibility();
            text = value instanceof TextView ? ((TextView) value).getText() : null;
            ViewGroup.LayoutParams params = value.getLayoutParams();
            width = params == null ? 0 : params.width;
            height = params == null ? 0 : params.height;
            relativeLayout = params instanceof android.widget.RelativeLayout.LayoutParams
                    ? new android.widget.RelativeLayout.LayoutParams((android.widget.RelativeLayout.LayoutParams) params) : null;
        }
        void restore() {
            View value = view.get();
            if (value == null) return;
            value.setVisibility(visibility);
            if (value instanceof TextView) ((TextView) value).setText(text);
            ViewGroup.LayoutParams params = value.getLayoutParams();
            if (relativeLayout != null) {
                value.setLayoutParams(new android.widget.RelativeLayout.LayoutParams(relativeLayout));
            } else if (params != null && (params.width != width || params.height != height)) {
                params.width = width;
                params.height = height;
                value.setLayoutParams(params);
            }
        }
    }
}
