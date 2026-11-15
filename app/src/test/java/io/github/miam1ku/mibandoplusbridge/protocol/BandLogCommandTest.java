// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BandLogCommandTest {
    @Test public void exportRequestUsesSystemReportDataOnSubtype80() throws Exception {
        XiaomiProto.Command command = BandLogCommand.exportDeviceLog();
        assertEquals(2, command.getType());
        assertEquals(80, command.getSubtype());
        assertTrue(command.getSystem().hasReportData());
        assertEquals(XiaomiProto.ReportData.Type.DEVICE_LOG,
                command.getSystem().getReportData().getType());
        assertEquals(command, XiaomiProto.Command.parseFrom(command.toByteArray()));
    }

    @Test public void resultStatusReadsDeviceLogResultOnSubtype81() {
        XiaomiProto.Command success = XiaomiProto.Command.newBuilder().setType(2).setSubtype(81)
                .setSystem(XiaomiProto.System.newBuilder().setReportDataResult(
                        XiaomiProto.ReportData.Result.newBuilder()
                                .setType(XiaomiProto.ReportData.Type.DEVICE_LOG)
                                .setStatus(XiaomiProto.ReportData.Status.SUCCESS)))
                .build();
        assertEquals(0, BandLogCommand.resultStatus(success));
        assertEquals(-1, BandLogCommand.resultStatus(
                XiaomiProto.Command.newBuilder().setType(2).setSubtype(80).build()));
    }
}
