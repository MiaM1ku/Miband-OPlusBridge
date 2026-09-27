/* Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo, LuK1337, Yoran Vulker.
 * Clock wire semantics adapted from Gadgetbridge XiaomiSystemService.java at
 * commit 75f923904f8504b03fabdee0987fd1c269a92278.
 */
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Builds the observed type 2/subtype 3 clock update with explicit quarter-hour offsets. */
public final class BandClockCommand {
    private BandClockCommand() {}

    public static XiaomiProto.Command at(Instant instant, ZoneId zone, boolean use24Hour) {
        if (instant == null || zone == null) throw new IllegalArgumentException("CLOCK_SOURCE_MISSING");
        ZonedDateTime local = instant.atZone(zone);
        int standard = zone.getRules().getStandardOffset(instant).getTotalSeconds();
        int daylight = local.getOffset().getTotalSeconds() - standard;
        if (standard % 900 != 0 || daylight % 900 != 0) {
            throw new IllegalArgumentException("TIMEZONE_OFFSET_UNSUPPORTED");
        }
        var clock = XiaomiProto.Clock.newBuilder()
                .setDate(XiaomiProto.Date.newBuilder().setYear(local.getYear())
                        .setMonth(local.getMonthValue()).setDay(local.getDayOfMonth()))
                .setTime(XiaomiProto.Time.newBuilder().setHour(local.getHour())
                        .setMinute(local.getMinute()).setSecond(local.getSecond())
                        .setMillisecond(local.getNano() / 1_000_000))
                .setTimezone(XiaomiProto.TimeZone.newBuilder().setZoneOffset(standard / 900)
                        .setDstOffset(daylight / 900).setName(zone.getId()))
                .setIsNot24Hour(!use24Hour);
        return XiaomiProto.Command.newBuilder().setType(2).setSubtype(3)
                .setSystem(XiaomiProto.System.newBuilder().setClock(clock)).build();
    }
}
