// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Type 17 subtype 16 phone-alarm alert, dismiss, and snooze. Every connected model gets the same packet. */
public final class BandAlarmCommand {
    private BandAlarmCommand() {}

    public static XiaomiProto.Command operation(int opCode, int alarmId, int alertTimeSec, String label) {
        if (opCode != 0 && opCode != 1 && opCode != 2) {
            throw new IllegalArgumentException("PHONE_ALARM_OP");
        }
        if (alarmId < 0) throw new IllegalArgumentException("PHONE_ALARM_ID");
        var target = XiaomiProto.PhoneAlarmTarget.newBuilder()
                .setId(alarmId)
                .setLabel(label == null ? "" : label);
        if (alertTimeSec >= 0) target.setAlertTime(alertTimeSec);
        return XiaomiProto.Command.newBuilder().setType(17).setSubtype(16)
                .setSchedule(XiaomiProto.Schedule.newBuilder().setPhoneAlarmOperation(
                        XiaomiProto.PhoneAlarmOperation.newBuilder()
                                .setOpCode(opCode)
                                .setPhoneAlarm(target)))
                .build();
    }
}
