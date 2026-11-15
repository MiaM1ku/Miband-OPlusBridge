// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

/** Wake evidence and stored sleep intervals belong to the current sleep episode. */
final class NightWakeEvidence {
    private boolean observed;
    private long episodeStartedAtMs;

    void reset() {
        observed = false;
        episodeStartedAtMs = 0;
    }

    void beginEpisode(long nowMs) {
        observed = false;
        episodeStartedAtMs = nowMs;
    }

    void observe() { observed = true; }
    boolean observed() { return observed; }
    boolean acceptsClosedInterval(long endMs) { return endMs >= episodeStartedAtMs; }
}
