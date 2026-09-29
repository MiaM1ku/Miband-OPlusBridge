// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

/** Drops a second copy of the same music frame. A forced refresh may repeat after the window. */
final class MusicRepeat {
    static final long FORCE_WINDOW_NANOS = 100_000_000L;

    private MusicRepeat() {}

    static boolean suppress(boolean force, boolean same, long elapsedNanos) {
        if (!same) return false;
        if (!force) return true;
        return elapsedNanos >= 0 && elapsedNanos < FORCE_WINDOW_NANOS;
    }
}
