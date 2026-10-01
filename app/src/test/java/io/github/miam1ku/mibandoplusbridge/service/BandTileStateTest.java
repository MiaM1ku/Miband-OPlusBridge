// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class BandTileStateTest {
    @Test public void unregisteredTileIsUnavailable() {
        BandTileState.View view = BandTileState.of(false, true, true, 40);
        assertEquals(BandTileState.UNAVAILABLE, view.state());
        assertEquals("未添加", view.subtitle());
    }

    @Test public void connectedBatteryIsShown() {
        BandTileState.View view = BandTileState.of(true, true, true, 40);
        assertEquals(BandTileState.ACTIVE, view.state());
        assertEquals("已连接 · 40%", view.subtitle());
        assertEquals("已连接 · 0%", BandTileState.of(true, true, true, 0).subtitle());
        assertEquals("已连接 · 100%", BandTileState.of(true, true, true, 100).subtitle());
    }

    @Test public void connectedWithoutAReadingHasNoPercent() {
        BandTileState.View missing = BandTileState.of(true, true, true, -1);
        assertEquals(BandTileState.ACTIVE, missing.state());
        assertEquals("已连接", missing.subtitle());
        assertEquals("已连接", BandTileState.of(true, true, true, 101).subtitle());
    }

    @Test public void runningButNotConnectedIsConnecting() {
        BandTileState.View view = BandTileState.of(true, true, false, 40);
        assertEquals(BandTileState.INACTIVE, view.state());
        assertEquals("连接中", view.subtitle());
    }

    @Test public void stoppedServiceIsDisconnected() {
        BandTileState.View view = BandTileState.of(true, false, true, 40);
        assertEquals(BandTileState.INACTIVE, view.state());
        assertEquals("未连接", view.subtitle());
    }

    @Test public void clickWakesOnlyWhenTheBridgeMayStart() {
        assertEquals(BandTileState.Action.WAKE, BandTileState.click(true));
        assertEquals(BandTileState.Action.OPEN_APP, BandTileState.click(false));
    }
}
