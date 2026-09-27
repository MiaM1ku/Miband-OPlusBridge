// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class UpdateCheckerTest {
    @Test public void parseLsposedTag() {
        UpdateChecker.Parsed parsed = UpdateChecker.parseTag("2-0.2.0", "ignored");
        assertEquals(2, parsed.code());
        assertEquals("0.2.0", parsed.name());
    }

    @Test public void parseVPrefix() {
        UpdateChecker.Parsed parsed = UpdateChecker.parseTag("v0.2.0", "fallback");
        assertEquals(0, parsed.code());
        assertEquals("0.2.0", parsed.name());
    }
}
