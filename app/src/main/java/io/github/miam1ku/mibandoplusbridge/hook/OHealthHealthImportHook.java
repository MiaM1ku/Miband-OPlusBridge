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
    private final AtomicBoolean tokenRefreshTried = new AtomicBoolean();
    private final AtomicBoolean tokenFallbackLogged = new AtomicBoolean();
    private long retryAfter;
    private boolean stressZerosCleared;
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
        hook.keepStoredAccount();
        hook.installAccountSdk();
        hook.installTokenFallback();
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
                // Cache the id OHealth already returned. Never call getSsoId.
                String account = param.hasThrowable() ? null : (String) param.getResult();
                if (!usableAccount(account)) return;
                String previous = observedAccount.getAndSet(account);
                host.publishedAccount = account;
                if (!Objects.equals(previous, account)) {
                    accountEpoch.incrementAndGet();
                    String seen = account;
                    worker.post(() -> adoptAccount(seen));
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
            @Override protected void afterHookedMethod(MethodHookParam param) {
                restoreSystemAccount();
                Handler main = new Handler(android.os.Looper.getMainLooper());
                main.postDelayed(OHealthHealthImportHook.this::restoreSystemAccount, 2_000);
                main.postDelayed(OHealthHealthImportHook.this::restoreSystemAccount, 8_000);
                main.postDelayed(OHealthHealthImportHook.this::restoreSystemAccount, 20_000);
                main.postDelayed(OHealthHealthImportHook.this::restoreSystemAccount, 45_000);
                request();
            }
        });
    }
    /**
     * The setup activity treats a missing login task as cancellation and writes tourist mode
     * on later launches too, even when the system account is still signed in. Put that account
     * back whenever Health comes up as a guest. Do not cache the account from inside
     * {@code setIsInTouristMode}: that re-enters the login screen and kills the process.
     */
    private void restoreSystemAccount() {
        try {
            ClassLoader loader = host.loader;
            Class<?> tourist = Class.forName("com.heytap.health.base.tourist.TouristHelper", false, loader);
            boolean guest = Boolean.TRUE.equals(tourist.getMethod("getIsInTouristMode").invoke(null));
            Class<?> accounts = Class.forName("com.heytap.health.account.AccountHelper", false, loader);
            Object manager = accounts.getMethod("getAccountManager").invoke(null);
            boolean system = manager != null && Boolean.TRUE.equals(
                    manager.getClass().getMethod("isSystemLogin").invoke(manager));
            boolean storedLogin = accountStillSigned();
            Class<?> prefsType = Class.forName("com.heytap.health.base.sp.SPUtils", false, loader);
            Object prefs = prefsType.getMethod("getInstance").invoke(null);
            String stored = String.valueOf(prefs.getClass().getMethod("getString", String.class)
                    .invoke(prefs, "user_ssoid"));
            boolean placeholder = stored.isBlank() || "com.heytap.health".equals(stored) || "null".equals(stored);
            Class<?> status = Class.forName("com.heytap.health.base.tourist.TouristStatus", false, loader);
            boolean agreed = Boolean.TRUE.equals(status.getMethod("hasAgreeHealth").invoke(null));
            String check = "OHEALTH_ACCOUNT_RESTORE_CHECK guest=" + guest
                    + " system=" + system + " stored=" + storedLogin
                    + " placeholder=" + placeholder + " agreed=" + agreed;
            Log.i("OplusBandBridge", check);
            OHealthDeviceHook.traceLine(context, check);
            // isSystemLogin is false until the account SDK initializes, even when the last
            // session was signed in. Without the health agreement, set(false) is forced back
            // to true and rewrites user_ssoid to the package name.
            // An empty token makes HeadInterceptor answer 10101. The H5 page shows that as 403,
            // and the device tab shows it as "no network" once our band is in the local list.
            if (agreed && (system || storedLogin) && tokenMissing(loader)
                    && manager != null && tokenRefreshTried.compareAndSet(false, true)) {
                manager.getClass().getMethod("cacheAccountInfo", boolean.class).invoke(manager, true);
                Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_TOKEN_REFRESH");
                OHealthDeviceHook.traceLine(context, "OHEALTH_ACCOUNT_TOKEN_REFRESH");
            }
            if (!agreed || !(system || storedLogin) || (!guest && !placeholder)) return;
            tourist.getMethod("setIsInTouristMode", boolean.class).invoke(null, false);
            if (manager != null) {
                manager.getClass().getMethod("cacheAccountInfo", boolean.class).invoke(manager, true);
            }
            Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_RESTORED");
            OHealthDeviceHook.traceLine(context, "OHEALTH_ACCOUNT_RESTORED");
        } catch (Throwable failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_RESTORE_FAILED " + cause.getClass().getSimpleName());
        }
    }
    /**
     * LoginCheckStage writes tourist mode when the SDK is not initialized yet.
     * Ignore that write when the stored account is still real. Do not call back
     * into the setter from here.
     */
    private void keepStoredAccount() {
        try {
            Class<?> helper = Class.forName(
                    "com.heytap.health.base.tourist.TouristHelper", false, host.loader);
            Class<?> status = Class.forName(
                    "com.heytap.health.base.tourist.TouristStatus", false, host.loader);
            XC_MethodHook keep = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !Boolean.TRUE.equals(param.args[0])) return;
                    if (!accountStillSigned()) return;
                    param.setResult(null);
                    Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_TOURIST_IGNORED");
                    OHealthDeviceHook.traceLine(context, "OHEALTH_ACCOUNT_TOURIST_IGNORED");
                }
            };
            XposedBridge.hookAllMethods(helper, "setIsInTouristMode", keep);
            XposedBridge.hookAllMethods(status, "setIsInTouristMode", keep);
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_GUARD_FAILED "
                    + failure.getClass().getSimpleName());
        }
    }

    /** Same order as AcApkUtils.getAccountPkgName. The first installed package wins. */
    private static final String[] ACCOUNT_PACKAGES = {
            "com.oplus.sysaccount", "com.oneplus.sysaccount",
            "com.oplus.account", "com.oplus.vip",
            "com.oppo.usercenter", "com.heytap.usercenter",
            "com.heytap.vip", "com.oneplus.account"
    };

    /**
     * Account init during Application.attach sees a null application context.
     * The SDK then caches "no account app" and the open-account path NPEs,
     * leaving {@code initializing} true for the rest of the process.
     */
    private void installAccountSdk() {
        try {
            Class<?> packages = Class.forName(
                    "com.oplus.accountsdk.service.common.util.AcApkUtils", false, host.loader);
            Class<?> sdk = Class.forName("com.heytap.health.account.sdk.AccountSdk", false, host.loader);
            java.lang.reflect.Field cachedPackage = packages.getDeclaredField("acPkg");
            cachedPackage.setAccessible(true);
            accountInitializing = flag(sdk, "initializing");
            accountInitialized = flag(sdk, "initialized");
            java.util.concurrent.atomic.AtomicBoolean initializing = accountInitializing;
            java.util.concurrent.atomic.AtomicBoolean initialized = accountInitialized;
            if ("".equals(cachedPackage.get(null))) cachedPackage.set(null, null);
            if (initializing != null && initialized != null
                    && initializing.get() && !initialized.get()) {
                initializing.set(false);
                Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_INIT_RESET");
                OHealthDeviceHook.traceLine(context, "OHEALTH_ACCOUNT_INIT_RESET");
            }
            XposedBridge.hookAllMethods(packages, "getAccountPkgName", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.args[0] instanceof Context base) || applicationContext(base) != null) return;
                    String found = accountPackage(base);
                    if (found == null) return;
                    try {
                        cachedPackage.set(null, found);
                    } catch (IllegalAccessException ignored) { }
                    param.setResult(found);
                    Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_PKG " + found);
                }
            });
            XposedBridge.hookAllMethods(sdk, "init", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof Context passed)) return;
                    if (applicationContext(passed) != null) return;
                    Context ready = application();
                    if (ready == null) return;
                    param.args[0] = ready;
                    Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_INIT_CONTEXT");
                }
            });
            Handler main = new Handler(android.os.Looper.getMainLooper());
            for (long delay : new long[] {1_000L, 3_000L, 8_000L}) {
                main.postDelayed(this::retryAccountInit, delay);
            }
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_SDK_HOOK_FAILED "
                    + failure.getClass().getSimpleName());
        }
    }

    private java.util.concurrent.atomic.AtomicBoolean accountInitializing;
    private java.util.concurrent.atomic.AtomicBoolean accountInitialized;

    /** A ContextWrapper whose base is not attached yet throws here instead of returning null. */
    private static Context applicationContext(Context base) {
        if (base == null) return null;
        try {
            return base.getApplicationContext();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Context application() {
        Context app = applicationContext(context);
        if (app != null) return app;
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object current = thread.getMethod("currentApplication").invoke(null);
            if (current instanceof Context ready) {
                Context application = applicationContext(ready);
                if (application != null) return application;
            }
        } catch (ReflectiveOperationException ignored) { }
        return null;
    }

    private void retryAccountInit() {
        try {
            Context app = application();
            if (app == null) return;
            Class<?> sdk = Class.forName("com.heytap.health.account.sdk.AccountSdk", false, host.loader);
            if (Boolean.TRUE.equals(sdk.getMethod("isInitialized").invoke(null))) return;
            if (accountInitializing != null && accountInitialized != null
                    && accountInitializing.get() && !accountInitialized.get()) {
                accountInitializing.set(false);
            } else if (Boolean.TRUE.equals(sdk.getMethod("isInitializing").invoke(null))) {
                return;
            }
            sdk.getMethod("init", Context.class).invoke(null, app);
            Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_INIT_RETRY");
            OHealthDeviceHook.traceLine(context, "OHEALTH_ACCOUNT_INIT_RETRY");
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_INIT_RETRY_FAILED "
                    + failure.getClass().getSimpleName());
        }
    }

    private static String accountPackage(Context context) {
        android.content.pm.PackageManager manager;
        try {
            manager = context.getPackageManager();
        } catch (RuntimeException ignored) {
            return null;
        }
        for (String name : ACCOUNT_PACKAGES) {
            try {
                if (manager.getPackageInfo(name, 0) != null) return name;
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        }
        return null;
    }

    private static java.util.concurrent.atomic.AtomicBoolean flag(Class<?> type, String name) {
        try {
            java.lang.reflect.Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof java.util.concurrent.atomic.AtomicBoolean flag ? flag : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /**
     * The main process returns an empty token until the account SDK finishes init.
     * HeadInterceptor turns that into 10101, which the device tab calls "no network"
     * and the message page calls 403. Other processes already fall back to the stored token.
     */
    private void installTokenFallback() {
        try {
            Class<?> helper = Class.forName("com.heytap.health.base.account.TokenHelper", false, host.loader);
            Class<?> prefsType = Class.forName("com.heytap.health.base.sp.SPUtils", false, host.loader);
            de.robv.android.xposed.XposedBridge.hookAllMethods(helper, "getToken", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object current = param.getResult();
                    if (current != null && !String.valueOf(current).isBlank()) return;
                    try {
                        Object prefs = prefsType.getMethod("getInstance").invoke(null);
                        Object stored = prefs.getClass().getMethod("getString", String.class)
                                .invoke(prefs, "account_token");
                        if (stored == null || String.valueOf(stored).isBlank()) {
                            if (tokenFallbackLogged.compareAndSet(false, true)) {
                                Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_TOKEN_ABSENT");
                                OHealthDeviceHook.traceLine(context, "OHEALTH_ACCOUNT_TOKEN_ABSENT");
                            }
                            return;
                        }
                        param.setResult(String.valueOf(stored));
                        if (tokenFallbackLogged.compareAndSet(false, true)) {
                            Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_TOKEN_STORED");
                            OHealthDeviceHook.traceLine(context, "OHEALTH_ACCOUNT_TOKEN_STORED");
                        }
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_TOKEN_HOOK_FAILED "
                    + failure.getClass().getSimpleName());
        }
    }

    /** True when the request interceptor would refuse the call with 10101. */
    private static boolean tokenMissing(ClassLoader loader) {
        try {
            Class<?> helper = Class.forName("com.heytap.health.base.account.TokenHelper", false, loader);
            Object token = helper.getMethod("getToken").invoke(helper.getMethod("getInstance").invoke(null));
            return token == null || String.valueOf(token).isBlank();
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Last session's login flag or SSO. Does not call isSystemLogin. */
    private boolean accountStillSigned() {
        try {
            Class<?> prefsType = Class.forName("com.heytap.health.base.sp.SPUtils", false, host.loader);
            Object prefs = prefsType.getMethod("getInstance").invoke(null);
            boolean login = Boolean.TRUE.equals(prefs.getClass()
                    .getMethod("getBoolean", String.class, boolean.class)
                    .invoke(prefs, "login_status", false));
            String stored = String.valueOf(prefs.getClass().getMethod("getString", String.class)
                    .invoke(prefs, "user_ssoid"));
            boolean placeholder = stored.isBlank() || "com.heytap.health".equals(stored) || "null".equals(stored);
            return login || !placeholder;
        } catch (Throwable failure) {
            return false;
        }
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
            ensureAccount();
            if (!usableAccount(host.account())) {
                scheduleRetry(5_000);
                return;
            }
            OHealthDeviceHook.rememberBand(host.loader);
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
            if (!stressZerosCleared) {
                try {
                    stressZerosCleared = clearUnmeasuredStress();
                } catch (SecurityException paused) {
                    throw paused;
                } catch (Exception | LinkageError stressFail) {
                    retrySoon = true;
                    logImportFailure(stressFail);
                }
            }
            drain();
            if (steps != null) steps.write(context);
            lastFailure = null;
            if (retrySoon) scheduleRetry(2_000);
            OHealthHomeMetricHook.requestReload();
        } catch (Exception | LinkageError failure) {
            scheduleRetry(transientImport(failure) ? 2_000 : FAILURE_COOLDOWN_MS);
            logImportFailure(failure);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** A zero continuous sample is an empty minute. Remove any already stored in OHealth. */
    private boolean clearUnmeasuredStress() throws Exception {
        String account = host.account();
        Object api = host.api();
        if (!usableAccount(account) || api == null) return false;
        Bundle request = new Bundle();
        request.putString("account", account);
        Bundle gaps = context.getContentResolver().call(HealthQueueProvider.URI, "unmeasuredStress", null, request);
        if (gaps == null) throw new IllegalStateException("STRESS_GAP_UNAVAILABLE");
        long[] starts = gaps.getLongArray("starts");
        long[] ends = gaps.getLongArray("ends");
        String[] devices = gaps.getStringArray("devices");
        if (starts == null || ends == null || devices == null
                || starts.length != ends.length || starts.length != devices.length) {
            throw new IllegalStateException("STRESS_GAP_UNAVAILABLE");
        }
        int table = OHealthHealthModels.Kind.STRESS.table;
        for (int i = 0; i < starts.length; i++) {
            host.deleteRows(api, table, account, OHealthDeviceHook.healthId(devices[i]), starts[i], ends[i]);
        }
        if (starts.length > 0) host.syncCloud(api, table);
        Bundle forgotten = context.getContentResolver().call(HealthQueueProvider.URI,
                "forgetUnmeasuredStress", null, request);
        if (forgotten == null) throw new IllegalStateException("STRESS_GAP_UNAVAILABLE");
        return true;
    }

    /** The app can already be signed in without calling getSsoId. Read it once per import pass. */
    private void ensureAccount() {
        if (usableAccount(host.account())) return;
        try {
            Object value = host.accountGetter.invoke(host.companion);
            String account = value instanceof String ? (String) value : null;
            if (!usableAccount(account)) {
                if (!"OHEALTH_ACCOUNT_UNAVAILABLE".equals(lastFailure)) {
                    Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_UNAVAILABLE");
                    lastFailure = "OHEALTH_ACCOUNT_UNAVAILABLE";
                }
                return;
            }
            if (!usableAccount(host.account())) {
                String previous = observedAccount.getAndSet(account);
                host.publishedAccount = account;
                if (!Objects.equals(previous, account)) accountEpoch.incrementAndGet();
            }
            Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_OBSERVED");
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_READ_FAILED " + failure.getClass().getSimpleName());
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
                    host.syncCloud(api, model.kind.table);
                    forgetPreviousDevice(api, account, model.kind.table, records);
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
    /** Rows stored under the bridge id are not visible once the band is addressed by MAC. */
    private void forgetPreviousDevice(Object api, String account, int table, List<HealthRecord> records)
            throws Exception {
        String previous = records.get(0).deviceId;
        String stored = OHealthDeviceHook.healthId(previous);
        if (previous == null || previous.equals(stored)) return;
        long start = Long.MAX_VALUE;
        long end = 0;
        for (HealthRecord record : records) {
            start = Math.min(start, record.startMs);
            end = Math.max(end, record.startMs);
        }
        if (end < start) return;
        try {
            host.deleteRows(api, table, account, previous, Math.max(0, start - 1), end + 1);
        } catch (Exception ignored) {
            Log.i("OplusBandBridge", "OHEALTH_PREVIOUS_DEVICE_KEPT table=" + table);
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

    /** Health's tourist placeholder is not a signed-in SSO. */
    private static boolean usableAccount(String account) {
        return account != null && !account.isBlank() && account.length() <= 512
                && !"com.heytap.health".equals(account);
    }

    /** Bind whatever real account OHealth already returned, including after a switch or a pause. */
    private void adoptAccount(String account) {
        try {
            Bundle data = new Bundle();
            data.putString("account", account);
            context.getContentResolver().call(HealthQueueProvider.URI, "adoptAccount", null, data);
            request();
        } catch (Throwable ignored) {
            Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_ADOPT_FAILED");
        }
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

        volatile String publishedAccount;
        String account() { return publishedAccount; }
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
            syncCloud(api, table);
        }

        void deleteRows(Object api, int table, String account, String device, long start, long end)
                throws Exception {
            if (device == null || device.isBlank() || end <= start) {
                throw new IllegalArgumentException("HEALTH_DELETE_WINDOW");
            }
            Class<?> type = Class.forName("com.heytap.databaseengine.option.DataDeleteOption", false, loader);
            Object option = type.getConstructor().newInstance();
            type.getMethod("setSsoid", String.class).invoke(option, account);
            type.getMethod("setDeviceUniqueId", String.class).invoke(option, device);
            type.getMethod("setDataTable", int.class).invoke(option, table);
            type.getMethod("setStartTime", long.class).invoke(option, Math.max(0, start));
            // end is exclusive. Inclusive host filters must not eat the next real sample.
            type.getMethod("setEndTime", long.class).invoke(option, end - 1);
            Object observer = observerConstructor.newInstance();
            boolean reportedSuccess = false;
            try {
                subscribe.invoke(api.getClass().getMethod("deleteSportHealthData", type).invoke(api, option), observer);
                reportedSuccess = Boolean.TRUE.equals(observerResult.invoke(observer));
            } finally {
                try { observerDispose.invoke(observer); } catch (ReflectiveOperationException ignored) { }
            }
            // Table 1001 store.delete returns the deleted row count on this callback, and
            // SyncObserver treats every non-zero value as failure. Wait above, then confirm
            // the window is empty. Sleep 1010, step stat 1002 and stress 1017 inherit
            // SportDataStore.delete, which returns 0 and removes nothing. An empty-window
            // check there fails a no-op and stops the night or the stress cleanup.
            if (table == OHealthStepWriter.TABLE_DETAIL) {
                if (minuteRowsRemain(api, account, device, start, end)) {
                    throw new IllegalStateException("HEALTH_DELETE_UNCONFIRMED");
                }
                return;
            }
            if (!reportedSuccess) throw new IllegalStateException("HEALTH_DELETE_UNCONFIRMED");
        }

        /** Detail deletes use the same inclusive host end as the read, so an empty read confirms one. */
        private boolean minuteRowsRemain(Object api, String account, String device, long start, long end)
                throws Exception {
            return !readRows(api, account, OHealthStepWriter.TABLE_DETAIL, device,
                    Math.max(0, start), end - 1, 0, false, 1).isEmpty();
        }

        /** Same post-insert cloud request as databaseengineservice. A cloud failure leaves the local row. */
        void syncCloud(Object api, int table) {
            int[] request = cloudRequest(table);
            if (request == null || api == null) return;
            try {
                Class<?> type = Class.forName("com.heytap.databaseengine.option.DataSyncOption", false, loader);
                Object option = syncOption(type);
                type.getMethod("setSyncDataType", int.class).invoke(option, request[0]);
                type.getMethod("setSyncAction", int.class).invoke(option, request[1]);
                type.getMethod("setSyncScope", int.class).invoke(option, 1);
                Object observer = observerConstructor.newInstance();
                try {
                    subscribe.invoke(api.getClass().getMethod("synCloud", type).invoke(api, option), observer);
                } finally {
                    try { observerDispose.invoke(observer); } catch (ReflectiveOperationException ignored) { }
                }
            } catch (Throwable failure) {
                Log.i("OplusBandBridge", "OHEALTH_CLOUD_SYNC_FAILED table=" + table
                        + " " + failure.getClass().getSimpleName());
            }
        }

        private static Object syncOption(Class<?> type) throws ReflectiveOperationException {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (constructor.getParameterCount() != 7) continue;
                constructor.setAccessible(true);
                return constructor.newInstance(0, 0, 0, 0, null, null, 0L);
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (constructor.getParameterCount() != 9) continue;
                constructor.setAccessible(true);
                return constructor.newInstance(0, 0, 0, 0, null, null, 0L, 0x7f, null);
            }
            throw new NoSuchMethodException("CLOUD_SYNC_OPTION");
        }

        /**
         * DataHelper.syncInsertSportData on 6.9.40. Heart, sleep, oxygen and stress are
         * not in this switch; the service insert starts WorkManager for those tables.
         */
        private static int[] cloudRequest(int table) {
            return switch (table) {
                case 1001, 1002 -> new int[]{1, 1};
                case 1004, 1012 -> new int[]{1000, 0};
                case 1024 -> new int[]{13, 1};
                case 1075 -> new int[]{18, 0};
                case 1172, 1173, 1174, 1175, 1176, 1177 -> new int[]{17, 1};
                default -> null;
            };
        }

        List<?> readRows(Object api, String account, int table, String device, long start, long end,
                int groupUnit, boolean parse) throws Exception {
            return readRows(api, account, table, device, start, end, groupUnit, parse, 0);
        }

        List<?> readRows(Object api, String account, int table, String device, long start, long end,
                int groupUnit, boolean parse, int limit) throws Exception {
            Object option = readConstructor.newInstance();
            readAccount.invoke(option, account);
            readStart.invoke(option, Math.max(0, start));
            readEnd.invoke(option, end);
            readTable.invoke(option, table);
            if (device != null && !device.isBlank()) readDevice.invoke(option, device);
            Class<?> readClass = readConstructor.getDeclaringClass();
            if (groupUnit != 0) {
                readClass.getMethod("setGroupUnitType", int.class).invoke(option, groupUnit);
                readClass.getMethod("setCount", int.class).invoke(option, 1);
                readClass.getMethod("setSortOrder", int.class).invoke(option, 1);
            }
            if (limit > 0) readClass.getMethod("setCount", int.class).invoke(option, limit);
            if (parse) {
                try {
                    readClass.getMethod("setIsParse", int.class).invoke(option, 2);
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
        /** Walk-mode read. A home-mode read uses the sportMode overload and does not change this one. */
        List<?> readStepDays(Object api, String account, long start, long end) throws Exception {
            return readStepDays(api, account, start, end, OHealthStepWriter.MINUTE_MODE);
        }

        List<?> readStepDays(Object api, String account, long start, long end, int sportMode) throws Exception {
            Object option = readConstructor.newInstance();
            readAccount.invoke(option, account);
            readStart.invoke(option, Math.max(0, start));
            readEnd.invoke(option, end);
            readTable.invoke(option, OHealthStepWriter.TABLE_STAT);
            Class<?> readClass = readConstructor.getDeclaringClass();
            readClass.getMethod("setReadSportMode", int.class).invoke(option, sportMode);
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
            readDevice.invoke(option, OHealthDeviceHook.healthId(records.get(0).deviceId));
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
