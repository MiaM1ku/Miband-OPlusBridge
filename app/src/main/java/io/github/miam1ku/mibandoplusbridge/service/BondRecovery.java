// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

/**
 * Decides what an SPP session does after the system bond is gone.
 *
 * <p>Secure RFCOMM needs the bond, so when the stack removes it the socket dies and every
 * following attempt is refused with {@code OFFICIAL_PAIRING_REQUIRED}. The quiet two minute retry
 * kept the band down until someone re-paired inside 「小米运动健康」. Two things change here: a bond
 * broadcast is news for the worker, so the next attempt starts right away, and the bridge asks the
 * system for the bond itself instead of waiting for the official app to do it.
 */
final class BondRecovery {
    /** BluetoothDevice.BOND_NONE, BOND_BONDING, BOND_BONDED, repeated to keep this JVM-only. */
    static final int NONE = 10;
    static final int BONDING = 11;
    static final int BONDED = 12;

    /** The next attempt should call {@code createBond()}. */
    static final int ASK = 0;
    /** The system is pairing, or a fresh request would only duplicate the last one. */
    static final int WAIT = 1;
    /** The bond is back; the session can use SPP again. */
    static final int READY = 2;
    /** This loss already used its requests. A re-pair from another app is what restores it. */
    static final int GAVE_UP = 3;

    static final int MAX_ASKS = 3;
    static final long ASK_GAP_MS = 20_000;
    /** Three asks can all miss a band that is out of range. After this hold the budget refills. */
    static final long HOLD_MS = 5 * 60_000;

    private int asks;
    private long lastAskMs = Long.MIN_VALUE;

    /**
     * A bond broadcast. True when it can change the outcome of the next attempt, which is also a
     * reason to wake the worker instead of waiting out the backoff. A restored bond also opens a
     * fresh request budget: the official app re-pairing is exactly what the next loss may need.
     */
    synchronized boolean note(int state) {
        if (state == BONDED) {
            asks = 0;
            lastAskMs = Long.MIN_VALUE;
            return true;
        }
        return state == NONE;
    }

    /** What to do before the next socket attempt. */
    synchronized int decide(int state, long nowMs) {
        if (state == BONDED) return READY;
        if (state == BONDING) return WAIT;
        if (asks >= MAX_ASKS) {
            if (nowMs - lastAskMs < HOLD_MS) return GAVE_UP;
            asks = 0;
            lastAskMs = Long.MIN_VALUE;
        }
        if (lastAskMs != Long.MIN_VALUE && nowMs - lastAskMs < ASK_GAP_MS) return WAIT;
        return ASK;
    }

    /** Records the {@code createBond()} call for {@link #decide}. */
    synchronized void asked(long nowMs) {
        asks++;
        lastAskMs = nowMs;
    }

    /** An authenticated session proves the bond is healthy, so the next loss gets a full budget. */
    synchronized void connected() {
        asks = 0;
        lastAskMs = Long.MIN_VALUE;
    }

    synchronized int asks() { return asks; }

    synchronized boolean gaveUp() { return asks >= MAX_ASKS; }

    /** Foreground notice while the bond is gone: keep asking, or hand the repair to the band. */
    static String notice(boolean gaveUp) {
        return gaveUp ? "手环需要重新配对" : "正在重新配对";
    }
}
