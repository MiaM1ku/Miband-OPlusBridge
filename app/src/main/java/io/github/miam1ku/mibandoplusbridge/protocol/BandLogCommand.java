// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Xiaomi report-data commands used by the full device-log exporter. */
public final class BandLogCommand {
    public static final int REQUEST_SUBTYPE = 80;
    public static final int RESULT_SUBTYPE = 81;

    private BandLogCommand() {}

    /** System 2/80 with ReportData.Type.DEVICE_LOG. */
    public static XiaomiProto.Command exportDeviceLog() {
        return XiaomiProto.Command.newBuilder()
                .setType(2)
                .setSubtype(REQUEST_SUBTYPE)
                .setSystem(XiaomiProto.System.newBuilder()
                        .setReportData(XiaomiProto.ReportData.newBuilder()
                                .setType(XiaomiProto.ReportData.Type.DEVICE_LOG)))
                .build();
    }

    /**
     * Returns the device status from a 2/81 result, or -1 when the command is a different
     * response. SUCCESS is 0.
     */
    public static int resultStatus(XiaomiProto.Command command) {
        if (command == null || command.getType() != 2 || !command.hasSubtype()
                || command.getSubtype() != RESULT_SUBTYPE || !command.hasSystem()
                || !command.getSystem().hasReportDataResult()) {
            return -1;
        }
        XiaomiProto.ReportData.Result result = command.getSystem().getReportDataResult();
        return result.getType() == XiaomiProto.ReportData.Type.DEVICE_LOG
                ? result.getStatus().getNumber() : -1;
    }
}
