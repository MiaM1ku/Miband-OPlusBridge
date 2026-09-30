// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import java.util.Set;

/** Package and screen rules shared by the listener and the health cache. */
public final class NotifyAdmission {
    private NotifyAdmission() {}

    /** An empty selection means every package. A non-empty selection is a real allowlist. */
    public static boolean packageAllowed(Set<String> packages, String packageName) {
        if (packageName == null || packageName.isBlank()) return false;
        return packages == null || packages.isEmpty() || packages.contains(packageName);
    }

    /**
     * Before health has reported a switch, unknown packages stay allowed.
     * An explicit deny or a closed main switch blocks.
     */
    public static boolean healthAllows(boolean mainKnown, boolean mainOn, Set<String> denied, String packageName) {
        if (packageName == null || packageName.isBlank()) return false;
        if (mainKnown && !mainOn) return false;
        return denied == null || !denied.contains(packageName);
    }

    /** {@code screen_on_push} false blocks only while the screen is on and unlocked. */
    public static boolean screenBlocks(boolean screenOnPush, boolean interactive, boolean locked) {
        return !screenOnPush && interactive && !locked;
    }
}
