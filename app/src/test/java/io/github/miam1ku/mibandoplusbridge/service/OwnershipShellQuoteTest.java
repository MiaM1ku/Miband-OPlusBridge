// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class OwnershipShellQuoteTest {
    @Test public void quotesRedirectsForSuDashC() {
        assertEquals("'pm disable-user --user 0 com.mi.health'",
                OwnershipController.shellQuote("pm disable-user --user 0 com.mi.health"));
    }

    @Test public void wrapsPlainCommands() {
        assertEquals("'id -u'", OwnershipController.shellQuote("id -u"));
    }

    @Test public void zenCommandIsOnlyTheGlobalSwitch() {
        assertEquals("settings put global zen_mode 0", OwnershipController.zenCommand(false));
        assertEquals("settings put global zen_mode 1", OwnershipController.zenCommand(true));
    }
}
