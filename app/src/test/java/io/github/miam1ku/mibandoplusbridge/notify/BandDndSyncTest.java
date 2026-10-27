// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BandDndSyncTest {
    @Test public void aFreshLinkAsksBeforeItPushesAnything() {
        var sync = new BandDndSync();
        assertEquals(BandDndSync.Next.ASK, sync.before());
        assertTrue(sync.waiting());
        assertEquals(BandDndSync.Next.MIRROR, sync.before());
    }

    @Test public void theBandAnsweringOffStopsTheMirrorUntilTheUserTurnsItBackOn() {
        var sync = new BandDndSync();
        assertEquals(BandDndSync.Next.ASK, sync.before());
        assertFalse(sync.answered(false));
        assertFalse(sync.on());
        assertFalse(sync.waiting());
        assertEquals(BandDndSync.Next.HOLD, sync.before());
        assertEquals(BandDndSync.Next.HOLD, sync.before());
        assertTrue(sync.answered(true));
        assertTrue(sync.on());
        assertEquals(BandDndSync.Next.MIRROR, sync.before());
    }

    @Test public void aQuietBandKeepsTheStoredSwitch() {
        var stored = new BandDndSync();
        stored.seed(false);
        assertEquals(BandDndSync.Next.ASK, stored.before());
        assertFalse(stored.answered(null));
        assertFalse(stored.on());

        var stillOn = new BandDndSync();
        assertEquals(BandDndSync.Next.ASK, stillOn.before());
        assertTrue(stillOn.answered(null));
        assertTrue(stillOn.on());
    }

    @Test public void anAnswerOnTheSecondAskDoesNotTurnSyncOffWithTheFirst() {
        var sync = new BandDndSync();
        assertEquals(BandDndSync.Next.ASK, sync.before());
        assertTrue(sync.answered(true));
        // Pushed mid-link when the user moved the switch on the band, not as a reply.
        assertFalse(sync.answered(false));
        assertEquals(BandDndSync.Next.HOLD, sync.before());
        assertTrue(sync.answered(true));
    }

    @Test public void aNewLinkForgetsTheAnswerButNotTheSwitch() {
        var sync = new BandDndSync();
        assertEquals(BandDndSync.Next.ASK, sync.before());
        assertFalse(sync.answered(false));
        sync.newLink();
        assertFalse(sync.waiting());
        assertFalse(sync.on());
        assertEquals(BandDndSync.Next.ASK, sync.before());
    }

    @Test public void theStoredSwitchSeedsTheNextLink() {
        var sync = new BandDndSync();
        sync.seed(false);
        sync.newLink();
        assertEquals(BandDndSync.Next.ASK, sync.before());
        // Answered off again: no mirror, and nothing changes.
        assertFalse(sync.answered(false));
        assertEquals(BandDndSync.Next.HOLD, sync.before());
    }
}
