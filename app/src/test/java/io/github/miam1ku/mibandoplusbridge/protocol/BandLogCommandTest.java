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

    @Test public void resultStatusReadsDeviceLogAndDumpLogOnSubtype81() {
        XiaomiProto.Command deviceLog = XiaomiProto.Command.newBuilder().setType(2).setSubtype(81)
                .setSystem(XiaomiProto.System.newBuilder().setReportDataResult(
                        XiaomiProto.ReportData.Result.newBuilder()
                                .setType(XiaomiProto.ReportData.Type.DEVICE_LOG)
                                .setStatus(XiaomiProto.ReportData.Status.SUCCESS)))
                .build();
        XiaomiProto.Command dumpLog = XiaomiProto.Command.newBuilder().setType(2).setSubtype(81)
                .setSystem(XiaomiProto.System.newBuilder().setReportDataResult(
                        XiaomiProto.ReportData.Result.newBuilder()
                                .setType(XiaomiProto.ReportData.Type.DUMP_LOG)
                                .setStatus(XiaomiProto.ReportData.Status.BUSY)))
                .build();
        assertEquals(0, BandLogCommand.resultStatus(deviceLog));
        assertEquals(3, BandLogCommand.resultStatus(dumpLog));
        assertEquals("DEVICE_LOG_DEVICE_BUSY", BandLogCommand.statusCode(3));
        assertEquals("DEVICE_LOG_NO_DATA", BandLogCommand.statusCode(1));
        assertEquals("DEVICE_LOG_DIRECT_UPLOAD_UNSUPPORTED", BandLogCommand.statusCode(2));
        assertEquals("DEVICE_LOG_DEVICE_REJECTED", BandLogCommand.statusCode(10));
        assertEquals(-1, BandLogCommand.resultStatus(
                XiaomiProto.Command.newBuilder().setType(2).setSubtype(80).build()));
        assertTrue(BandLogCommand.isProtocolRejection(
                XiaomiProto.Command.newBuilder().setType(2).setSubtype(80).setStatus(5).build()));
    }
}
