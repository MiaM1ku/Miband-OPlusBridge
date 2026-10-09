// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HealthPolicyTest {
    @Test public void unknownPolicyAllowsAndAClosedMainSwitchDoesNot() {
        assertNull(HealthPolicy.blockReason(false, false, false, Set.of(), Set.of(), null,
                List.of(), "com.tencent.mm", true, false));
        assertEquals("health-main", HealthPolicy.blockReason(true, false, true, Set.of("com.tencent.mm"),
                Set.of(), Set.of(), List.of(), "com.tencent.mm", false, true));
    }

    @Test public void screenOnPushBlocksOnlyAnUnlockedLitScreen() {
        assertEquals("health-screen", HealthPolicy.blockReason(true, true, false, Set.of("com.tencent.mm"),
                Set.of(), Set.of(), List.of(), "com.tencent.mm", true, false));
        assertNull(HealthPolicy.blockReason(true, true, false, Set.of("com.tencent.mm"),
                Set.of(), Set.of(), List.of(), "com.tencent.mm", true, true));
        assertNull(HealthPolicy.blockReason(true, true, false, Set.of("com.tencent.mm"),
                Set.of(), Set.of(), List.of(), "com.tencent.mm", false, false));
    }

    @Test public void aRowBeatsTheDefaultAndSmsFollowsTheFirstSmsRow() {
        assertNull(HealthPolicy.blockReason(true, true, true, Set.of("com.tencent.mm"), Set.of(),
                Set.of(), List.of(), "com.tencent.mm", true, false));
        assertEquals("health-package", HealthPolicy.blockReason(true, true, true, Set.of(),
                Set.of("com.tencent.mm"), Set.of("com.tencent.mm"), List.of(), "com.tencent.mm", true, false));
        assertEquals("health-package", HealthPolicy.blockReason(true, true, true, Set.of(), Set.of(),
                Set.of("com.android.mms"), List.of("com.android.mms", "com.google.android.apps.messaging"),
                "com.google.android.apps.messaging", true, false));
        assertNull(HealthPolicy.blockReason(true, true, true, Set.of("com.android.mms"), Set.of(),
                Set.of(), List.of("com.android.mms", "com.google.android.apps.messaging"),
                "com.google.android.apps.messaging", true, false));
        assertNull(HealthPolicy.blockReason(true, true, true, Set.of(), Set.of(),
                Set.of("com.tencent.mm"), List.of(), "com.tencent.mm", true, false));
        assertEquals("health-package", HealthPolicy.blockReason(true, true, true, Set.of(), Set.of(),
                null, List.of(), "com.example.missing", true, false));
    }
}
