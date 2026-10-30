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

    @Test public void asksForTheBondOncePerLoss() {
        BondRecovery recovery = new BondRecovery();
        long now = 1_000;
        assertEquals(BondRecovery.ASK, recovery.decide(BondRecovery.NONE, now));
        recovery.asked(now);
        // The band confirm prompt lives on the user's wrist, so never nag automatically.
        assertEquals("no second automatic ask, however long the gap",
                BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, now + BondRecovery.ASK_GAP_MS));
        assertEquals(BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, now + 60 * 60_000));
        assertEquals("one request per loss", BondRecovery.MAX_ASKS, recovery.asks());
        assertTrue(recovery.gaveUp());
    }

    @Test public void pairingInProgressAndRestoredBondSkipTheRequest() {
        BondRecovery recovery = new BondRecovery();
        assertEquals(BondRecovery.WAIT, recovery.decide(BondRecovery.BONDING, 5_000));
        assertEquals(BondRecovery.READY, recovery.decide(BondRecovery.BONDED, 5_000));
        assertEquals("a restored bond spends no request", 0, recovery.asks());
        assertEquals(BondRecovery.ASK, recovery.decide(BondRecovery.NONE, 5_000));
        recovery.asked(5_000);
        // With a single ask the gap branch is unreachable: one request already spent the loss.
        assertEquals(BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, 5_001));
    }

    @Test public void manualRetryAuthAndRestoredBondRefillTheBudget() {
        BondRecovery recovery = new BondRecovery();
        recovery.asked(1_000);
        assertEquals(BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, 100_000));
        recovery.manualRetry();
        assertEquals("the 重新配对 tap expects the next wrist prompt", BondRecovery.ASK,
                recovery.decide(BondRecovery.NONE, 100_000));
        recovery.asked(100_000);
        assertEquals(BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, 200_000));
        recovery.connected();
        assertEquals("an authenticated session is a fresh start", BondRecovery.ASK,
                recovery.decide(BondRecovery.NONE, 200_000));
        recovery.asked(200_000);
        recovery.note(BondRecovery.BONDED);
        assertEquals("the official app re-pairing is a fresh start", 0, recovery.asks());
    }

    @Test public void noticeAsksForATapAfterTheOneAsk() {
        assertEquals("正在重新配对", BondRecovery.notice(false));
        assertEquals("手环需要重新配对，点通知重试", BondRecovery.notice(true));
    }
}
