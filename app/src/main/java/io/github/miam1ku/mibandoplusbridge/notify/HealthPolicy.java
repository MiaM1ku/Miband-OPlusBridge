// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import java.util.List;
import java.util.Set;

/** OPPO Health's notification switches, as last pushed by the Health process. */
public final class HealthPolicy {
    private HealthPolicy() {}

    /**
     * @return null when the post may continue. {@code health-main}, {@code health-screen}
     *         or {@code health-package} when Health would not send it.
     *         An unknown policy allows everything.
     */
    public static String blockReason(boolean known, boolean main, boolean screenOnPush,
            Set<String> open, Set<String> closed, Set<String> defaults, List<String> mms,
            String packageName, boolean interactive, boolean locked) {
        if (packageName == null || packageName.isBlank()) return "health-package";
        if (!known) return null;
        if (!main) return "health-main";
        if (!screenOnPush && interactive && !locked) return "health-screen";
        if (!packageOn(open, closed, defaults, mms, packageName)) return "health-package";
        return null;
    }

    /** A row wins over the default whitelist. An SMS package follows the first SMS row. */
    static boolean packageOn(Set<String> open, Set<String> closed, Set<String> defaults,
            List<String> mms, String packageName) {
        Boolean row = row(open, closed, packageName);
        if (row == null && mms != null && mms.contains(packageName)) {
            for (String candidate : mms) {
                row = row(open, closed, candidate);
                if (row != null) break;
            }
        }
        if (row != null) return row;
        return defaults != null && defaults.contains(packageName);
    }

    private static Boolean row(Set<String> open, Set<String> closed, String packageName) {
        if (open != null && open.contains(packageName)) return true;
        if (closed != null && closed.contains(packageName)) return false;
        return null;
    }
}
