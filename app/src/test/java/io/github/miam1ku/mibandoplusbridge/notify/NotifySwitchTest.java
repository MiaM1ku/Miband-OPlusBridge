// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NotifySwitchTest {
    @Test public void booleanAndNumericSwitchesBothCount() {
        assertTrue(NotifySwitch.on(Boolean.TRUE));
        assertFalse(NotifySwitch.on(Boolean.FALSE));
        assertTrue(NotifySwitch.on(1));
        assertFalse(NotifySwitch.on(0));
        assertFalse(NotifySwitch.on(null));
        assertFalse(NotifySwitch.on("true"));
    }
}
