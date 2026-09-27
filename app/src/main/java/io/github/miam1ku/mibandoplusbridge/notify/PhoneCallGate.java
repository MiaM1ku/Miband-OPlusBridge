// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** In-memory, per-subscription state only; submitted commands are not delivery evidence. */
public final class PhoneCallGate {
    public enum State { IDLE, RINGING, OFFHOOK }

    public interface Sender {
        CompletionStage<Void> send(boolean ringing);
        void cancelQueuedRing();
    }

    private final Sender sender;
    private final Map<Integer, State> states = new HashMap<>();
    private boolean enabled;
    private boolean connected;
    private boolean ringing;
    private boolean deliveredRinging;
    private long revision;
    private Throwable lastFailure;

    public PhoneCallGate(Sender sender) {
        this.sender = Objects.requireNonNull(sender);
    }

    public synchronized void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (!value) {
            states.clear();
            reconcile();
            // Invalidate any late ring completion even when already disconnected.
            deliveredRinging = false;
        }
    }

    /** A fresh snapshot is mandatory: never reuse the previous session's call states. */
    public synchronized void connected(Map<Integer, State> currentStates) {
        revision++;
        connected = true;
        ringing = false;
        deliveredRinging = false;
        states.clear();
        if (enabled) states.putAll(currentStates);
        reconcile();
    }

    public synchronized void disconnected() {
        revision++;
        connected = false;
        ringing = false;
        deliveredRinging = false;
        states.clear();
        sender.cancelQueuedRing();
    }

    /** Replacing the complete set makes subscription removal an atomic aggregate transition. */
    public synchronized void replaceStates(Map<Integer, State> currentStates) {
        states.clear();
        if (enabled) states.putAll(currentStates);
        reconcile();
    }

    public synchronized void onState(int subscriptionId, State state) {
        if (!enabled || !states.containsKey(subscriptionId)) return;
        states.put(subscriptionId, Objects.requireNonNull(state));
        reconcile();
    }

    public synchronized boolean isRingingDelivered() { return deliveredRinging; }
    public synchronized Throwable lastFailure() { return lastFailure; }

    private void reconcile() {
        boolean next = enabled && connected && states.containsValue(State.RINGING);
        if (next == ringing) return;
        ringing = next;
        long token = ++revision;
        lastFailure = null;
        // Cancel first, then reserve the clear; an unsent ring must not survive its end.
        if (!next) sender.cancelQueuedRing();
        try {
            Objects.requireNonNull(sender.send(next)).whenComplete((unused, failure) -> {
                synchronized (PhoneCallGate.this) {
                    if (token != revision) return;
                    if (failure != null) lastFailure = failure;
                    else deliveredRinging = next;
                }
            });
        } catch (RuntimeException failure) {
            lastFailure = failure;
        }
    }
}
