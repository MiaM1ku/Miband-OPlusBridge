// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;

/**
 * Tracks the active call state sent to the wearable and filters states the device ignores.
 * Non-watch bands only display incoming rings (call_type=1): firmware rejects outgoing (3)
 * with notify->key err and ignores call_type=2 (MISSED_CALL in Xiaomi's proto).
 */
public final class HostCallState {
    public enum Action { NONE, SEND_CALL, END_CALL, REPLACE_WITH_CALL }

    public record Decision(Action action, int previous) {}

    private int posted;

    public synchronized Decision update(boolean watch, boolean removed, int callState) {
        int previous = posted;
        if (removed) {
            posted = 0;
            return new Decision(previous != 0 ? Action.END_CALL : Action.NONE, previous);
        }
        if (!watch) {
            if (callState == BandNotificationCommand.CALL_INCOMING) {
                if (previous == BandNotificationCommand.CALL_INCOMING) {
                    return new Decision(Action.NONE, previous);
                }
                posted = BandNotificationCommand.CALL_INCOMING;
                return new Decision(Action.SEND_CALL, previous);
            }
            // Answered incoming call: dismiss the ring once and return to idle so hang-up
            // does not send a second endCall(). Outgoing dials on a band are skipped.
            posted = 0;
            return new Decision(previous != 0 ? Action.END_CALL : Action.NONE, previous);
        }
        if (callState == previous) {
            return new Decision(Action.NONE, previous);
        }
        posted = callState;
        return new Decision(previous != 0 ? Action.REPLACE_WITH_CALL : Action.SEND_CALL, previous);
    }

    public synchronized boolean clear() {
        boolean wasShowing = posted != 0;
        posted = 0;
        return wasShowing;
    }

    public synchronized void reset() {
        posted = 0;
    }
}
