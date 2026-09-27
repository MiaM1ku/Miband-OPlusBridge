// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BandClockCommandTest {
    @Test public void sameInstantUsesLocalDateAndExactDstOffset() {
        Instant instant = Instant.parse("2026-07-01T23:59:59.321Z");
        var shanghai = BandClockCommand.at(instant, ZoneId.of("Asia/Shanghai"), true);
        var china = shanghai.getSystem().getClock();
        assertEquals(2, shanghai.getType());
        assertEquals(3, shanghai.getSubtype());
        assertEquals(2026, china.getDate().getYear());
        assertEquals(7, china.getDate().getMonth());
        assertEquals(2, china.getDate().getDay());
        assertEquals(7, china.getTime().getHour());
        assertEquals(59, china.getTime().getMinute());
        assertEquals(321, china.getTime().getMillisecond());
        assertEquals(32, china.getTimezone().getZoneOffset());
        assertEquals(0, china.getTimezone().getDstOffset());
        assertFalse(china.getIsNot24Hour());
        var newYork = BandClockCommand.at(instant, ZoneId.of("America/New_York"), false)
                .getSystem().getClock();
        assertEquals(19, newYork.getTime().getHour());
        assertEquals(-20, newYork.getTimezone().getZoneOffset());
        assertEquals(4, newYork.getTimezone().getDstOffset());
        assertTrue(newYork.getIsNot24Hour());
    }
}
