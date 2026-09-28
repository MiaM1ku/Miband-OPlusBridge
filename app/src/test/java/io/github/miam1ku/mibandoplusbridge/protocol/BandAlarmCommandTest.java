// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BandAlarmCommandTest {
    @Test public void alertCarriesOpIdTimeAndLabel() {
        var command = BandAlarmCommand.operation(0, 7, 1_700_000_000, "起床");
        var alarm = command.getSchedule().getPhoneAlarmOperation().getPhoneAlarm();
        assertEquals(17, command.getType());
        assertEquals(16, command.getSubtype());
        assertEquals(0, command.getSchedule().getPhoneAlarmOperation().getOpCode());
        assertEquals(7, alarm.getId());
        assertEquals(1_700_000_000, alarm.getAlertTime());
        assertEquals("起床", alarm.getLabel());
    }

    @Test public void negativeTimeOmitsAlertTimeAndNullLabelIsEmpty() {
        var alarm = BandAlarmCommand.operation(1, 7, -1, null)
                .getSchedule().getPhoneAlarmOperation().getPhoneAlarm();
        assertEquals("", alarm.getLabel());
        assertFalse(alarm.hasAlertTime());
    }

    @Test public void unknownOpIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BandAlarmCommand.operation(9, 1, 0, ""));
    }
}
