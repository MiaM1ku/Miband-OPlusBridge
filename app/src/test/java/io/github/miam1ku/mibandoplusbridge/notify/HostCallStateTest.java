// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import org.junit.Test;
import static io.github.miam1ku.mibandoplusbridge.notify.HostCallState.Action.*;
import static io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.CALL_ACTIVE;
import static io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.CALL_INCOMING;
import static io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.CALL_OUTGOING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HostCallStateTest {
    @Test public void bandOnlySendsIncomingAndDismissesOnceWhenAnswered() {
        HostCallState state = new HostCallState();
        assertEquals(new HostCallState.Decision(SEND_CALL, 0), state.update(false, false, CALL_INCOMING));
        assertEquals(new HostCallState.Decision(NONE, CALL_INCOMING), state.update(false, false, CALL_INCOMING));
        // Answering dismisses the ring without sending CALL_ACTIVE (MISSED_CALL on Band 11).
        assertEquals(new HostCallState.Decision(END_CALL, CALL_INCOMING), state.update(false, false, CALL_ACTIVE));
        // Later hang-up does not send a redundant endCall().
        assertEquals(new HostCallState.Decision(NONE, 0), state.update(false, true, 0));
    }

    @Test public void bandSkipsOutgoingAndFollowUpActiveAndHangup() {
        HostCallState state = new HostCallState();
        assertEquals(new HostCallState.Decision(NONE, 0), state.update(false, false, CALL_OUTGOING));
        assertEquals(new HostCallState.Decision(NONE, 0), state.update(false, false, CALL_ACTIVE));
        assertEquals(new HostCallState.Decision(NONE, 0), state.update(false, true, 0));
    }

    @Test public void bandMissedCallDismissesRingingScreen() {
        HostCallState state = new HostCallState();
        assertEquals(new HostCallState.Decision(SEND_CALL, 0), state.update(false, false, CALL_INCOMING));
        assertEquals(new HostCallState.Decision(END_CALL, CALL_INCOMING), state.update(false, true, 0));
        assertEquals(new HostCallState.Decision(NONE, 0), state.update(false, true, 0));
    }

    @Test public void watchKeepsOutgoingAndActiveReplacementSemantics() {
        HostCallState state = new HostCallState();
        assertEquals(new HostCallState.Decision(SEND_CALL, 0), state.update(true, false, CALL_OUTGOING));
        assertEquals(new HostCallState.Decision(NONE, CALL_OUTGOING), state.update(true, false, CALL_OUTGOING));
        assertEquals(new HostCallState.Decision(REPLACE_WITH_CALL, CALL_OUTGOING), state.update(true, false, CALL_ACTIVE));
        assertEquals(new HostCallState.Decision(END_CALL, CALL_ACTIVE), state.update(true, true, 0));
        assertEquals(new HostCallState.Decision(NONE, 0), state.update(true, true, 0));
    }

    @Test public void blockedCallClearReportsWhetherBandWasShowing() {
        HostCallState state = new HostCallState();
        assertFalse(state.clear());
        state.update(false, false, CALL_INCOMING);
        assertTrue(state.clear());
        assertFalse(state.clear());
    }
}
