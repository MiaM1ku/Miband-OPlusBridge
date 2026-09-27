// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Phone DND mirrored onto the band. Status 0 is on, 2 is off. Subtype 23 matches Gadgetbridge. */
public final class BandDndCommand {
    private BandDndCommand() {}

    public static XiaomiProto.Command state(boolean enabled) {
        return XiaomiProto.Command.newBuilder().setType(2).setSubtype(23)
                .setSystem(XiaomiProto.System.newBuilder().setDndStatus(
                        XiaomiProto.DoNotDisturb.newBuilder().setStatus(enabled ? 0 : 2)))
                .build();
    }
}
