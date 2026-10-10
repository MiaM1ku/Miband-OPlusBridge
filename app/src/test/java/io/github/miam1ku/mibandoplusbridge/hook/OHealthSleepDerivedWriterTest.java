// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public final class OHealthSleepDerivedWriterTest {
    @Test public void summarizeComputesMinMaxMean() {
        OHealthSleepDerivedWriter.Summary summary =
                OHealthSleepDerivedWriter.summarize(new int[] {50, 60, 70, 0, 0}, 3);
        assertEquals(3, summary.count());
        assertEquals(50, summary.min());
        assertEquals(70, summary.max());
        assertEquals(60, summary.mean());
    }

    @Test public void summarizeRoundsTheMean() {
        OHealthSleepDerivedWriter.Summary summary =
                OHealthSleepDerivedWriter.summarize(new int[] {50, 51}, 2);
        assertEquals(51, summary.mean());
    }

    @Test public void summarizeEmptyIsNull() {
        assertNull(OHealthSleepDerivedWriter.summarize(new int[4], 0));
    }

    @Test public void summarizeSleepBreathScaleAndBounds() {
        // Values in scaled 10x bpm format: 142 means 14.2 bpm
        OHealthSleepDerivedWriter.Summary summary =
                OHealthSleepDerivedWriter.summarize(new int[] {142, 148, 155}, 3);
        assertEquals(3, summary.count());
        assertEquals(142, summary.min());
        assertEquals(155, summary.max());
        assertEquals(148, summary.mean());

        // Boundary valid breath rate values (60..500)
        OHealthSleepDerivedWriter.Summary bounds =
                OHealthSleepDerivedWriter.summarize(new int[] {60, 500}, 2);
        assertEquals(2, bounds.count());
        assertEquals(60, bounds.min());
        assertEquals(500, bounds.max());
        assertEquals(280, bounds.mean());
    }

    @Test public void tableConstantsMatchOppoContract() {
        assertEquals(1048, OHealthSleepWriter.TABLE_BREATH_RATE);
        assertEquals(1049, OHealthSleepDerivedWriter.TABLE_INDEX);
        assertEquals(1071, OHealthSleepDerivedWriter.TABLE_HR_STAT);
        assertEquals(1052, OHealthSleepDerivedWriter.TABLE_DAY_STAT);
        assertEquals(1010, OHealthSleepWriter.TABLE_SLEEP);
        assertEquals(1011, OHealthSleepWriter.TABLE_STAT);
    }
}
