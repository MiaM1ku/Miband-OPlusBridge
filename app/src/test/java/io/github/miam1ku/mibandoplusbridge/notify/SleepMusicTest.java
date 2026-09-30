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

    @Test public void currentSleepFilePausesOnceAndAnAsleepBaselineHoldsThatNight() {
        SleepMusic gate = new SleepMusic();
        long now = 1_000_000_000_000L;
        long start = now - 30 * 60_000L;
        long end = now - 60_000L;
        assertEquals(SleepMusic.Effect.UNCHANGED, gate.currentNight(start, end, now, now).effect());
        assertEquals(SleepMusic.Effect.PAUSE, gate.currentNight(start, end, start, now).effect());
        assertEquals(SleepMusic.Effect.UNCHANGED, gate.currentNight(start, end, start, now).effect());
        assertEquals(SleepMusic.Effect.UNCHANGED,
                gate.currentNight(start, now - SleepMusic.FILE_WINDOW_MS - 1, 0, now).effect());
        long next = start + 24 * 60 * 60_000L;
        assertEquals(SleepMusic.Effect.PAUSE, gate.currentNight(next, next + 60_000L, 0, next + 60_000L).effect());
        SleepMusic held = new SleepMusic();
        assertEquals(SleepMusic.Effect.BASELINE, held.observe(true, now).effect());
        assertEquals(SleepMusic.Effect.UNCHANGED, held.currentNight(start, end, 0, now).effect());
        assertEquals(SleepMusic.Effect.PAUSE, held.currentNight(next, next + 60_000L, 0, next + 60_000L).effect());
        assertEquals(SleepMusic.Effect.BASELINE, new SleepMusic().push(true).effect());
    }

    @Test public void anAwakeReportHoldsTheCurrentNightAndAFreshGateDoesNotPauseIt() {
        SleepMusic gate = new SleepMusic();
        long now = 1_000_000_000_000L;
        long start = now - 30 * 60_000L;
        long end = now - 60_000L;
        assertEquals(SleepMusic.Effect.BASELINE, gate.observe(true, start).effect());
        assertEquals(SleepMusic.Effect.WOKE, gate.observe(false, now).effect());
        assertEquals(SleepMusic.Effect.UNCHANGED, gate.currentNight(start, end, 0, now).effect());
        long next = now + 20 * 60 * 60_000L;
        assertEquals(SleepMusic.Effect.PAUSE, gate.currentNight(next, next + 60_000L, 0, next + 60_000L).effect());
        SleepMusic restarted = new SleepMusic();
        assertEquals(SleepMusic.Effect.UNCHANGED,
                restarted.currentNight(start, end, Long.MAX_VALUE, now).effect());
        assertEquals(SleepMusic.Effect.PAUSE, restarted.currentNight(start, end, start, now).effect());
        SleepMusic stillAwake = new SleepMusic();
        long arm = now;
        assertEquals(SleepMusic.Effect.BASELINE, stillAwake.observe(false, arm).effect());
        assertEquals(SleepMusic.Effect.UNCHANGED, stillAwake.observe(false, arm + 3_600_000L).effect());
        assertEquals(SleepMusic.Effect.PAUSE, stillAwake.currentNight(
                arm + 30 * 60_000L, arm + 90 * 60_000L, arm, arm + 90 * 60_000L).effect());
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
