// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MacIdsTest {
    @Test public void sameAddressMatchesAcrossSeparatorAndCase() {
        assertEquals("aabbccddeeff", MacIds.normalize("AA:BB:CC:DD:EE:FF"));
        assertTrue(MacIds.same("aa:bb:cc:dd:ee:ff", "AA-BB-CC-DD-EE-FF"));
        assertTrue(MacIds.same("aabbccddeeff", "AA:BB:CC:DD:EE:FF"));
        assertFalse(MacIds.same("aa:bb:cc:dd:ee:ff", "aa:bb:cc:dd:ee:f0"));
        assertFalse(MacIds.same("aa:bb:cc:dd:ee:ff", ""));
        assertFalse(MacIds.same("not-a-mac", "not-a-mac"));
    }
}
