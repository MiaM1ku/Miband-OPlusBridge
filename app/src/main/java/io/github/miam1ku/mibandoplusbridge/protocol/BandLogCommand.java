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
     * response. SUCCESS is 0. Accepts both DEVICE_LOG and DUMP_LOG because some firmwares
     * answer a DEVICE_LOG export with DUMP_LOG.
     */
    public static int resultStatus(XiaomiProto.Command command) {
        if (command == null || command.getType() != 2 || !command.hasSubtype()
                || command.getSubtype() != RESULT_SUBTYPE || !command.hasSystem()
                || !command.getSystem().hasReportDataResult()) {
            return -1;
        }
        XiaomiProto.ReportData.Result result = command.getSystem().getReportDataResult();
        XiaomiProto.ReportData.Type type = result.getType();
        return (type == XiaomiProto.ReportData.Type.DEVICE_LOG
                || type == XiaomiProto.ReportData.Type.DUMP_LOG)
                ? result.getStatus().getNumber() : -1;
    }

    /** True when the band rejected 2/80 or 2/81 with a non-zero top-level protobuf status. */
    public static boolean isProtocolRejection(XiaomiProto.Command command) {
        return command != null && command.getType() == 2 && command.hasSubtype()
                && (command.getSubtype() == REQUEST_SUBTYPE || command.getSubtype() == RESULT_SUBTYPE)
                && command.hasStatus() && command.getStatus() != 0;
    }

    /** Maps a non-zero ReportData.Status number to a stable error code. */
    public static String statusCode(int status) {
        return switch (status) {
            case 1 -> "DEVICE_LOG_NO_DATA";
            case 2 -> "DEVICE_LOG_DIRECT_UPLOAD_UNSUPPORTED";
            case 3 -> "DEVICE_LOG_DEVICE_BUSY";
            case 10 -> "DEVICE_LOG_DEVICE_REJECTED";
            default -> "DEVICE_LOG_RESULT_INVALID";
        };
    }
}
