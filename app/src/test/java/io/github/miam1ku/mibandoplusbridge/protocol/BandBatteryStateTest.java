// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import static org.junit.Assert.*;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;

public class BandBatteryStateTest {
    private static XiaomiProto.Command response(XiaomiProto.Battery.Builder battery) {
        return XiaomiProto.Command.newBuilder().setType(2).setSubtype(1)
                .setSystem(XiaomiProto.System.newBuilder()
                        .setPower(XiaomiProto.Power.newBuilder().setBattery(battery))).build();
    }

    @Test public void levelOnlyResponseKeepsChargerUnknown() throws Exception {
        // Wire round trip reproduces an older peer sending just the optional level field.
        var wire = response(XiaomiProto.Battery.newBuilder().setLevel(67)).toByteArray();
        var state = BandBatteryState.parse(XiaomiProto.Command.parseFrom(wire));
        assertEquals(67, state.level());
        assertNull(state.chargerState());
        assertNull(state.charging());
    }

    @Test public void zeroAndFutureStatusKeepVerifiedPercentage() {
        for (int value : new int[] {0, 4, 255, -1}) {
            var state = BandBatteryState.parse(response(XiaomiProto.Battery.newBuilder().setLevel(81).setState(value)));
            assertEquals(81, state.level());
            assertNull(state.chargerState());
            assertNull(state.charging());
        }
    }

    @Test public void knownStatusesHaveExplicitChargingSemantics() {
        for (int value = 1; value <= 3; value++) {
            var state = BandBatteryState.parse(response(XiaomiProto.Battery.newBuilder().setLevel(100).setState(value)));
            assertEquals(Integer.valueOf(value), state.chargerState());
            assertEquals(Boolean.valueOf(value == 1), state.charging());
        }
    }

    @Test public void validatesPresenceAndRangeIndependentlyOfStatus() {
        assertEquals("BATTERY_MISSING", assertThrows(IllegalArgumentException.class,
                () -> BandBatteryState.parse(XiaomiProto.Command.newBuilder().setType(2).setSubtype(1).build())).getMessage());
        assertEquals("BATTERY_VALUE_INVALID", assertThrows(IllegalArgumentException.class,
                () -> BandBatteryState.parse(response(XiaomiProto.Battery.newBuilder().setState(1)))).getMessage());
        for (int value : new int[] {-1, 101, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> BandBatteryState.parse(response(XiaomiProto.Battery.newBuilder().setLevel(value))));
            assertThrows(IllegalArgumentException.class, () -> new BandBatteryState(value, null));
        }
        assertEquals(0, BandBatteryState.parse(response(XiaomiProto.Battery.newBuilder().setLevel(0))).level());
        assertEquals(100, new BandBatteryState(100, null).level());
    }
}
