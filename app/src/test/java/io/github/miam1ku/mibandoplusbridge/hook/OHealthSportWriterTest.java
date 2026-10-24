// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class OHealthSportWriterTest {
    @Test
    public void firstRideUsesTheSessionEnd() {
        assertEquals(1_000L, OHealthSportWriter.nextSlot(1_000L, "", 0, 1, 0));
    }

    @Test
    public void aSummaryImportedBeforeItsTrackGetsANewEnd() {
        assertEquals(1_001L, OHealthSportWriter.nextSlot(1_000L, "1:0:u6", 0, 1, 63_082));
    }

    @Test
    public void theSameTrackStaysOnTheEndAlreadyConfirmed() {
        assertEquals(1_001L, OHealthSportWriter.nextSlot(1_000L, "1:63082:1001:u7", 1_001L, 1, 63_082));
    }

    @Test
    public void aFailedReplaceTriesTheNextMillisecond() {
        assertEquals(1_002L, OHealthSportWriter.nextSlot(1_000L, "1:0:u6", 1_001L, 1, 63_082));
    }
}
