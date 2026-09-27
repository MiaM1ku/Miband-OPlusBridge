// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneDndTest {
    @Test public void onlyRealDndFiltersBlockPushes() {
        assertFalse(PhoneDnd.blocksNotifications(PhoneDnd.UNKNOWN));
        assertFalse(PhoneDnd.blocksNotifications(PhoneDnd.ALL));
        assertTrue(PhoneDnd.blocksNotifications(PhoneDnd.PRIORITY));
        assertTrue(PhoneDnd.blocksNotifications(PhoneDnd.NONE));
        assertTrue(PhoneDnd.blocksNotifications(PhoneDnd.ALARMS));
    }
}
