// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

/** OHealth whitelist values are Boolean on some builds and 0/1 on others. */
public final class NotifySwitch {
    private NotifySwitch() {}

    public static boolean on(Object value) {
        if (value instanceof Boolean enabled) return enabled;
        if (value instanceof Number number) return number.intValue() != 0;
        return false;
    }
}
