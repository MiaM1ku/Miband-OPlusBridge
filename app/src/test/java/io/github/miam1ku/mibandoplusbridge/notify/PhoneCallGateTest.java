// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.Test;
import static io.github.miam1ku.mibandoplusbridge.notify.PhoneCallGate.State.*;
import static org.junit.Assert.*;

public final class PhoneCallGateTest {
    @Test public void dualSimClearsOnlyWhenLastRingingSubscriptionStops() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, RINGING);
        gate.onState(2, RINGING);
        sender.ack(0);
        assertTrue(gate.isRingingDelivered());
        gate.onState(1, OFFHOOK);
        assertEquals(List.of("ring"), sender.events);
        gate.onState(2, IDLE);
        gate.onState(1, IDLE);
        assertEquals(List.of("ring", "cancel", "clear"), sender.events);
        sender.ack(1);
        assertFalse(gate.isRingingDelivered());
    }

    @Test public void outgoingCallsNeverRequestIncomingAlert() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, OFFHOOK);
        gate.onState(2, OFFHOOK);
        gate.onState(1, IDLE);
        gate.onState(2, IDLE);
        assertTrue(sender.events.isEmpty());
    }

    @Test public void endCancelsUnsentRingBeforeSubmittingClearAndIgnoresLateAck() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, RINGING);
        assertFalse(gate.isRingingDelivered());
        gate.onState(1, OFFHOOK);
        assertEquals(List.of("ring", "cancel", "clear"), sender.events);
        sender.ack(0);
        assertFalse(gate.isRingingDelivered());
        sender.ack(1);
        assertFalse(gate.isRingingDelivered());
    }

    @Test public void disableClearsCurrentAlertAndDoesNotRetainOldCallState() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, RINGING);
        sender.ack(0);
        gate.setEnabled(false);
        gate.onState(2, RINGING);
        gate.setEnabled(false);
        gate.setEnabled(true);
        assertEquals(List.of("ring", "cancel", "clear"), sender.events);
        assertFalse(gate.isRingingDelivered());
        gate.replaceStates(Map.of(1, IDLE, 2, IDLE));
        assertEquals(List.of("ring", "cancel", "clear"), sender.events);
    }

    @Test public void reconnectUsesFreshStateAndDoesNotReplayEndedCall() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, RINGING);
        gate.disconnected();
        gate.onState(1, RINGING);
        gate.connected(Map.of(1, IDLE, 2, OFFHOOK));
        sender.ack(0);
        assertFalse(gate.isRingingDelivered());
        assertEquals(List.of("ring", "cancel"), sender.events);
        gate.disconnected();
        gate.connected(Map.of(1, IDLE, 2, RINGING));
        assertEquals(List.of("ring", "cancel", "cancel", "ring"), sender.events);
        sender.ack(1);
        assertTrue(gate.isRingingDelivered());
    }

    @Test public void failedSubmissionIsObservableAndCannotBecomeDelivery() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, RINGING);
        RuntimeException failure = new IllegalStateException("OFFLINE");
        sender.pending.get(0).completeExceptionally(failure);
        assertSame(failure, gate.lastFailure());
        assertFalse(gate.isRingingDelivered());
        gate.onState(1, RINGING);
        assertEquals(List.of("ring"), sender.events);
        gate.onState(1, IDLE);
        // A transport failure can occur after bytes were sent; a clear is still required.
        assertEquals(List.of("ring", "cancel", "clear"), sender.events);
        sender.ack(1);
        assertNull(gate.lastFailure());
    }

    @Test public void immediateRejectionIsObservableWithoutEscapingCallback() {
        RuntimeException rejected = new IllegalStateException("NOT_READY");
        PhoneCallGate gate = new PhoneCallGate(new PhoneCallGate.Sender() {
            public CompletionStage<Void> send(boolean ringing) { throw rejected; }
            public void cancelQueuedRing() { }
        });
        gate.setEnabled(true);
        gate.connected(Map.of(1, RINGING));
        assertSame(rejected, gate.lastFailure());
        assertFalse(gate.isRingingDelivered());
    }

    @Test public void subscriptionRemovalAndLatestTransitionPreserveClearOrdering() {
        Sender sender = new Sender();
        PhoneCallGate gate = connected(sender);
        gate.onState(1, RINGING);
        gate.replaceStates(Map.of(2, IDLE));
        gate.onState(1, RINGING); // Removed subscription's late callback is ignored.
        gate.onState(2, RINGING);
        sender.ack(1); // Previous clear must not overwrite the new desired ring's delivery.
        assertFalse(gate.isRingingDelivered());
        sender.ack(2);
        assertTrue(gate.isRingingDelivered());
        assertEquals(List.of("ring", "cancel", "clear", "ring"), sender.events);
    }

    private static PhoneCallGate connected(Sender sender) {
        PhoneCallGate gate = new PhoneCallGate(sender);
        gate.setEnabled(true);
        gate.connected(Map.of(1, IDLE, 2, IDLE));
        return gate;
    }

    private static final class Sender implements PhoneCallGate.Sender {
        final List<String> events = new ArrayList<>();
        final List<CompletableFuture<Void>> pending = new ArrayList<>();
        public CompletionStage<Void> send(boolean ringing) {
            events.add(ringing ? "ring" : "clear");
            CompletableFuture<Void> result = new CompletableFuture<>();
            pending.add(result);
            return result;
        }
        public void cancelQueuedRing() { events.add("cancel"); }
        void ack(int index) { pending.get(index).complete(null); }
    }
}
