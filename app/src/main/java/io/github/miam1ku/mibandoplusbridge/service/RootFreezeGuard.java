// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.content.Context;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * ColourOS freezes a cached process through the framework freezer: system_server writes 1 to
 * {@code /sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze}. The process then stops running
 * until something touches one of its providers, which is why the live service can sit on a stale
 * foreground notice for a long time.
 *
 * <p>The per-app 「应用速冻」 page and its API ({@code IOplusHansFreezeManager.requestFrozenDelay})
 * need {@code com.oplus.permission.safe.POWER} (signature|privileged). Root does not hold it
 * either: a root call comes back through the callback as {@code onError(1)}. Writing the cgroup
 * file, however, is allowed for root, and the ROM does not put the flag back within a minute, so a
 * tiny root shell can undo each freeze a few seconds after it happens. The shell is a separate
 * process, so it keeps running while this app is frozen.</p>
 *
 * <p>The loop exits with this process: it polls {@code kill -0 <pid>} so a service restart starts a
 * fresh guard instead of leaving an orphan behind.</p>
 */
final class RootFreezeGuard {
    private static final long POLL_SECONDS = 5;
    private static final Object LOCK = new Object();
    private static boolean started;
    private static Process process;

    private RootFreezeGuard() {}

    /** Called from the service's root worker; {@code su} runs off the main thread. */
    static void start(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        int uid = app.getApplicationInfo().uid;
        int pid = android.os.Process.myPid();
        synchronized (LOCK) {
            if (started && process != null && isAlive(process)) return;
            started = true;
        }
        String script = "APP_PID=" + pid + "; APP_UID=" + uid + "; "
                + "while kill -0 $APP_PID 2>/dev/null; do "
                + "for f in /sys/fs/cgroup/apps/uid_$APP_UID/pid_*/cgroup.freeze; do "
                + "[ -f \"$f\" ] || continue; "
                + "if [ \"$(cat \"$f\" 2>/dev/null)\" = 1 ]; then "
                + "echo 0 > \"$f\" 2>/dev/null && echo thaw $(basename $(dirname \"$f\")); "
                + "fi; done; sleep " + POLL_SECONDS + "; done";
        try {
            Process spawned = new ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true).start();
            synchronized (LOCK) { process = spawned; }
            SessionLog.line(app, "FREEZE_GUARD started uid=" + uid + " pid=" + pid);
            Thread reader = new Thread(() -> {
                try (BufferedReader out = new BufferedReader(new InputStreamReader(
                        spawned.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                        if (!line.startsWith("thaw ")) continue;
                        SessionLog.line(app, "FREEZE_GUARD " + line.trim());
                    }
                } catch (Exception ignored) {
                    // The service stopping closes the stream; nothing left to report.
                }
            }, "band-freeze-guard");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception unavailable) {
            synchronized (LOCK) { started = false; process = null; }
            SessionLog.line(app, "FREEZE_GUARD unavailable "
                    + unavailable.getClass().getSimpleName());
        }
    }

    private static boolean isAlive(Process candidate) {
        try {
            candidate.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }
}
