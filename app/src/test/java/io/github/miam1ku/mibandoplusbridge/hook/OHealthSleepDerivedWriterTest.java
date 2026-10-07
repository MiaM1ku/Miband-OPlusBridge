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
}
