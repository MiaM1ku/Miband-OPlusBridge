// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Proposes the current SSO ID only to the signed bridge; never logs or chooses an account. */
public final class OHealthAccountHook {
    private static final String HOST = "com.heytap.health";
    private static final AtomicReference<String> LAST_ACCOUNT = new AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicInteger ACCOUNT_TRIES = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile long lastProposedAt;

    private OHealthAccountHook() {}

    public static void install(Context context, ClassLoader loader) throws Exception {
        if (!HOST.equals(context.getPackageName())) return;
        try {
            installGetter(context, loader);
        } catch (Throwable failure) {
            StackTraceElement[] frames = failure.getStackTrace();
            String at = frames.length == 0 ? "" : " at " + frames[0].getClassName() + "." + frames[0].getMethodName();
            Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_HOOK_UNAVAILABLE " + failure.getClass().getSimpleName() + at);
            if (failure instanceof Exception checked) throw checked;
            throw new IllegalStateException(failure.getClass().getSimpleName());
        }
    }

    private static void installGetter(Context context, ClassLoader loader) throws Exception {
        Class<?> helper = Class.forName("com.heytap.device.data.storage.DataRepositoryHelper$Companion", false, loader);
        var getter = helper.getDeclaredMethod("getSsoId");
        if (getter.getReturnType() != String.class) throw new NoSuchMethodException("HOST_VERSION_UNSUPPORTED_OHEALTH");
        XposedBridge.hookMethod(getter, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                String account = (String) param.getResult();
                String previous = LAST_ACCOUNT.getAndSet(account);
                if (Objects.equals(previous, account)
                        && android.os.SystemClock.elapsedRealtime() - lastProposedAt < 60_000L) return;
                try {
                    Bundle data = new Bundle();
                    if (account != null && !account.isBlank() && account.length() <= 512) {
                        data.putString("account", account);
                        context.getContentResolver().call(HealthQueueProvider.URI, "proposeAccount", null, data);
                    } else if (previous != null && !previous.isBlank()) {
                        context.getContentResolver().call(HealthQueueProvider.URI, "accountSignedOut", null, null);
                    } else {
                        return;
                    }
                    data.clear();
                    lastProposedAt = android.os.SystemClock.elapsedRealtime();
                } catch (Throwable unavailable) {
                    // A failed proposal must be retried, not treated as account confirmation.
                    LAST_ACCOUNT.compareAndSet(account, previous);
                    lastProposedAt = 0;
                    Log.i("OplusBandBridge", "OHEALTH_ACCOUNT_PROPOSAL_FAILED "
                            + unavailable.getClass().getSimpleName());
                    int attempt = ACCOUNT_TRIES.getAndIncrement();
                    if (attempt < 4) {
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                Class<?> owner = Class.forName(
                                        "com.heytap.device.data.storage.DataRepositoryHelper", false, loader);
                                Object companion = owner.getField("Companion").get(null);
                                XposedHelpers.callMethod(companion, "getSsoId");
                            } catch (Throwable ignored) { }
                        }, 2_000L * (attempt + 1));
                    }
                }
            }
        });
        XC_MethodHook refreshAccount = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!"com.heytap.health".equals(Application.getProcessName())) return;
                try {
                    Class<?> owner = Class.forName("com.heytap.device.data.storage.DataRepositoryHelper", false, loader);
                    Object companion = owner.getField("Companion").get(null);
                    XposedHelpers.callMethod(companion, "getSsoId");
                } catch (Throwable unavailable) {
                    // The getter hook remains installed for a later login.
                }
            }
        };
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", refreshAccount);
        XposedHelpers.findAndHookMethod(android.app.Activity.class, "onResume", refreshAccount);
    }
}
