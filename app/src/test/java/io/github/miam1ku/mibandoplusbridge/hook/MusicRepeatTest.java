// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MusicRepeatTest {
    @Test public void identicalForcedPublishIsDroppedOnlyInsideTheWindow() {
        assertFalse(MusicRepeat.suppress(true, false, 1));
        assertTrue(MusicRepeat.suppress(false, true, 5_000_000_000L));
        assertTrue(MusicRepeat.suppress(true, true, 2_000_000L));
        assertFalse(MusicRepeat.suppress(true, true, MusicRepeat.FORCE_WINDOW_NANOS));
        assertFalse(MusicRepeat.suppress(true, true, -1));
    }
}
