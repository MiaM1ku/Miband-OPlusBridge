// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BandLiveServiceSleepPolicyTest {
    @Test public void nightWindowCoversTwentyTwoThroughSeven() {
        for (int hour = 22; hour <= 23; hour++) assertTrue(BandLiveService.isNightWindow(hour));
        for (int hour = 0; hour < 8; hour++) assertTrue(BandLiveService.isNightWindow(hour));
        for (int hour = 8; hour < 22; hour++) assertFalse(BandLiveService.isNightWindow(hour));
    }

    @Test public void historyPollIsTwoHoursAtNightAndThirtyMinutesByDay() {
        assertEquals(120, BandLiveService.historyPeriodMinutes(23));
        assertEquals(120, BandLiveService.historyPeriodMinutes(3));
        assertEquals(30, BandLiveService.historyPeriodMinutes(8));
        assertEquals(30, BandLiveService.historyPeriodMinutes(15));
    }

    @Test public void historyGateDecisionTable() {
        assertTrue(BandLiveService.shouldRequestHistory(
                false, true, null, false, false, false, false));
        assertFalse(BandLiveService.shouldRequestHistory(
                true, true, null, false, true, false, false));
        assertTrue(BandLiveService.shouldRequestHistory(
                true, true, null, false, false, false, false));
        assertFalse(BandLiveService.shouldRequestHistory(
                true, true, null, false, false, true, false));
        assertFalse(BandLiveService.shouldRequestHistory(
                true, false, true, false, false, false, false));
        assertTrue(BandLiveService.shouldRequestHistory(
                true, false, false, false, false, false, false));
        assertFalse(BandLiveService.shouldRequestHistory(
                true, true, null, true, false, false, false));
        assertFalse(BandLiveService.shouldRequestHistory(
                true, true, null, false, false, false, true));
    }

    @Test public void quietStepNoiseDoesNotResetTheQuietWindow() {
        assertTrue(BandLiveService.quietStepNoise(1001, 1000));
        assertTrue(BandLiveService.quietElapsed(60L * 60_000L + 1, 1));
        assertFalse(BandLiveService.quietStepNoise(1040, 1000));
    }

    @Test public void counterResetRebasesWithoutWakeEvidence() {
        var zone = java.time.ZoneId.systemDefault();
        long before = java.time.LocalDate.of(2026, 11, 14).atTime(23, 59).atZone(zone).toInstant().toEpochMilli();
        long after = java.time.LocalDate.of(2026, 11, 15).atTime(0, 1).atZone(zone).toInstant().toEpochMilli();
        assertTrue(BandLiveService.quietCounterReset(1000, before, 0, after));
        assertTrue(BandLiveService.quietCounterReset(5, before, 40, after));
        assertTrue(BandLiveService.quietCounterReset(1000, before, 5, before + 1000));
        assertFalse(BandLiveService.quietCounterReset(1000, before, 1040, before + 1000));
        assertFalse(BandLiveService.quietCounterReset(Long.MIN_VALUE, 0, 0, after));
    }

    @Test public void newSleepEpisodeDiscardsOldWakeEvidenceAndClosedIntervals() {
        var evidence = new NightWakeEvidence();
        evidence.observe();
        assertTrue(evidence.observed());
        evidence.beginEpisode(2000);
        assertFalse(evidence.observed());
        assertFalse(evidence.acceptsClosedInterval(1999));
        assertTrue(evidence.acceptsClosedInterval(2001));
        evidence.observe();
        assertTrue(evidence.observed());
        evidence.reset();
        assertFalse(evidence.observed());
    }

    @Test public void nightAwakeNeedsTwoConsecutiveSamples() {
        assertTrue(BandLiveService.nightAwakeConfirmed(false, 1));
        assertFalse(BandLiveService.nightAwakeConfirmed(true, 1));
        assertTrue(BandLiveService.nightAwakeConfirmed(true, 2));
    }

    @Test public void staleStepSampleIsNeverTreatedAsQuiet() {
        long now = 100L * 60_000L;
        assertTrue(BandLiveService.staleStepSample(now, 0));
        assertTrue(BandLiveService.staleStepSample(now, now - 91L * 60_000L));
        assertFalse(BandLiveService.staleStepSample(now, now - 89L * 60_000L));
        assertTrue(BandLiveService.quietSuppressesHistory(false, true));
        assertTrue(BandLiveService.quietSuppressesHistory(true, false));
        assertFalse(BandLiveService.quietSuppressesHistory(false, false));
    }

    @Test public void sleepStatePollingRunsAtNightOnly() {
        assertTrue(BandLiveService.shouldPollSleepState(23, true));
        assertTrue(BandLiveService.shouldPollSleepState(3, true));
        assertFalse(BandLiveService.shouldPollSleepState(10, true));
        assertFalse(BandLiveService.shouldPollSleepState(23, false));
    }

    @Test public void lateWakeRequiresEvidenceBeforeTheHardEnd() {
        assertTrue(BandLiveService.nightStillSuppressed(8, false));
        assertFalse(BandLiveService.nightStillSuppressed(8, true));
        assertTrue(BandLiveService.nightStillSuppressed(11, false));
        assertTrue(BandLiveService.nightStillSuppressed(13, false));
        assertFalse(BandLiveService.nightStillSuppressed(14, false));
        assertFalse(BandLiveService.nightStillSuppressed(15, false));
    }

    @Test public void manualSyncBypassesSleepGateForOneRound() {
        assertFalse(BandLiveService.historyShouldFlush(true, false, false));
        assertTrue(BandLiveService.historyShouldFlush(true, true, false));
        assertFalse(BandLiveService.historyShouldFlush(false, true, false));
        assertTrue(BandLiveService.historyShouldFlush(true, false, true));
    }

    @Test public void manualSyncIsRetainedUntilHistoryPipelineIsReadyAndConsumedOnce() {
        var requested = new java.util.concurrent.atomic.AtomicBoolean(true);
        long requestedAtMs = 1_000L;
        assertFalse(BandLiveService.consumeManualSync(requested, false, requestedAtMs, requestedAtMs + 1));
        assertTrue(requested.get());
        assertTrue(BandLiveService.consumeManualSync(requested, true, requestedAtMs, requestedAtMs + 1));
        assertFalse(requested.get());
        assertFalse(BandLiveService.consumeManualSync(requested, true, requestedAtMs, requestedAtMs + 1));
    }

    @Test public void manualSyncOverrideExpiresAfterOneMinute() {
        var requested = new java.util.concurrent.atomic.AtomicBoolean(true);
        long requestedAtMs = 1_000L;
        assertTrue(BandLiveService.consumeManualSync(requested, true, requestedAtMs, requestedAtMs + 60_000L));
        assertFalse(requested.get());

        requested.set(true);
        assertFalse(BandLiveService.consumeManualSync(requested, true, requestedAtMs,
                requestedAtMs + 60_001L));
        assertFalse(requested.get());
    }

    @Test public void sleepProtectionWindowEndsAtFourteen() {
        assertTrue(BandLiveService.isSleepProtectionWindow(22));
        assertTrue(BandLiveService.isSleepProtectionWindow(0));
        assertTrue(BandLiveService.isSleepProtectionWindow(13));
        assertFalse(BandLiveService.isSleepProtectionWindow(14));
        assertFalse(BandLiveService.isSleepProtectionWindow(21));
    }

    @Test public void stepRefreshMustBeatTheStaleThreshold() {
        assertTrue(BandLiveService.nightStatsRefreshMinutes()
                < BandLiveService.quietSampleMaxAgeMinutes());
        assertTrue(BandLiveService.quietSampleMaxAgeMinutes()
                < BandLiveService.nightPollMinutes());
    }
}
