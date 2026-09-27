// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Confirmed-account outbox import. Only exact native readback authorizes a receipt. */
public final class OHealthHealthImportHook {
    private static final String HOST = "com.heytap.health";
    private static final String[] COLUMNS = {"recordId", "revision", "record"};
    private static final long FAILURE_COOLDOWN_MS = 60_000;
    private static OHealthHealthImportHook installed;
    private final Context context;
    private final HostContract host;
    private final OHealthSleepWriter sleep;
    private final OHealthStepWriter steps;
    private final Handler worker;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicReference<String> observedAccount = new AtomicReference<>();
    private final AtomicReference<Object> observedApi = new AtomicReference<>();
    private final AtomicLong accountEpoch = new AtomicLong();
    private long retryAfter;
    private String lastFailure;
    private final Runnable work = this::runScheduled;

    private OHealthHealthImportHook(Context context, HostContract host, OHealthSleepWriter sleep,
            OHealthStepWriter steps) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        this.host = host;
        this.sleep = sleep;
        this.steps = steps;
        HandlerThread thread = new HandlerThread("OplusBandHealthImport");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public static synchronized void install(Context context, ClassLoader loader) throws Exception {
        if (installed != null) return;
        if (!HOST.equals(context.getPackageName())) return;
        String process = Application.getProcessName();
        if (!HOST.equals(process)) {
            Log.i("OplusBandBridge", "OHEALTH_IMPORT_SKIPPED process=" + process);
            return;
        }
        final HostContract contract;
        try {
            contract = new HostContract(loader);
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            Log.i("OplusBandBridge", "OHEALTH_IMPORT_CONTRACT_UNAVAILABLE");
            throw new IllegalStateException("HOST_VERSION_UNSUPPORTED_OHEALTH");
        }
        OHealthSleepWriter sleepWriter = null;
        try {
            sleepWriter = new OHealthSleepWriter(contract);
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            Log.i("OplusBandBridge", "OHEALTH_SLEEP_CONTRACT_UNAVAILABLE");
        }
        OHealthStepWriter stepWriter = null;
        try {
            stepWriter = new OHealthStepWriter(contract);
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            Log.i("OplusBandBridge", "OHEALTH_STEP_CONTRACT_UNAVAILABLE");
        }
        OHealthHealthImportHook hook = new OHealthHealthImportHook(context, contract, sleepWriter, stepWriter);
        hook.observe();
        installed = hook;
        hook.request();
    }

    private void observe() {
        context.getContentResolver().registerContentObserver(HealthQueueProvider.URI, false,
                new ContentObserver(null) {
                    @Override public void onChange(boolean selfChange) { request(); }
                });
        context.getContentResolver().registerContentObserver(HealthQueueProvider.RECORDS_URI, true,
                new ContentObserver(null) {
                    @Override public void onChange(boolean selfChange) { request(); }
                });
        XposedBridge.hookMethod(host.accountGetter, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                String account = param.hasThrowable() ? null : (String) param.getResult();
                if (!Objects.equals(observedAccount.getAndSet(account), account)) {
                    accountEpoch.incrementAndGet();
                    request();
                }
            }
        });
        XposedBridge.hookMethod(host.apiGetter, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object api = param.hasThrowable() ? null : param.getResult();
                if (observedApi.getAndSet(api) != api && api != null) request();
            }
        });
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { request(); }
        });
    }

    private void request() {
        if (scheduled.compareAndSet(false, true)) worker.post(work);
    }

    private void runScheduled() {
        long delay = retryAfter - SystemClock.elapsedRealtime();
        if (delay > 0) {
            worker.postDelayed(work, delay);
            return;
        }
        scheduled.set(false);
        boolean retrySoon = false;
        try {
            if (sleep != null) {
                try {
                    sleep.write(context);
                } catch (SecurityException paused) {
                    throw paused;
                } catch (Exception | LinkageError sleepFail) {
                    retrySoon |= transientImport(sleepFail);
                    logImportFailure(sleepFail);
                }
            }
            drain();
            if (steps != null) steps.write(context);
            lastFailure = null;
            if (retrySoon) scheduleRetry(2_000);
        } catch (Exception | LinkageError failure) {
            scheduleRetry(transientImport(failure) ? 2_000 : FAILURE_COOLDOWN_MS);
            logImportFailure(failure);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private void scheduleRetry(long wait) {
        retryAfter = SystemClock.elapsedRealtime() + wait;
        scheduled.set(true);
        worker.postDelayed(work, wait);
    }

    private static boolean transientImport(Throwable failure) {
        String reason = unwrap(failure).getMessage();
        return "SLEEP_DEVICE_NOT_READY".equals(reason)
                || "IMPORT_NOT_READY".equals(reason)
                || "SLEEP_IMPORT_NOT_READY".equals(reason)
                || "IMPORT_READ_FAILED_100015".equals(reason)
                || "IMPORT_READ_FAILED_100003".equals(reason);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private void logImportFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        String reason = cause.getMessage();
        String category = failure instanceof SecurityException ? "ACCOUNT_PAUSED" : "RETAINED";
        String detail = category + " " + cause.getClass().getSimpleName();
        if (reason != null && reason.matches("[A-Z][A-Z0-9_]{1,90}")) detail += " " + reason;
        if (!detail.equals(lastFailure)) Log.i("OplusBandBridge", "OHEALTH_IMPORT_" + detail);
        lastFailure = detail;
    }

    private void ensureHeytapContext() {
        try {
            Class<?> type = Class.forName("com.heytap.databaseengine.apiv2._HeytapHealth", false, host.loader);
            fillContext(type, null);
            Object api = host.api();
            if (api == null) return;
            java.lang.reflect.Field holderField = api.getClass().getDeclaredField("mApiHolder");
            holderField.setAccessible(true);
            Object holder = holderField.get(api);
            if (holder != null) fillContext(holder.getClass(), holder);
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_HEYTAP_CONTEXT_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    private void fillContext(Class<?> type, Object instance) throws Exception {
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (!Context.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            if (field.get(instance) == null) {
                field.set(instance, context);
                Log.i("OplusBandBridge", "OHEALTH_CONTEXT_SET " + type.getSimpleName() + "." + field.getName());
            }
        }
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != Context.class) continue;
            if (instance == null && (method.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0) continue;
            method.setAccessible(true);
            try {
                method.invoke(instance, context);
                Log.i("OplusBandBridge", "OHEALTH_CONTEXT_CALL " + type.getSimpleName() + "." + method.getName());
            } catch (Throwable ignored) { }
        }
    }

    private void drain() throws Exception {
        if (OHealthDeviceHook.registeredSnapshot() == null) {
            throw new IllegalStateException("SLEEP_DEVICE_NOT_READY");
        }
        ensureHeytapContext();
        String account = host.account();
        if (account == null || account.isBlank() || account.length() > 512) return;
        long epoch = accountEpoch.get();
        Object api = host.api();
        if (api == null) throw new IllegalStateException("IMPORT_NOT_READY");
        while (true) {
            requireAccount(account, epoch);
            List<HealthRecord> page = pending(account);
            if (page.isEmpty()) return;
            Map<Batch, List<HealthRecord>> groups = new LinkedHashMap<>();
            for (HealthRecord record : page) {
                OHealthHealthModels.Kind kind = OHealthHealthModels.Kind.of(record);
                kind.type(record); // Reject unsupported modes before any write in this page.
                ZoneId zone = record.timezone == null ? ZoneId.systemDefault() : ZoneId.of(record.timezone);
                LocalDate day = Instant.ofEpochMilli(record.startMs).atZone(zone).toLocalDate();
                groups.computeIfAbsent(new Batch(kind, day), unused -> new ArrayList<>()).add(record);
            }
            int acknowledged = 0;
            int released = 0;
            boolean retained = false;
            for (Map.Entry<Batch, List<HealthRecord>> group : groups.entrySet()) {
                requireAccount(account, epoch);
                OHealthHealthModels model = host.models.get(group.getKey().kind());
                List<HealthRecord> importable = new ArrayList<>();
                for (HealthRecord record : group.getValue()) {
                    if (!record.hostAccepts()) {
                        requireAccount(account, epoch);
                        Bundle receipt = new Bundle();
                        receipt.putString("account", account);
                        receipt.putString("recordId", record.recordId);
                        receipt.putInt("revision", record.revision);
                        Bundle result = context.getContentResolver().call(HealthQueueProvider.URI,
                                "releaseUnsupported", null, receipt);
                        if (result == null) throw new IllegalStateException("IMPORT_RECEIPT_UNAVAILABLE");
                        released += result.getInt("released", 0);
                        continue;
                    }
                    importable.add(record);
                }
                if (importable.isEmpty()) continue;
                List<HealthRecord> records = importable;
                Set<OHealthHealthModels.Point> found = host.read(api, account, model, records);
                List<HealthRecord> missing = new ArrayList<>();
                for (HealthRecord record : records) {
                    if (!found.contains(model.key(account, record))) missing.add(record);
                }
                Log.i("OplusBandBridge", "OHEALTH_IMPORT_GROUP table=" + model.kind.table
                        + " records=" + records.size() + " found=" + found.size()
                        + " missing=" + missing.size());
                boolean inserted = false;
                if (!missing.isEmpty()) {
                    List<Object> rows = new ArrayList<>();
                    for (HealthRecord record : missing) rows.add(model.create(account, record));
                    Object option = host.insertOption(model.kind.table, rows);
                    requireAccount(account, epoch);
                    host.insert(api, option);
                    inserted = true;
                    requireAccount(account, epoch);
                    found = host.read(api, account, model, records);
                }
                int before = acknowledged;
                for (HealthRecord record : records) {
                    OHealthHealthModels.Point key = model.key(account, record);
                    if (!delivered(found, key, inserted)) {
                        retained = true;
                        continue;
                    }
                    requireAccount(account, epoch);
                    Bundle receipt = new Bundle();
                    receipt.putString("account", account);
                    receipt.putString("recordId", record.recordId);
                    receipt.putInt("revision", record.revision);
                    Bundle result = context.getContentResolver().call(HealthQueueProvider.URI, "ack", null, receipt);
                    if (result == null) throw new IllegalStateException("IMPORT_RECEIPT_UNAVAILABLE");
                    acknowledged += result.getInt("acknowledged", 0);
                }
                if (acknowledged == before && !missing.isEmpty()) retained = true;
            }
            Log.i("OplusBandBridge", "OHEALTH_IMPORT_PAGE acknowledged=" + acknowledged
                    + " released=" + released + " retained=" + retained);
            // Confirmed or host-rejected rows are gone. Continue while the page made progress.
            if (acknowledged == 0 && released == 0) throw new IllegalStateException("IMPORT_READBACK_INCOMPLETE");
        }
    }
    /** Host keeps one visible row per account, timestamp and type. An insert merged into that minute is delivered. */
    private static boolean delivered(Set<OHealthHealthModels.Point> found, OHealthHealthModels.Point key,
            boolean inserted) {
        if (found.contains(key)) return true;
        if (!inserted) return false;
        for (OHealthHealthModels.Point point : found) {
            if (point.account().equals(key.account()) && point.timestamp() == key.timestamp()
                    && point.type() == key.type()) return true;
        }
        return false;
    }


    private List<HealthRecord> pending(String account) throws Exception {
        List<HealthRecord> page = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(HealthQueueProvider.URI, COLUMNS,
                "account=?", new String[]{account}, null)) {
            if (cursor == null) throw new IllegalStateException("IMPORT_QUEUE_UNAVAILABLE");
            while (cursor.moveToNext()) {
                if (page.size() >= 200) throw new IllegalStateException("IMPORT_QUEUE_CONTRACT");
                HealthRecord record = HealthRecord.fromJson(new JSONObject(cursor.getString(2)));
                if (!record.recordId.equals(cursor.getString(0)) || record.revision != cursor.getInt(1)) {
                    throw new IllegalStateException("IMPORT_QUEUE_REVISION_MISMATCH");
                }
                page.add(record);
            }
        }
        return page;
    }

    private void requireAccount(String expected, long epoch) throws Exception {
        if (!expected.equals(host.account()) || accountEpoch.get() != epoch) {
            throw new SecurityException("IMPORT_ACCOUNT_CHANGED");
        }
    }

    private record Batch(OHealthHealthModels.Kind kind, LocalDate date) { }

    /** Resolve the entire supported host surface before subscribing or writing anything. */
    static final class HostContract {
        final Object companion;
        final Method accountGetter, apiGetter, read, insert, subscribe, observerResult, observerDispose;
        final Method readAccount, readStart, readEnd, readDevice, readTable, readType;
        final Method insertTable, insertDatas, errorCode, payload, dispose;
        final Constructor<?> readConstructor, insertConstructor, observerConstructor;
        final Class<?> observerClass, beanClass;
        final ClassLoader loader;
        final EnumMap<OHealthHealthModels.Kind, OHealthHealthModels> models =
                new EnumMap<>(OHealthHealthModels.Kind.class);

        HostContract(ClassLoader loader) throws ReflectiveOperationException {
            this.loader = loader;
            Class<?> owner = Class.forName("com.heytap.device.data.storage.DataRepositoryHelper", false, loader);
            companion = owner.getField("Companion").get(null);
            Class<?> companionClass = Class.forName(owner.getName() + "$Companion", false, loader);
            accountGetter = companionClass.getMethod("getSsoId");
            if (accountGetter.getReturnType() != String.class) throw new NoSuchMethodException("IMPORT_ACCOUNT_CONTRACT");
            apiGetter = companionClass.getMethod("getDbApi");
            Class<?> apiClass = Class.forName("com.heytap.databaseengine.api.ISportHealthDataAPI", false, loader);
            Class<?> readClass = Class.forName("com.heytap.databaseengine.option.DataReadOption", false, loader);
            Class<?> insertClass = Class.forName("com.heytap.databaseengine.option.DataInsertOption", false, loader);
            readConstructor = readClass.getConstructor();
            insertConstructor = insertClass.getConstructor();
            readAccount = readClass.getMethod("setSsoid", String.class);
            readStart = readClass.getMethod("setStartTime", long.class);
            readEnd = readClass.getMethod("setEndTime", long.class);
            readDevice = readClass.getMethod("setDeviceUniqueId", String.class);
            readTable = readClass.getMethod("setDataTable", int.class);
            readType = readClass.getMethod("setReadHealthDataType", int.class);
            insertTable = insertClass.getMethod("setDataTable", int.class);
            insertDatas = insertClass.getMethod("setDatas", List.class);
            read = apiClass.getMethod("readSportHealthData", readClass);
            insert = apiClass.getMethod("insertSportHealthData", insertClass);
            observerClass = Class.forName("io.reactivex.rxjava3.core.Observer", false, loader);
            Class<?> observableClass = Class.forName("io.reactivex.rxjava3.core.Observable", false, loader);
            if (!observableClass.isAssignableFrom(read.getReturnType())
                    || !observableClass.isAssignableFrom(insert.getReturnType())) {
                throw new NoSuchMethodException("IMPORT_OBSERVABLE_CONTRACT");
            }
            subscribe = observableClass.getMethod("subscribe", observerClass);
            dispose = Class.forName("io.reactivex.rxjava3.disposables.Disposable", false, loader).getMethod("dispose");
            Class<?> syncClass = Class.forName("com.heytap.device.data.storage.SyncObserver", false, loader);
            if (!observerClass.isAssignableFrom(syncClass)) throw new NoSuchMethodException("IMPORT_OBSERVER_CONTRACT");
            observerConstructor = syncClass.getConstructor();
            observerResult = syncClass.getMethod("result");
            observerDispose = syncClass.getMethod("dispose");
            if (observerResult.getReturnType() != boolean.class) throw new NoSuchMethodException("IMPORT_RESULT_CONTRACT");
            beanClass = Class.forName("com.heytap.databaseengine.model.CommonBackBean", false, loader);
            errorCode = beanClass.getMethod("getErrorCode");
            payload = beanClass.getMethod("getObj");
            if (errorCode.getReturnType() != int.class) throw new NoSuchMethodException("IMPORT_BEAN_CONTRACT");
            for (OHealthHealthModels.Kind kind : OHealthHealthModels.Kind.values()) {
                models.put(kind, new OHealthHealthModels(kind, loader));
            }
        }

        String account() throws ReflectiveOperationException { return (String) accountGetter.invoke(companion); }
        Object api() throws ReflectiveOperationException { return apiGetter.invoke(companion); }

        Object insertOption(int table, List<Object> rows) throws ReflectiveOperationException {
            if (rows.isEmpty()) throw new IllegalArgumentException("IMPORT_EMPTY_INSERT");
            Object option = insertConstructor.newInstance();
            insertTable.invoke(option, table);
            insertDatas.invoke(option, rows);
            return option;
        }

        void insert(Object api, Object option) throws ReflectiveOperationException {
            Object observer = observerConstructor.newInstance();
            try {
                subscribe.invoke(insert.invoke(api, option), observer);
                if (!Boolean.TRUE.equals(observerResult.invoke(observer))) {
                    throw new IllegalStateException("IMPORT_INSERT_UNCONFIRMED");
                }
            } finally {
                // SyncObserver normally auto-disposes onNext; also release a timed-out subscription.
                try { observerDispose.invoke(observer); } catch (ReflectiveOperationException ignored) { }
            }
        }

        void insertRows(Object api, int table, List<Object> rows) throws ReflectiveOperationException {
            insert(api, insertOption(table, rows));
        }

        List<?> readRows(Object api, String account, int table, String device, long start, long end,
                int groupUnit, boolean parse) throws Exception {
            Object option = readConstructor.newInstance();
            readAccount.invoke(option, account);
            readStart.invoke(option, Math.max(0, start));
            readEnd.invoke(option, end);
            readTable.invoke(option, table);
            if (device != null && !device.isBlank()) readDevice.invoke(option, device);
            if (groupUnit != 0) {
                Class<?> readClass = readConstructor.getDeclaringClass();
                readClass.getMethod("setGroupUnitType", int.class).invoke(option, groupUnit);
                readClass.getMethod("setCount", int.class).invoke(option, 1);
                readClass.getMethod("setSortOrder", int.class).invoke(option, 1);
            }
            if (parse) {
                try {
                    readConstructor.getDeclaringClass().getMethod("setIsParse", int.class).invoke(option, 2);
                } catch (NoSuchMethodException ignored) { }
            }
            Object bean = awaitRead(read.invoke(api, option));
            if (!beanClass.isInstance(bean)) throw new IllegalStateException("IMPORT_READ_BEAN_TYPE");
            int code = (Integer) errorCode.invoke(bean);
            if (code == 101005) return List.of();
            if (code != 0) throw new IllegalStateException("IMPORT_READ_FAILED_" + code);
            Object value = payload.invoke(bean);
            if (!(value instanceof List<?> rows)) throw new IllegalStateException("IMPORT_READ_PAYLOAD");
            return rows;
        }
        /** Same query as ExtendStepCounterUtil.fetchDailyData: table 1002, sport mode -2. */
        List<?> readStepDays(Object api, String account, long start, long end) throws Exception {
            Object option = readConstructor.newInstance();
            readAccount.invoke(option, account);
            readStart.invoke(option, Math.max(0, start));
            readEnd.invoke(option, end);
            readTable.invoke(option, OHealthStepWriter.TABLE_STAT);
            Class<?> readClass = readConstructor.getDeclaringClass();
            readClass.getMethod("setReadSportMode", int.class).invoke(option, OHealthStepWriter.DAY_STEP_MODE);
            readClass.getMethod("setSortOrder", int.class).invoke(option, 1);
            Object bean = awaitRead(read.invoke(api, option));
            if (!beanClass.isInstance(bean)) throw new IllegalStateException("IMPORT_READ_BEAN_TYPE");
            int code = (Integer) errorCode.invoke(bean);
            if (code == 101005) return List.of();
            if (code != 0) throw new IllegalStateException("IMPORT_READ_FAILED_" + code);
            Object value = payload.invoke(bean);
            if (!(value instanceof List<?> rows)) throw new IllegalStateException("IMPORT_READ_PAYLOAD");
            return rows;
        }



        Set<OHealthHealthModels.Point> read(Object api, String account, OHealthHealthModels model,
                List<HealthRecord> records) throws Exception {
            long start = Long.MAX_VALUE;
            long end = 0;
            for (HealthRecord record : records) {
                start = Math.min(start, record.startMs);
                end = Math.max(end, record.startMs);
            }
            Object option = readConstructor.newInstance();
            readAccount.invoke(option, account);
            readStart.invoke(option, Math.max(0, start - 1));
            readEnd.invoke(option, end + 1);
            readTable.invoke(option, model.kind.table);
            readDevice.invoke(option, records.get(0).deviceId);
            readType.invoke(option, -1);
            Object bean = awaitRead(read.invoke(api, option));
            if (!beanClass.isInstance(bean)) throw new IllegalStateException("IMPORT_READ_BEAN_TYPE");
            int code = (Integer) errorCode.invoke(bean);
            if (code == 101005) return Set.of();
            if (code != 0) throw new IllegalStateException("IMPORT_READ_FAILED_" + code);
            Object value = payload.invoke(bean);
            if (!(value instanceof List<?> rows)) throw new IllegalStateException("IMPORT_READ_PAYLOAD");
            Set<OHealthHealthModels.Point> result = new HashSet<>();
            for (Object row : rows) result.add(model.key(row));
            return result;
        }

        private Object awaitRead(Object observable) throws Exception {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Object> bean = new AtomicReference<>();
            AtomicReference<Object> disposable = new AtomicReference<>();
            AtomicBoolean failed = new AtomicBoolean();
            AtomicBoolean closed = new AtomicBoolean();
            Object observer = Proxy.newProxyInstance(loader, new Class<?>[]{observerClass}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "onSubscribe" -> {
                        disposable.set(args[0]);
                        if (closed.get()) dispose.invoke(args[0]);
                    }
                    case "onNext" -> { bean.compareAndSet(null, args[0]); done.countDown(); }
                    case "onError" -> { failed.set(true); done.countDown(); }
                    case "onComplete" -> done.countDown();
                    case "equals" -> { return proxy == args[0]; }
                    case "hashCode" -> { return System.identityHashCode(proxy); }
                    case "toString" -> { return "OplusBandReadObserver"; }
                    default -> throw new UnsupportedOperationException("IMPORT_OBSERVER_METHOD");
                }
                return null;
            });
            try {
                subscribe.invoke(observable, observer);
                if (!done.await(60, TimeUnit.SECONDS) || failed.get() || bean.get() == null) {
                    throw new IllegalStateException("IMPORT_READ_UNCONFIRMED");
                }
                return bean.get();
            } finally {
                closed.set(true);
                Object subscription = disposable.get();
                if (subscription != null) dispose.invoke(subscription);
            }
        }
    }
}
