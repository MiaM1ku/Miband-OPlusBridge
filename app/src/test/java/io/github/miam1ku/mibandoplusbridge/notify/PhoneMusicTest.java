// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.media.session.PlaybackState;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneMusicTest {
    @Test public void playingPausedAndIdleUseTheBandCodes() {
        assertEquals(1, PhoneMusic.bandState(PlaybackState.STATE_PLAYING));
        assertEquals(2, PhoneMusic.bandState(PlaybackState.STATE_PAUSED));
        assertEquals(2, PhoneMusic.bandState(PlaybackState.STATE_BUFFERING));
        assertEquals(0, PhoneMusic.bandState(PlaybackState.STATE_STOPPED));
        assertEquals(0, PhoneMusic.bandState(PlaybackState.STATE_NONE));
    }
}
