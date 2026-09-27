// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class WeatherConditionMapTest {
    @Test public void verifiedOHealthCategoriesMapToXiaomiConditions() {
        int[] expected = {
                -1, 7, 3, 3, 3, 3, 7, 7, 8, 9, 9, 9, 10, 10, 12, 12, 12, 19,
                5, 5, 5, -1, 4, 4, 4, 4, 33, 13, 13, 14, 26, 15, 27, 16, 28, 17,
                17, 6, 18, 18, 18, 32, 32, 32, 32, 29, 29, 29, 20, 31, 18, 18, 18, 18,
                0, 0, 1, 1, 1, 2, 30, 30, 30, 30, 30, 30, 30, 30, 0, 1, -1, -1
        };
        for (int code = 0; code < expected.length; code++) {
            assertEquals("OHealth code " + code, expected[code], WeatherConditionMap.fromOHealth(code));
        }
    }

    @Test public void outOfRangeCodesNeverBecomeClearSkies() {
        for (int code : new int[] {Integer.MIN_VALUE, -1, 72, 999, Integer.MAX_VALUE}) {
            assertEquals(-1, WeatherConditionMap.fromOHealth(code));
        }
    }
}
