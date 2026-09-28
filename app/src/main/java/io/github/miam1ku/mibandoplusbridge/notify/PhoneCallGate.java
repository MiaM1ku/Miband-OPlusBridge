// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** In-memory, per-subscription state only; submitted commands are not delivery evidence. */
public final class PhoneCallGate {
    public enum State { IDLE, RINGING, OFFHOOK }
    /** Band call screen. Incoming wins over an answered line; outgoing is a dial the phone started. */
    public enum Phase { NONE, INCOMING, ACTIVE, OUTGOING }

    public interface Sender {
        CompletionStage<Void> send(Phase phase);
        void cancelQueuedRing();
    }

    private static final class Line {
        Phase phase = Phase.NONE;
    }

    private final Sender sender;
    private final Map<Integer, Line> lines = new HashMap<>();
    private boolean enabled;
    private boolean connected;
    private Phase phase = Phase.NONE;
    private Phase delivered = Phase.NONE;
    private long revision;
    private Throwable lastFailure;

    public PhoneCallGate(Sender sender) {
        this.sender = Objects.requireNonNull(sender);
    }

    public synchronized void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (!value) {
            lines.clear();
            reconcile();
            delivered = Phase.NONE;
        }
    }

    /** A fresh snapshot is mandatory: never reuse the previous session's call states. */
    public synchronized void connected(Map<Integer, State> currentStates) {
        revision++;
        connected = true;
        phase = Phase.NONE;
        delivered = Phase.NONE;
        lines.clear();
        if (enabled) apply(currentStates);
        reconcile();
    }

    public synchronized void disconnected() {
        revision++;
        connected = false;
        phase = Phase.NONE;
        delivered = Phase.NONE;
        lines.clear();
        sender.cancelQueuedRing();
    }

    /** Replacing the complete set makes subscription removal an atomic aggregate transition. */
    public synchronized void replaceStates(Map<Integer, State> currentStates) {
        if (!enabled) {
            lines.clear();
            reconcile();
            return;
        }
        apply(currentStates);
        reconcile();
    }

    public synchronized void onState(int subscriptionId, State state) {
        if (!enabled || !lines.containsKey(subscriptionId)) return;
        Line line = lines.get(subscriptionId);
        line.phase = transition(line.phase, Objects.requireNonNull(state), false);
        reconcile();
    }

    /** OFFHOOK does not change when the remote side answers. The in-call notification does. */
    public synchronized boolean markAnswered() {
        if (!enabled) return false;
        boolean changed = false;
        for (Line line : lines.values()) {
            if (line.phase == Phase.OUTGOING) {
                line.phase = Phase.ACTIVE;
                changed = true;
            }
        }
        if (changed) reconcile();
        return changed;
    }

    public synchronized boolean isRingingDelivered() { return delivered == Phase.INCOMING; }
    public synchronized Throwable lastFailure() { return lastFailure; }

    private void apply(Map<Integer, State> currentStates) {
        Map<Integer, Line> next = new HashMap<>();
        for (var entry : currentStates.entrySet()) {
            Line previous = lines.get(entry.getKey());
            Line line = new Line();
            line.phase = transition(previous == null ? Phase.NONE : previous.phase,
                    Objects.requireNonNull(entry.getValue()), previous == null);
            next.put(entry.getKey(), line);
        }
        lines.clear();
        lines.putAll(next);
    }

    private static Phase transition(Phase previous, State telephony, boolean snapshot) {
        return switch (telephony) {
            case RINGING -> Phase.INCOMING;
            case IDLE -> Phase.NONE;
            case OFFHOOK -> switch (previous) {
                case INCOMING, ACTIVE -> Phase.ACTIVE;
                case OUTGOING -> Phase.OUTGOING;
                case NONE -> snapshot ? Phase.ACTIVE : Phase.OUTGOING;
            };
        };
    }

    private Phase aggregate() {
        boolean incoming = false;
        boolean active = false;
        boolean outgoing = false;
        for (Line line : lines.values()) {
            switch (line.phase) {
                case INCOMING -> incoming = true;
                case ACTIVE -> active = true;
                case OUTGOING -> outgoing = true;
                case NONE -> { }
            }
        }
        if (incoming) return Phase.INCOMING;
        if (active) return Phase.ACTIVE;
        if (outgoing) return Phase.OUTGOING;
        return Phase.NONE;
    }

    private void reconcile() {
        Phase next = enabled && connected ? aggregate() : Phase.NONE;
        if (next == phase) return;
        if (phase != Phase.NONE) sender.cancelQueuedRing();
        phase = next;
        long token = ++revision;
        lastFailure = null;
        try {
            Objects.requireNonNull(sender.send(next)).whenComplete((unused, failure) -> {
                synchronized (PhoneCallGate.this) {
                    if (token != revision) return;
                    if (failure != null) lastFailure = failure;
                    else delivered = next;
                }
            });
        } catch (RuntimeException failure) {
            lastFailure = failure;
        }
    }
}
