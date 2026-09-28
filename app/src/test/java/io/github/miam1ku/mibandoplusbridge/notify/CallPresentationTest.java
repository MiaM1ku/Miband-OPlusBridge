// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CallPresentationTest {
    @Test public void dialingCopyStaysOutgoingUntilTheCallIsConnected() {
        assertFalse(CallPresentation.connected(false, "正在呼叫", "张三"));
        assertFalse(CallPresentation.connected(false, "正在拨号", "10086"));
        assertTrue(CallPresentation.connected(true, "正在呼叫", "张三"));
        assertTrue(CallPresentation.connected(false, "00:12", "张三"));
        assertTrue(CallPresentation.connected(false, "通话中", "张三"));
        assertTrue(CallPresentation.connected(false, "", "1:02:03"));
    }
}
