// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

/** GATT accepts one descriptor write. The next CCCD starts only after that callback. */
final class BleNotifyQueue {
    private final int total;
    private int started;
    private boolean outstanding;

    BleNotifyQueue(int total) {
        if (total < 1) throw new IllegalArgumentException("total");
        this.total = total;
    }

    /** Index to write now, or -1 while a write is in flight or after the last one has started. */
    int start() {
        if (outstanding || started >= total) return -1;
        outstanding = true;
        return started++;
    }

    /** @return false when no write was waiting for a callback. */
    boolean confirmed() {
        if (!outstanding) return false;
        outstanding = false;
        return true;
    }

    boolean complete() {
        return !outstanding && started >= total;
    }
}
