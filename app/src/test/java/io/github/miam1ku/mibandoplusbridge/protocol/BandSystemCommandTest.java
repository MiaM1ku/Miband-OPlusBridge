// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class BandSystemCommandTest {
    @Test public void findDeviceZeroStarts() {
        var command = system(17, XiaomiProto.System.newBuilder().setFindDevice(0));
        assertEquals(BandSystemCommand.FindPhone.START, BandSystemCommand.findPhone(command));
    }

    @Test public void findDeviceOneStops() {
        var command = system(17, XiaomiProto.System.newBuilder().setFindDevice(1));
        assertEquals(BandSystemCommand.FindPhone.STOP, BandSystemCommand.findPhone(command));
    }

    @Test public void findWatchSubtypeIsIgnored() {
        var command = system(18, XiaomiProto.System.newBuilder().setFindDevice(0));
        assertNull(BandSystemCommand.findPhone(command));
    }

    @Test public void missingSystemIsIgnored() {
        var command = XiaomiProto.Command.newBuilder().setType(2).setSubtype(17).build();
        assertNull(BandSystemCommand.findPhone(command));
    }

    @Test public void findWatchUsesSubtypeEighteen() {
        var start = BandSystemCommand.findWatch(true);
        var stop = BandSystemCommand.findWatch(false);
        assertEquals(2, start.getType());
        assertEquals(18, start.getSubtype());
        assertEquals(0, start.getSystem().getFindDevice());
        assertEquals(1, stop.getSystem().getFindDevice());
    }

    private static XiaomiProto.Command system(int subtype, XiaomiProto.System.Builder system) {
        return XiaomiProto.Command.newBuilder().setType(2).setSubtype(subtype).setSystem(system).build();
    }
}
