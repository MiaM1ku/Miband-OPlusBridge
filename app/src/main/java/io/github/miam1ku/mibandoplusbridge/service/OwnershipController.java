// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.content.Context;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import android.content.pm.PackageManager;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Fixed-package, reversible connection ownership. Never accepts caller-supplied shell text. */
public final class OwnershipController {
    private static final String BRIDGE = "io.github.miam1ku.mibandoplusbridge";
    private static final String OFFICIAL = HostIdentity.MI_PACKAGE;
    private static final ReentrantReadWriteLock GATE = new ReentrantReadWriteLock(true);
    private final Context context;
    private final LocalPrefs state;

    public static void beginNativeSession() { GATE.readLock().lock(); }
    public static void endNativeSession() { GATE.readLock().unlock(); }

    public static final class Failure extends Exception {
        public final String code;
        public Failure(String code) { super(code); this.code = code; }
    }

    public OwnershipController(Context context) {
        this.context = context.getApplicationContext();
        state = LocalPrefs.open(this.context, "ownership");
    }

    public String mode() { return state.getString("mode", "OFFICIAL"); }

    public synchronized boolean nativeReady() {
        return "NATIVE".equals(mode()) && state.getBoolean("ownsDisable", false)
                && !state.getBoolean("officialRestored", false)
                && context.getSystemService(UserManager.class).isUserUnlocked();
    }

    /** True only after the user has allowed this app in KernelSU. No grant dialog is shown. */
    public boolean probeRoot() {
        try {
            return "0".equals(execute("id -u", 8));
        } catch (Failure ignored) {
            return false;
        }
    }

    public synchronized void takeOver() throws Failure {
        GATE.writeLock().lock();
        try {
        requireUnlocked();
        if (!HostIdentity.installed(context, HostIdentity.MI_PACKAGE)) throw new Failure("HOST_VERSION_UNSUPPORTED");
        requireRoot();
        if (state.getBoolean("ownsDisable", false)) {
            if (nativeReady()) return;
            throw new Failure("OWNERSHIP_RECOVERY_REQUIRED");
        }
        int original = officialState();
        if (original != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                && original != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            throw new Failure("OFFICIAL_ALREADY_DISABLED");
        }
        if (!state.edit().putInt("originalEnabled", original).putBoolean("transitionPending", true)
                .putBoolean("officialRestored", false).putString("mode", "OFFICIAL").commit()) {
            throw new Failure("OWNERSHIP_STORAGE_FAILED");
        }
        try {
            run("am force-stop com.mi.health", "OFFICIAL_STOP_FAILED");
            run("pm disable-user --user 0 com.mi.health", "OFFICIAL_DISABLE_FAILED");
            run("am force-stop com.mi.health", "OFFICIAL_STOP_FAILED");
            if (officialState() != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                throw new Failure("OFFICIAL_DISABLE_UNCONFIRMED");
            }
            ensureDoze();
            if (!state.edit().putBoolean("ownsDisable", true).putBoolean("transitionPending", false)
                    .putString("mode", "NATIVE").commit()) throw new Failure("OWNERSHIP_STORAGE_FAILED");
            if (officialState() != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                throw new Failure("OFFICIAL_DISABLE_UNCONFIRMED");
            }
            run("am force-stop com.mi.health", "OFFICIAL_STOP_FAILED");
        } catch (Failure error) {
            rollbackPending(original);
            throw error;
        }
        } finally {
            GATE.writeLock().unlock();
        }
    }

    public synchronized void restoreOfficial() throws Failure {
        GATE.writeLock().lock();
        try {
        if (!state.getBoolean("ownsDisable", false) && !state.getBoolean("transitionPending", false)) return;
        requireRoot();
        int original = state.getInt("originalEnabled", -1);
        if (original != 0 && original != 1) throw new Failure("ORIGINAL_STATE_UNKNOWN");
        if (!state.getBoolean("officialRestored", false)) {
            restorePackage(original);
            if (officialState() != original) throw new Failure("OFFICIAL_RESTORE_UNCONFIRMED");
            if (!state.edit().putBoolean("officialRestored", true).commit()) {
                throw new Failure("OWNERSHIP_STORAGE_FAILED");
            }
        }
        dropDoze();
        if (!state.edit().putString("mode", "OFFICIAL").putBoolean("ownsDisable", false)
                .putBoolean("transitionPending", false).remove("originalEnabled")
                .remove("officialRestored").remove("dozeAdded").commit()) {
            throw new Failure("OWNERSHIP_STORAGE_FAILED");
        }
        } finally {
            GATE.writeLock().unlock();
        }
    }

    public synchronized boolean restoreIfModuleInvalid() throws Failure {
        GATE.writeLock().lock();
        try {
        if (!state.getBoolean("ownsDisable", false) && !state.getBoolean("transitionPending", false)) return false;
        if (nativeReady()) return false;
        restoreOfficial();
        return true;
        } finally {
            GATE.writeLock().unlock();
        }
    }

    private void rollbackPending(int original) {
        try {
            restorePackage(original);
            if (officialState() == original) {
                dropDoze();
                if (!state.edit().putString("mode", "OFFICIAL").putBoolean("ownsDisable", false)
                        .putBoolean("transitionPending", false).remove("originalEnabled")
                        .remove("officialRestored").remove("dozeAdded").commit()) {
                    throw new Failure("OWNERSHIP_STORAGE_FAILED");
                }
            }
        } catch (Failure ignored) {
        }
    }

    private void restorePackage(int original) throws Failure {
        if (original == 0) run("pm default-state --user 0 com.mi.health", "OFFICIAL_RESTORE_FAILED");
        else if (original == 1) run("pm enable --user 0 com.mi.health", "OFFICIAL_RESTORE_FAILED");
        else throw new Failure("ORIGINAL_STATE_UNKNOWN");
    }

    private int officialState() throws Failure {
        try { return context.getPackageManager().getApplicationEnabledSetting(OFFICIAL); }
        catch (IllegalArgumentException absent) { throw new Failure("OFFICIAL_APP_MISSING"); }
    }

    private void requireUnlocked() throws Failure {
        if (!context.getSystemService(UserManager.class).isUserUnlocked()) throw new Failure("USER_LOCKED");
    }
    private void requireRoot() throws Failure {
        if (!"0".equals(execute("id -u"))) throw new Failure("ROOT_REQUIRED");
    }

    private void ensureDoze() {
        try {
            String list = execute("cmd deviceidle whitelist");
            if (list.contains(BRIDGE)) return;
            execute("cmd deviceidle whitelist +" + BRIDGE);
            state.edit().putBoolean("dozeAdded", true).commit();
        } catch (Failure ignored) { }
    }

    private void dropDoze() {
        if (!state.getBoolean("dozeAdded", false)) return;
        try {
            execute("cmd deviceidle whitelist -" + BRIDGE);
        } catch (Failure ignored) { }
    }

    private void run(String fixedCommand, String failureCode) throws Failure {
        try { execute(fixedCommand); }
        catch (Failure failure) { throw new Failure(failureCode); }
    }
    static String shellQuote(String command) {
        return "'" + command.replace("'", "'\\''") + "'";
    }
    private static String execute(String fixedCommand) throws Failure {
        return execute(fixedCommand, 30);
    }
    private static String execute(String fixedCommand, int timeoutSeconds) throws Failure {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "/system/bin/sh -c " + shellQuote(fixedCommand))
                    .redirectErrorStream(true).start();
            if (!process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new Failure("ROOT_ACTION_TIMEOUT");
            }
            byte[] output = process.getInputStream().readNBytes(256);
            String text = new String(output, StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) throw new Failure("ROOT_ACTION_FAILED");
            return text;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Failure("ROOT_ACTION_FAILED");
        } finally {
            if (process != null) process.destroy();
        }
    }
}
