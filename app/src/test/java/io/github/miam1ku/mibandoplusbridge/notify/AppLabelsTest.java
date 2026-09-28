// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class AppLabelsTest {
    @Test public void packageIdIsNotAnApplicationName() {
        assertEquals("", AppLabels.prefer("com.tencent.mm", "com.tencent.mm"));
        assertEquals("", AppLabels.prefer("com.tencent.mm", "  "));
        assertEquals("微信", AppLabels.prefer("com.tencent.mm", "微信"));
    }
}
