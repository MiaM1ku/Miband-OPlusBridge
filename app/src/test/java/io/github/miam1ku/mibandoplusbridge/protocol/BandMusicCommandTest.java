// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class BandMusicCommandTest {
    @Test public void playbackKeepsTrackAndNothingOmitsIt() {
        var playing = BandMusicCommand.playback(50, "测试音乐", "桥接", 12, 180, true);
        assertEquals(18, playing.getType());
        assertEquals(1, playing.getSubtype());
        var info = playing.getMusic().getMusicInfo();
        assertEquals(1, info.getState());
        assertEquals("测试音乐", info.getTrack());
        assertEquals("桥接", info.getArtist());
        assertEquals(12, info.getPosition());
        assertEquals(180, info.getDuration());

        var idle = BandMusicCommand.nothing(20);
        assertEquals(0, idle.getMusic().getMusicInfo().getState());
        assertFalse(idle.getMusic().getMusicInfo().hasTrack());
    }

    @Test public void pausedPlaybackIsStateTwo() {
        assertEquals(2, BandMusicCommand.playback(1, "a", "b", 0, 1, false)
                .getMusic().getMusicInfo().getState());
    }

    @Test public void mediaKeysMapToActions() {
        assertEquals(BandMusicCommand.Action.PLAY, BandMusicCommand.action(key(0, 0), 40));
        assertEquals(BandMusicCommand.Action.PAUSE, BandMusicCommand.action(key(1, 0), 40));
        assertEquals(BandMusicCommand.Action.PREVIOUS, BandMusicCommand.action(key(3, 0), 40));
        assertEquals(BandMusicCommand.Action.NEXT, BandMusicCommand.action(key(4, 0), 40));
        assertEquals(BandMusicCommand.Action.VOLUME_UP, BandMusicCommand.action(key(5, 80), 40));
        assertEquals(BandMusicCommand.Action.VOLUME_DOWN, BandMusicCommand.action(key(5, 40), 40));
        assertNull(BandMusicCommand.action(key(2, 0), 40));
    }

    @Test public void percentRoundsAndRejectsEmptyRange() {
        assertEquals(33, BandMusicCommand.percent(5, 15));
        assertEquals(0, BandMusicCommand.percent(1, 0));
    }

    @Test public void fitKeepsSurrogatePairsAndFixedFields() {
        String track = "曲\ud83c\udfb5".repeat(80);
        var command = BandMusicCommand.playback(40, track, "桥接", 3, 9, true);
        int limit = command.getSerializedSize() - 1;
        var fitted = BandMusicCommand.fit(command, limit);
        var info = fitted.getMusic().getMusicInfo();
        assertTrue(fitted.getSerializedSize() <= limit);
        assertEquals(1, info.getState());
        assertEquals(40, info.getVolume());
        String kept = info.hasTrack() ? info.getTrack() : "";
        assertEquals(kept, track.substring(0, kept.length()));
        if (!kept.isEmpty()) assertFalse(Character.isHighSurrogate(kept.charAt(kept.length() - 1)));
    }

    private static XiaomiProto.Command key(int code, int volume) {
        return XiaomiProto.Command.newBuilder().setType(18).setSubtype(2)
                .setMusic(XiaomiProto.Music.newBuilder().setMediaKey(
                        XiaomiProto.MediaKey.newBuilder().setKey(code).setVolume(volume)))
                .build();
    }
}
