// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** A verified percentage plus independently optional charger telemetry. */
public record BandBatteryState(int level, Integer chargerState) {
    public BandBatteryState {
        if (level < 0 || level > 100) throw new IllegalArgumentException("BATTERY_VALUE_INVALID");
        // Official DeviceModelExtKt.updateDeviceStatus accepts charge status 0 alongside a level.
        // Keep absent, zero and future charger values unknown while retaining the verified level.
        if (chargerState != null && (chargerState < 1 || chargerState > 3)) chargerState = null;
    }

    public static BandBatteryState parse(XiaomiProto.Command command) {
        if (command == null || !command.hasSystem() || !command.getSystem().hasPower()
                || !command.getSystem().getPower().hasBattery()) {
            throw new IllegalArgumentException("BATTERY_MISSING");
        }
        var battery = command.getSystem().getPower().getBattery();
        if (!battery.hasLevel()) throw new IllegalArgumentException("BATTERY_VALUE_INVALID");
        return new BandBatteryState(battery.getLevel(), battery.hasState() ? battery.getState() : null);
    }

    /** Null represents unknown; states 2 (idle) and 3 (full) are confirmed false. */
    public Boolean charging() {
        return chargerState == null ? null : Boolean.valueOf(chargerState == 1);
    }
}
