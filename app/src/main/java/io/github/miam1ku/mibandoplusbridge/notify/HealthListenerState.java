// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

/** Whether OPPO Health's own notification listener is connected in this process. */
public final class HealthListenerState {
    private static volatile boolean connected;

    private HealthListenerState() {}

    public static boolean connected() { return connected; }

    public static void connected(boolean value) { connected = value; }
}
