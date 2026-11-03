// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.SparseArray;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import io.github.miam1ku.mibandoplusbridge.data.StepAggregateAuditStore.Request;
import io.github.miam1ku.mibandoplusbridge.data.StepAggregateReview;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedBridge;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Audits historical daily totals in the existing database-service process, with per-day consent. */
final class OHealthStepAggregateHook {
    private static final String PROCESS = "com.heytap.health:SportDaemonService";
    private static final String[] COLUMNS = {"recordId", "revision", "record"};
    private static OHealthStepAggregateHook installed;
    private final Context context;
    private final NativeAccess nativeAccess;
    private final Handler worker;
    private final Object scheduleLock = new Object();
    private long nextWakeMs = Long.MAX_VALUE;
    private final Runnable work = this::runScheduled;
    private final Map<String, Long> lastAudit = new HashMap<>();
    private final Map<String, String> lastOutcome = new HashMap<>();
    private final ThreadLocal<Mutation> mutation = new ThreadLocal<>();

    static synchronized void install(Context context, ClassLoader loader) throws Exception {
        if (installed != null || !PROCESS.equals(Application.getProcessName())) return;
        OHealthStepAggregateHook hook = new OHealthStepAggregateHook(context, loader);
        hook.observe();
        installed = hook;
        hook.log("OHEALTH_STEP_AUDIT_READY process=" + PROCESS);
        hook.request();
    }

    private OHealthStepAggregateHook(Context context, ClassLoader loader) throws Exception {
        this.context = attachedContext(context, context.getApplicationContext());
        nativeAccess = new NativeAccess(loader);
        HandlerThread thread = HookRuntime.thread("OplusBandStepAudit");
        worker = new Handler(thread.getLooper());
        XposedBridge.hookMethod(nativeAccess.merge, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Mutation active = mutation.get();
                if (active == null || param.thisObject != active.process || param.args[0] != active.source
                        || Boolean.TRUE.equals(param.args[1]) || param.args[2] == null) return;
                Object old = param.args[2];
                if (!active.matches(nativeAccess, old)) throw new IllegalStateException("STEP_AUDIT_CHANGED");
                // The native merge recomputes step-goal flags from the corrected current row.
                // Its ordinary max merge continues to preserve every other current metric.
                nativeAccess.setSteps.invoke(old, active.steps);
                active.applied = true;
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Mutation active = mutation.get();
                if (active != null && param.thisObject == active.process && param.args[0] == active.source
                        && active.applied && !param.hasThrowable()) param.setResult(true);
            }
        });
        XposedBridge.hookMethod(nativeAccess.factory.getMethod("getStat", int.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args[0].equals(1002) && !param.hasThrowable() && param.getResult() != null) request();
            }
        });
    }

    private void observe() {
        HookRuntime.observe(context.getContentResolver(), HealthQueueProvider.STEP_AUDITS_URI, false,
                new ContentObserver(worker) {
                    @Override public void onChange(boolean selfChange) { request(); }
                });
    }

    private void request() { schedule(500); }

    private void schedule(long delayMs) {
        synchronized (scheduleLock) {
            long next = android.os.SystemClock.elapsedRealtime() + delayMs;
            if (next >= nextWakeMs) return;
            nextWakeMs = next;
            worker.removeCallbacks(work);
            worker.postDelayed(work, delayMs);
        }
    }

    private void runScheduled() {
        synchronized (scheduleLock) { nextWakeMs = Long.MAX_VALUE; }
        try { auditPending(); }
        catch (SecurityException inactiveAccount) { }
        catch (Exception failure) {
            log("OHEALTH_STEP_AUDIT_PAUSED " + failure.getClass().getSimpleName());
            schedule(30_000);
        } finally {
            // Re-audit unchanged requests too: native/cloud rows and provider readiness can change.
            schedule(300_000);
        }
    }

    private void auditPending() throws Exception {
        Bundle pending = context.getContentResolver().call(HealthQueueProvider.URI, "stepAudits", null, null);
        if (pending == null) return;
        JSONArray entries = new JSONArray(pending.getString("entries", "[]"));
        if (entries.length() == 0) return;
        Object process = nativeAccess.existingProcess();
        if (process == null) { schedule(5_000); return; }
        Set<String> currentKeys = new java.util.HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            currentKeys.add(Request.fromJson(entries.getJSONObject(i).getJSONObject("request")).key());
        }
        lastAudit.keySet().retainAll(currentKeys);
        lastOutcome.keySet().retainAll(currentKeys);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            Request request = Request.fromJson(entry.getJSONObject("request"));
            boolean approved = entry.optBoolean("approved", false);
            long now = android.os.SystemClock.elapsedRealtime();
            long elapsed = now - lastAudit.getOrDefault(request.key(), -60_000L);
            if (!approved && elapsed < 30_000) { schedule(30_000 - elapsed); continue; }
            lastAudit.put(request.key(), now);
            try { audit(process, request); }
            catch (Exception failure) {
                log("OHEALTH_STEP_AUDIT_PAUSED date=" + request.date() + " " + failure.getClass().getSimpleName());
                schedule(30_000);
            }
        }
    }

    private void audit(Object process, Request request) throws Exception {
        Object db = nativeAccess.db.get(process);
        boolean excessive;
        synchronized (process) {
            excessive = rows(db, request).stream().anyMatch(row -> aggregate(row)
                    && Long.parseLong(row.get("total_steps")) > request.daySteps());
        }
        if (!excessive) {
            Bundle clear = new Bundle();
            clear.putString("request", request.toJson().toString());
            context.getContentResolver().call(HealthQueueProvider.URI, "reportStepAudit", null, clear);
            outcome(request, "CONSISTENT");
            return;
        }
        List<StepAggregateReview.SourceMinute> sources = sourceMinutes(request);
        StepAggregateReview review;
        synchronized (process) { review = assess(db, request, sources); }
        Bundle report = new Bundle();
        report.putString("request", request.toJson().toString());
        if (review != null) report.putString("review", review.toJson().toString());
        Bundle permission = context.getContentResolver().call(HealthQueueProvider.URI, "reportStepAudit", null, report);
        outcome(request, review == null ? "EVIDENCE_PENDING" : "REVIEW_" + review.fingerprint().substring(0, 12));
        if (review == null || permission == null || !permission.getBoolean("approved", false)) return;
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<List<Map<String, String>>> beforeCommit = new AtomicReference<>();
        synchronized (process) {
            nativeAccess.transaction.invoke(db, (Runnable) () -> {
                try {
                    StepAggregateReview fresh = assess(db, request, sourceMinutes(request));
                    if (!review.equals(fresh)) throw new IllegalStateException("STEP_AUDIT_CHANGED");
                    Bundle current = context.getContentResolver().call(HealthQueueProvider.URI,
                            "reportStepAudit", null, report);
                    if (current == null || !current.getBoolean("approved", false)) {
                        throw new IllegalStateException("STEP_AUDIT_APPROVAL_CHANGED");
                    }
                    beforeCommit.set(rows(db, request));
                    repair(process, db, request, fresh);
                } catch (Exception error) {
                    failure.set(error);
                    throw new IllegalStateException("STEP_AUDIT_ROLLBACK", error);
                }
            });
        }
        if (failure.get() != null) throw failure.get();
        // A separate read after commit confirms the durable rows before consuming consent.
        List<Map<String, String>> readback = rows(db, request);
        if (beforeCommit.get() == null) throw new IllegalStateException("STEP_AUDIT_READBACK");
        verifyPreserved(beforeCommit.get(), readback, review.expectedSteps());
        Bundle complete = new Bundle(report);
        complete.putString("review", review.toJson().toString());
        Bundle completed = context.getContentResolver().call(HealthQueueProvider.URI, "completeStepAudit", null, complete);
        if (completed == null || !"STEP_AUDIT_COMPLETED".equals(completed.getString("status"))) {
            throw new IllegalStateException("STEP_AUDIT_COMPLETION_PENDING");
        }
        nativeAccess.refresh(context, request.account());
        log("OHEALTH_STEP_AGGREGATE_REPAIRED date=" + request.date() + " steps=" + review.expectedSteps());
    }

    private void repair(Object process, Object db, Request request, StepAggregateReview review) throws Exception {
        List<Map<String, String>> before = rows(db, request);
        Object dao = nativeAccess.dao.get(process);
        @SuppressWarnings("unchecked") List<Object> originals = (List<Object>) nativeAccess.queryDays.invoke(
                dao, request.account(), request.date(), request.date());
        for (Object old : originals) {
            int mode = (Integer) nativeAccess.mode.invoke(old);
            if ((mode != -2 && mode != -4) || !empty(nativeAccess.device.invoke(old))) continue;
            int previous = (Integer) nativeAccess.steps.invoke(old);
            if (previous <= review.expectedSteps()) continue;
            Object source = copyEntity(old);
            nativeAccess.setSteps.invoke(source, review.expectedSteps());
            nativeAccess.setSync.invoke(source, 0);
            Mutation active = new Mutation(process, source, request.account(), request.date(), mode,
                    nativeAccess.id.invoke(old), previous, review.expectedSteps());
            if (mutation.get() != null) throw new IllegalStateException("STEP_AUDIT_REENTRANT");
            mutation.set(active);
            try {
                // Ordinary merge plus a thread-local steps-only correction keeps concurrent metrics.
                nativeAccess.stat.invoke(process, List.of(source), false, false);
                if (!active.applied) throw new IllegalStateException("STEP_AUDIT_TOKEN_UNUSED");
            } finally { mutation.remove(); }
        }
        verifyPreserved(before, rows(db, request), review.expectedSteps());
    }

    static void verifyPreserved(List<Map<String, String>> before, List<Map<String, String>> after, int steps) {
        if (before.size() != after.size()) throw new IllegalStateException("STEP_AUDIT_ROW_SET_CHANGED");
        Set<String> derived = Set.of("total_steps", "sync_status", "updated", "modified_time", "update_timestamp",
                "steps_goal_complete", "day_goal_complete");
        for (int i = 0; i < before.size(); i++) {
            Map<String, String> old = before.get(i), got = after.get(i);
            boolean corrected = aggregate(old) && Long.parseLong(old.get("total_steps")) > steps;
            if (!old.keySet().equals(got.keySet())) throw new IllegalStateException("STEP_AUDIT_SCHEMA_CHANGED");
            for (String column : old.keySet()) {
                if (corrected && derived.contains(column)) continue;
                if (!java.util.Objects.equals(old.get(column), got.get(column))) {
                    throw new IllegalStateException("STEP_AUDIT_PRESERVED_FIELD_" + column);
                }
            }
            if (corrected && !Integer.toString(steps).equals(got.get("total_steps"))) {
                throw new IllegalStateException("STEP_AUDIT_READBACK");
            }
        }
    }

    private static boolean aggregate(Map<String, String> row) {
        String mode = row.get("sport_mode");
        return empty(row.get("device_unique_id")) && ("-2".equals(mode) || "-4".equals(mode));
    }

    private StepAggregateReview assess(Object db, Request request, List<StepAggregateReview.SourceMinute> sources)
            throws Exception {
        List<StepAggregateReview.SavedMinute> saved = new ArrayList<>();
        try (Cursor cursor = nativeAccess.query(db, "SELECT ssoid,device_unique_id,start_time,end_time,steps,calories,"
                + "distance,sport_mode,display,timezone FROM DBSportDataDetail WHERE ssoid=? AND start_time<? "
                + "AND end_time>? ORDER BY start_time LIMIT 1441", request.account(),
                request.startMs() + 86_400_000L, request.startMs())) {
            while (cursor.moveToNext()) saved.add(new StepAggregateReview.SavedMinute(cursor.getString(0),
                    cursor.getString(1), cursor.getLong(2), cursor.getLong(3), cursor.getLong(4), cursor.getLong(5),
                    cursor.getLong(6), cursor.getInt(7), cursor.getInt(8), cursor.getString(9)));
        }
        List<StepAggregateReview.Daily> days = new ArrayList<>();
        for (Map<String, String> row : rows(db, request)) days.add(new StepAggregateReview.Daily(row.get("ssoid"),
                row.get("device_unique_id"), Integer.parseInt(row.get("date")), Integer.parseInt(row.get("sport_mode")),
                Long.parseLong(row.get("total_steps")), Long.parseLong(row.get("total_calories")),
                Long.parseLong(row.get("total_distance"))));
        return StepAggregateReview.assess(request.account(), request.queuedDevice(), request.nativeDevice(),
                request.date(), request.startMs(), request.timezone(), request.daySteps(), request.minuteMode(),
                System.currentTimeMillis(), sources, saved, days);
    }

    private List<Map<String, String>> rows(Object db, Request request) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Cursor cursor = nativeAccess.query(db, "SELECT * FROM DBSportDataStat WHERE ssoid=? AND date=? ORDER BY _id",
                request.account(), request.date())) {
            while (cursor.moveToNext()) {
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < cursor.getColumnCount(); i++) row.put(cursor.getColumnName(i), cursor.getString(i));
                rows.add(row);
            }
        }
        return rows;
    }

    private List<StepAggregateReview.SourceMinute> sourceMinutes(Request request) throws Exception {
        List<StepAggregateReview.SourceMinute> sources = new ArrayList<>();
        String after = null;
        do {
            android.net.Uri uri = after == null ? HealthQueueProvider.RECORDS_URI
                    : HealthQueueProvider.RECORDS_URI.buildUpon().appendQueryParameter("after", after).build();
            int count = 0;
            try (Cursor cursor = context.getContentResolver().query(uri, COLUMNS,
                    "account=? AND deviceId=? AND kind=? AND startMs<? AND endMs>?",
                    new String[]{request.account(), request.queuedDevice(), "steps_interval",
                            Long.toString(request.startMs() + 86_400_000L), Long.toString(request.startMs())}, null)) {
                if (cursor == null) throw new IllegalStateException("STEP_AUDIT_SOURCE_UNAVAILABLE");
                while (cursor.moveToNext()) {
                    HealthRecord record = HealthRecord.fromJson(new JSONObject(cursor.getString(2)));
                    if (record.value == null || record.value.doubleValue() != record.value.longValue()) {
                        throw new IllegalStateException("STEP_AUDIT_SOURCE_VALUE");
                    }
                    sources.add(new StepAggregateReview.SourceMinute(record.deviceId, record.startMs, record.endMs,
                            record.value.longValue(), record.calories == null ? -1 : record.calories,
                            record.distance == null ? -1 : record.distance, record.timezone));
                    after = cursor.getString(0);
                    if (++count > 200 || sources.size() > 1440) throw new IllegalStateException("STEP_AUDIT_SOURCE_LIMIT");
                }
            }
            if (count < 200) break;
        } while (true);
        return sources;
    }

    /** Application.attach supplies a usable base before getApplicationContext becomes available. */
    static <T> T attachedContext(T attached, T application) {
        return java.util.Objects.requireNonNull(application == null ? attached : application);
    }

    static Object copyEntity(Object old) throws Exception {
        Object result = old.getClass().getConstructor().newInstance();
        for (Class<?> type = old.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> valueType = field.getType();
                if (!valueType.isPrimitive() && valueType != String.class && valueType != Long.class
                        && valueType != Integer.class && valueType != Boolean.class) {
                    throw new IllegalStateException("STEP_AUDIT_ENTITY_CONTRACT");
                }
                field.setAccessible(true);
                field.set(result, field.get(old));
            }
        }
        return result;
    }

    private void outcome(Request request, String result) {
        if (!result.equals(lastOutcome.put(request.key(), result))) {
            log("OHEALTH_STEP_AUDIT_CHECKED date=" + request.date() + " result=" + result);
        }
    }

    private static boolean empty(Object value) { return value == null || "".equals(value); }
    private void log(String message) { HookRuntime.log(message); OHealthDeviceHook.traceLine(context, message); }

    static final class Mutation {
        final Object process, source, id;
        final String account;
        final int date, mode, previous, steps;
        boolean applied;
        Mutation(Object process, Object source, String account, int date, int mode, Object id, int previous, int steps) {
            this.process = process; this.source = source; this.account = account; this.date = date;
            this.mode = mode; this.id = id; this.previous = previous; this.steps = steps;
        }
        boolean matches(NativeAccess access, Object old) throws Exception {
            return matchesValues((String) access.account.invoke(old), (String) access.device.invoke(old),
                    (Integer) access.date.invoke(old), (Integer) access.mode.invoke(old),
                    access.id.invoke(old), (Integer) access.steps.invoke(old));
        }
        boolean matchesValues(String account, String device, int date, int mode, Object id, int steps) {
            return !applied && this.account.equals(account) && empty(device) && this.date == date
                    && this.mode == mode && this.previous == steps && this.id.equals(id);
        }
    }

    interface StatFactory {
        Object create() throws Exception;
        Object database(Object stat) throws Exception;
    }

    /** Initialize the official cached stat only after its existing shared database is open. */
    static Object readyStat(Object database, boolean open, Object cached, StatFactory factory) throws Exception {
        if (database == null || !open) return null;
        Object stat = cached == null ? factory.create() : cached;
        if (stat == null || factory.database(stat) != database) {
            throw new IllegalStateException("STEP_AUDIT_DATABASE_CHANGED");
        }
        return stat;
    }

    private static final class NativeAccess {
        final Class<?> factory;
        final Field map, db, dao, sharedDb, common;
        final Method merge, stat, queryDays, sql, transaction, createStat, isOpen, applicationContext;
        final Method account, device, date, mode, steps, id, setSteps, setSync;
        final ClassLoader loader;
        NativeAccess(ClassLoader loader) throws Exception {
            this.loader = loader;
            factory = Class.forName("com.heytap.databaseengineservice.store.SportHealthStatFactory", false, loader);
            Class<?> process = Class.forName("com.heytap.databaseengineservice.store.stat.SportDataStatProcess", false, loader);
            Class<?> entity = Class.forName("com.heytap.databaseengineservice.db.table.DBSportDataStat", false, loader);
            Class<?> daoType = Class.forName("com.heytap.databaseengineservice.db.dao.SportDataStatDao", false, loader);
            Class<?> room = Class.forName("androidx.room.RoomDatabase", false, loader);
            map = factory.getField("map"); db = process.getField("db"); dao = process.getField("sportDataStatDao");
            sharedDb = Class.forName("com.heytap.databaseengineservice.db.AppDatabase", false, loader).getField("INSTANCE");
            common = Class.forName("com.heytap.databaseengineservice.depend.BusinessDelegate", false, loader).getField("common");
            applicationContext = Class.forName("com.heytap.databaseengineservice.depend.ICommon", false, loader)
                    .getMethod("getApplicationContext");
            createStat = factory.getMethod("getStat", int.class);
            isOpen = room.getMethod("isOpen");
            merge = process.getMethod("processOldStat", entity, boolean.class, entity);
            stat = process.getMethod("stat", List.class, boolean.class, boolean.class);
            queryDays = daoType.getMethod("query", String.class, int.class, int.class);
            sql = room.getMethod("query", String.class, Object[].class);
            transaction = room.getMethod("runInTransaction", Runnable.class);
            account = entity.getMethod("getSsoid"); device = entity.getMethod("getDeviceUniqueId");
            date = entity.getMethod("getDate"); mode = entity.getMethod("getSportMode");
            steps = entity.getMethod("getTotalSteps"); id = entity.getMethod("getSportStatId");
            setSteps = entity.getMethod("setTotalSteps", int.class); setSync = entity.getMethod("setSyncStatus", int.class);
        }
        Object existingProcess() throws Exception {
            synchronized (factory) {
                Object ready = sharedDb.get(null);
                Object cached = ((SparseArray<?>) map.get(null)).get(1002);
                Object delegate = common.get(null);
                if (cached == null && (delegate == null || applicationContext.invoke(delegate) == null)) return null;
                return readyStat(ready, ready != null && Boolean.TRUE.equals(isOpen.invoke(ready)), cached,
                        new StatFactory() {
                            @Override public Object create() throws Exception { return createStat.invoke(null, 1002); }
                            @Override public Object database(Object stat) throws Exception { return db.get(stat); }
                        });
            }
        }
        Cursor query(Object db, String sql, Object... arguments) throws Exception {
            return (Cursor) this.sql.invoke(db, sql, arguments);
        }
        void refresh(Context context, String account) {
            try {
                Class.forName("com.heytap.databaseengineservice.broadcast.BroadcastUtil", false, loader)
                        .getMethod("sendDataRefreshBroadcast", Context.class, int.class, String.class)
                        .invoke(null, context, 1, account);
            } catch (Exception unavailable) { HookRuntime.log("OHEALTH_STEP_AUDIT_REFRESH_PENDING"); }
        }
    }
}
