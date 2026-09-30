// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.content.Context;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Health wakes the live service. The live service wakes health. Root is the background-start fallback. */
public final class HostKeepAlive {
    static final long ROOT_GAP_MS = 15_000;
    private static final AtomicLong bridgeRootAt = new AtomicLong();
    private static final AtomicLong healthRootAt = new AtomicLong();
    private static final ExecutorService root = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "OplusBandWake");
        thread.setDaemon(true);
        return thread;
    });

    private HostKeepAlive() {}

    /** Notification, music, or a health card read. No-op when the live service is already up. */
    public static void ensureBridge(Context context) {
        BandLiveService.ensureProcess(context);
    }

    /** Binder and broadcast threads must not wait on su. */
    public static void startBridgeAsync(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (!claim(bridgeRootAt, System.currentTimeMillis(), ROOT_GAP_MS)) return;
        root.execute(() -> runBridge(app));
    }

    /** Package-replaced recovery. The caller is already off the main thread. */
    public static void startBridge(Context context) {
        if (context == null) return;
        if (!claim(bridgeRootAt, System.currentTimeMillis(), ROOT_GAP_MS)) return;
        runBridge(context.getApplicationContext());
    }

    public static void startHealthAsync(Context context, Runnable after) {
        if (context == null || !BandLiveService.mayWake(context)) return;
        Context app = context.getApplicationContext();
        if (!claim(healthRootAt, System.currentTimeMillis(), ROOT_GAP_MS)) return;
        root.execute(() -> {
            boolean started = OwnershipController.rootStart(
                    "com.heytap.health", "com.heytap.health.rpc.host.HealthRpcMsgService", false);
            SessionLog.line(app, started ? "WAKE_HEALTH root" : "WAKE_HEALTH root-failed");
            if (started && after != null) after.run();
        });
    }

    static boolean claim(AtomicLong last, long nowMs, long gapMs) {
        while (true) {
            long previous = last.get();
            if (previous != 0 && nowMs - previous < gapMs) return false;
            if (last.compareAndSet(previous, nowMs)) return true;
        }
    }

    private static void runBridge(Context context) {
        boolean started = OwnershipController.rootStart("io.github.miam1ku.mibandoplusbridge",
                "io.github.miam1ku.mibandoplusbridge.service.BandLiveService", true);
        SessionLog.line(context, started ? "WAKE_BRIDGE root" : "WAKE_BRIDGE root-failed");
    }
}
