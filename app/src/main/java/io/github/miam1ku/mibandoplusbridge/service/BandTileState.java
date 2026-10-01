// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

/** Quick settings labels. Adding the tile wakes the bridge; it never disconnects. */
public final class BandTileState {
    static final int UNAVAILABLE = 0; // Tile.STATE_UNAVAILABLE
    static final int INACTIVE = 1;    // Tile.STATE_INACTIVE
    static final int ACTIVE = 2;      // Tile.STATE_ACTIVE

    record View(int state, String subtitle) {}

    private BandTileState() {}

    static View of(boolean registered, boolean serviceUp, boolean connected, int battery) {
        if (!registered) return new View(UNAVAILABLE, "未添加");
        if (serviceUp && connected) {
            if (battery >= 0 && battery <= 100) return new View(ACTIVE, "已连接 · " + battery + "%");
            return new View(ACTIVE, "已连接");
        }
        if (serviceUp) return new View(INACTIVE, "连接中");
        return new View(INACTIVE, "未连接");
    }

    enum Action { WAKE, OPEN_APP }

    static Action click(boolean mayWake) {
        return mayWake ? Action.WAKE : Action.OPEN_APP;
    }
}
