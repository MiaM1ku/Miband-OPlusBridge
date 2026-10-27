// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A removed bond used to leave the session in a two minute loop with no repair. */
public final class BondRecoveryTest {
    @Test public void lostBondWakesTheWorkerAndRestoredBondWakesIt() {
        BondRecovery recovery = new BondRecovery();
        assertTrue("a removed bond is news", recovery.note(BondRecovery.NONE));
        assertTrue("a restored bond is news", recovery.note(BondRecovery.BONDED));
        assertFalse("pairing in progress changes nothing yet", recovery.note(BondRecovery.BONDING));
    }

    @Test public void asksForTheBondThreeTimesWithAGap() {
        BondRecovery recovery = new BondRecovery();
        long now = 1_000;
        assertEquals(BondRecovery.ASK, recovery.decide(BondRecovery.NONE, now));
        recovery.asked(now);
        assertEquals("the same second must not ask twice", BondRecovery.WAIT,
                recovery.decide(BondRecovery.NONE, now + 1));
        assertEquals(BondRecovery.ASK, recovery.decide(BondRecovery.NONE, now + BondRecovery.ASK_GAP_MS));
        recovery.asked(now + BondRecovery.ASK_GAP_MS);
        assertEquals(BondRecovery.ASK, recovery.decide(BondRecovery.NONE, now + 2 * BondRecovery.ASK_GAP_MS));
        recovery.asked(now + 2 * BondRecovery.ASK_GAP_MS);
        assertEquals("three requests per loss", BondRecovery.MAX_ASKS, recovery.asks());
        assertEquals(BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, now + 10 * BondRecovery.ASK_GAP_MS));
        assertTrue(recovery.gaveUp());
    }

    @Test public void theBudgetRefillsAfterTheHold() {
        BondRecovery recovery = new BondRecovery();
        for (int ask = 0; ask < BondRecovery.MAX_ASKS; ask++) recovery.asked(1_000L * ask);
        long lastAsk = 1_000L * (BondRecovery.MAX_ASKS - 1);
        assertEquals(BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, lastAsk + BondRecovery.HOLD_MS - 1));
        assertEquals("a band that came back deserves another ask",
                BondRecovery.ASK, recovery.decide(BondRecovery.NONE, lastAsk + BondRecovery.HOLD_MS));
    }

    @Test public void pairingInProgressAndRestoredBondSkipTheRequest() {
        BondRecovery recovery = new BondRecovery();
        assertEquals(BondRecovery.WAIT, recovery.decide(BondRecovery.BONDING, 5_000));
        assertEquals(BondRecovery.READY, recovery.decide(BondRecovery.BONDED, 5_000));
        assertEquals("a restored bond spends no request", 0, recovery.asks());
    }

    @Test public void authenticatedSessionAndRestoredBondRefillTheBudget() {
        BondRecovery recovery = new BondRecovery();
        for (int ask = 0; ask < BondRecovery.MAX_ASKS; ask++) {
            recovery.asked(1_000L * ask);
        }
        assertEquals(BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, 100_000));
        recovery.connected();
        assertEquals(BondRecovery.ASK, recovery.decide(BondRecovery.NONE, 100_000));
        recovery.asked(100_000);
        recovery.note(BondRecovery.BONDED);
        assertEquals("the official app re-pairing is a fresh start", 0, recovery.asks());
    }

    @Test public void noticeAsksForATapOnlyAfterTheBudget() {
        assertEquals("正在重新配对", BondRecovery.notice(false));
        assertEquals("手环需要重新配对", BondRecovery.notice(true));
    }
}
