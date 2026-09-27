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
}
