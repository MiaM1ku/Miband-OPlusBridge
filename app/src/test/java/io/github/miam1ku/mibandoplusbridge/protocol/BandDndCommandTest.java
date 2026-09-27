// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BandDndCommandTest {
    @Test public void enabledAndDisabledUseTheWatchStatusCodes() {
        var on = BandDndCommand.state(true);
        var off = BandDndCommand.state(false);
        assertEquals(2, on.getType());
        assertEquals(23, on.getSubtype());
        assertEquals(0, on.getSystem().getDndStatus().getStatus());
        assertEquals(2, off.getSystem().getDndStatus().getStatus());
    }
}
