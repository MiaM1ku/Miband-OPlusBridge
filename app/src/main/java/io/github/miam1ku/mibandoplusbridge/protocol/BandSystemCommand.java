// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Band system commands. Find-phone is type 2 subtype 17, not the find-watch ack on 18. */
public final class BandSystemCommand {
    public enum FindPhone { START, STOP }

    private BandSystemCommand() {}

    /** Absent {@code findDevice} is proto2 0 via {@code getFindDevice()}, so it starts the ring. */
    public static FindPhone findPhone(XiaomiProto.Command command) {
        if (command == null || command.getType() != 2 || command.getSubtype() != 17 || !command.hasSystem()) {
            return null;
        }
        return command.getSystem().getFindDevice() == 0 ? FindPhone.START : FindPhone.STOP;
    }

    /** Find-watch is type 2 subtype 18. Zero starts the band ring, one stops it. */
    public static XiaomiProto.Command findWatch(boolean start) {
        return XiaomiProto.Command.newBuilder().setType(2).setSubtype(18)
                .setSystem(XiaomiProto.System.newBuilder().setFindDevice(start ? 0 : 1))
                .build();
    }
}
