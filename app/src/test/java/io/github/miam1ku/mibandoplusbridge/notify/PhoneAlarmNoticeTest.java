// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.app.Notification;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PhoneAlarmNoticeTest {
    @Test public void onlyTheRingingClockForegroundNotificationAlertsTheBand() {
        assertTrue(PhoneAlarmNotice.ringing(PhoneAlarmNotice.CLOCK,
                Notification.FLAG_FOREGROUND_SERVICE, "com.oplus.alarmclock.channel", 7, false));
        assertTrue(PhoneAlarmNotice.ringing(PhoneAlarmNotice.CLOCK,
                0, "com.oplus.alarmclock.channel", Integer.MIN_VALUE, false));
        assertFalse(PhoneAlarmNotice.ringing(PhoneAlarmNotice.CLOCK,
                Notification.FLAG_ONGOING_EVENT, "com.oplus.alarmclock.channel", 4, false));
        assertFalse(PhoneAlarmNotice.ringing("com.tencent.mobileqq",
                Notification.FLAG_FOREGROUND_SERVICE, "com.oplus.alarmclock.channel", 1, true));
        assertFalse(PhoneAlarmNotice.ringing(PhoneAlarmNotice.CLOCK,
                Notification.FLAG_FOREGROUND_SERVICE, "clock_widget", 1, false));
    }
}
