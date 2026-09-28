// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import org.junit.Test;
import static io.github.miam1ku.mibandoplusbridge.notify.CallPresentation.Kind.ACTIVE;
import static io.github.miam1ku.mibandoplusbridge.notify.CallPresentation.Kind.INCOMING;
import static io.github.miam1ku.mibandoplusbridge.notify.CallPresentation.Kind.OUTGOING;
import static io.github.miam1ku.mibandoplusbridge.notify.CallPresentation.Kind.UNKNOWN;
import static io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.CALL_ACTIVE;
import static io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.CALL_INCOMING;
import static io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.CALL_OUTGOING;
import static org.junit.Assert.assertEquals;
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

    @Test public void colorOsDialingIsOutgoingAndARunningTimerIsActive() {
        assertEquals(OUTGOING, CallPresentation.kind(false, false, 0, "正在呼叫", "张三"));
        assertEquals(OUTGOING, CallPresentation.kind(false, false, 0, "正在拨号", "10086"));
        assertEquals(OUTGOING, CallPresentation.kind(false, false, 0, "等待接听", "张三"));
        assertEquals(OUTGOING, CallPresentation.kind(false, false, 0, "Calling", "Ada"));
        assertEquals(INCOMING, CallPresentation.kind(false, false, 0, "来电", "张三"));
        assertEquals(INCOMING, CallPresentation.kind(false, false, 0, "Incoming call", "Ada"));
        assertEquals(ACTIVE, CallPresentation.kind(false, false, 0, "正在通话", "张三"));
        assertEquals(ACTIVE, CallPresentation.kind(false, false, 0, "保持通话", "张三"));
        assertEquals(ACTIVE, CallPresentation.kind(false, true, 0, "", "张三"));
        assertEquals(UNKNOWN, CallPresentation.kind(false, false, 0, "", "张三"));
        assertEquals(INCOMING, CallPresentation.kind(false, false, 0, "{\"text\":\"来电\"}", "张三"));
        assertEquals(0, CallPresentation.wire(UNKNOWN));
        assertEquals(OUTGOING, CallPresentation.kind(false, false, 2, "", "张三"));
        assertEquals(INCOMING, CallPresentation.kind(false, false, 1, "", "张三"));
        assertEquals(CALL_OUTGOING, CallPresentation.wire(OUTGOING));
        assertEquals(CALL_ACTIVE, CallPresentation.wire(ACTIVE));
        assertEquals(CALL_INCOMING, CallPresentation.wire(INCOMING));
    }

    @Test public void stateCopyIsNotUsedAsTheCallerName() {
        assertEquals("张三", CallPresentation.displayName("张三", OUTGOING));
        assertEquals("去电", CallPresentation.displayName("正在呼叫", OUTGOING));
        assertEquals("通话中", CallPresentation.displayName("", ACTIVE));
        assertEquals("来电", CallPresentation.displayName("来电", INCOMING));
    }

    @Test public void seedlingChronometerNodeMarksTheCallConnected() {
        assertTrue(CallPresentation.chronometerCard("{\"desc\":\"Chronometer\",\"started\":true}"));
        assertFalse(CallPresentation.chronometerCard("{\"desc\":\"正在呼叫\"}"));
        assertFalse(CallPresentation.chronometerCard(null));
    }
}
