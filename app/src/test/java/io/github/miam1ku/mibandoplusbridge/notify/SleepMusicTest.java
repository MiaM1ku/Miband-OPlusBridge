// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SleepMusicTest {
    @Test public void firstSampleIsBaselineAndOnlyAwakeToAsleepPauses() {
        SleepMusic gate = new SleepMusic();
        assertEquals(SleepMusic.Effect.BASELINE, gate.observe(true).effect());
        assertEquals(SleepMusic.Effect.UNCHANGED, gate.observe(true).effect());
        assertEquals(SleepMusic.Effect.WOKE, gate.observe(false).effect());
        assertEquals(SleepMusic.Effect.UNCHANGED, gate.observe(false).effect());
        SleepMusic.Report pause = gate.observe(true);
        assertEquals(SleepMusic.Effect.PAUSE, pause.effect());
        assertEquals(SleepMusic.Effect.UNCHANGED, gate.observe(true).effect());
        assertEquals(0, pause.generation());
    }

    @Test public void resetDropsTheEdgeUntilTheNextSample() {
        SleepMusic gate = new SleepMusic();
        gate.observe(false);
        assertEquals(1, gate.reset());
        SleepMusic.Report again = gate.observe(true);
        assertEquals(SleepMusic.Effect.BASELINE, again.effect());
        assertEquals(1, again.generation());
        assertEquals(1, gate.generation());
    }

    @Test public void basicAndPushedReportsUseTheWireValues() {
        assertEquals(Boolean.TRUE, SleepMusic.asleep(basic(true)));
        assertEquals(Boolean.FALSE, SleepMusic.asleep(basic(false)));
        assertEquals(Boolean.TRUE, SleepMusic.asleep(pushed(1)));
        assertEquals(Boolean.FALSE, SleepMusic.asleep(pushed(2)));
        assertNull(SleepMusic.asleep(pushed(0)));
        assertNull(SleepMusic.asleep(pushed(3)));
        assertNull(SleepMusic.asleep(XiaomiProto.Command.newBuilder().setType(2).setSubtype(79)
                .setSystem(XiaomiProto.System.newBuilder().setDeviceState(
                        XiaomiProto.DeviceState.newBuilder().setWearingState(1))).build()));
        assertNull(SleepMusic.asleep(basic(true).toBuilder().setStatus(3).build()));
        assertNull(SleepMusic.asleep(XiaomiProto.Command.newBuilder().setType(2).setSubtype(1).build()));
        assertNull(SleepMusic.asleep(null));
        XiaomiProto.Command query = SleepMusic.query();
        assertEquals(2, query.getType());
        assertEquals(78, query.getSubtype());
        assertFalse(query.hasSystem());
    }

    private static XiaomiProto.Command basic(boolean asleep) {
        return XiaomiProto.Command.newBuilder().setType(2).setSubtype(78)
                .setSystem(XiaomiProto.System.newBuilder().setBasicDeviceState(
                        XiaomiProto.BasicDeviceState.newBuilder()
                                .setIsCharging(false).setIsWorn(true).setIsUserAsleep(asleep)))
                .build();
    }

    private static XiaomiProto.Command pushed(int sleepState) {
        return XiaomiProto.Command.newBuilder().setType(2).setSubtype(79)
                .setSystem(XiaomiProto.System.newBuilder().setDeviceState(
                        XiaomiProto.DeviceState.newBuilder().setSleepState(sleepState)))
                .build();
    }
}
