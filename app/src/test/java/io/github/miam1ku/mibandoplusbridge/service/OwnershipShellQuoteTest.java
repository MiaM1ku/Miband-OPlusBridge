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

    @Test public void zenCommandUsesTheNotificationService() {
        assertEquals("cmd notification set_dnd off", OwnershipController.zenCommand(false));
        assertEquals("cmd notification set_dnd priority", OwnershipController.zenCommand(true));
        assertEquals("cmd notification allow_dnd io.github.miam1ku.mibandoplusbridge",
                OwnershipController.allowDndCommand());
    }
}
