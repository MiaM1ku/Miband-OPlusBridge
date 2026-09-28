// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Type 18 music snapshots and band media keys. Volume on key 5 is an absolute 0–100 percent. */
public final class BandMusicCommand {
    public enum Action { PLAY, PAUSE, PREVIOUS, NEXT, VOLUME_UP, VOLUME_DOWN }

    private BandMusicCommand() {}

    public static XiaomiProto.Command nothing(int volume) {
        return XiaomiProto.Command.newBuilder().setType(18).setSubtype(1)
                .setMusic(XiaomiProto.Music.newBuilder().setMusicInfo(
                        XiaomiProto.MusicInfo.newBuilder().setState(0).setVolume(clampPercent(volume))))
                .build();
    }

    public static XiaomiProto.Command playback(int volume, String track, String artist,
                                                int positionSeconds, int durationSeconds, boolean playing) {
        var info = XiaomiProto.MusicInfo.newBuilder()
                .setState(playing ? 1 : 2)
                .setVolume(clampPercent(volume))
                .setTrack(track == null ? "" : track)
                .setArtist(artist == null ? "" : artist)
                .setPosition(Math.max(0, positionSeconds))
                .setDuration(Math.max(0, durationSeconds));
        return XiaomiProto.Command.newBuilder().setType(18).setSubtype(1)
                .setMusic(XiaomiProto.Music.newBuilder().setMusicInfo(info))
                .build();
    }

    /** Shrinks track, then artist, by whole Unicode code points. State and volume stay. */
    public static XiaomiProto.Command fit(XiaomiProto.Command command, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("MUSIC_PAYLOAD_LIMIT");
        if (command.getSerializedSize() <= limit) return command;
        if (!command.hasMusic() || !command.getMusic().hasMusicInfo()) {
            throw new IllegalArgumentException("MUSIC_PAYLOAD_LIMIT");
        }
        var original = command.getMusic().getMusicInfo();
        var data = original.toBuilder().clearTrack().clearArtist();
        if (replaceInfo(command, data.build()).getSerializedSize() > limit) {
            throw new IllegalArgumentException("MUSIC_PAYLOAD_LIMIT");
        }
        fitField(command, data, original.getTrack(), true, limit);
        fitField(command, data, original.getArtist(), false, limit);
        return replaceInfo(command, data.build());
    }

    public static int percent(int level, int max) {
        if (max <= 0) return 0;
        return clampPercent(Math.round(100f * level / max));
    }

    /** Key 5 at or below {@code currentPercent} is down. An absent volume field is proto2 0. */
    public static Action action(XiaomiProto.Command command, int currentPercent) {
        if (command == null || command.getType() != 18 || command.getSubtype() != 2
                || !command.hasMusic() || !command.getMusic().hasMediaKey()) return null;
        var key = command.getMusic().getMediaKey();
        return switch (key.getKey()) {
            case 0 -> Action.PLAY;
            case 1 -> Action.PAUSE;
            case 3 -> Action.PREVIOUS;
            case 4 -> Action.NEXT;
            case 5 -> key.getVolume() > currentPercent ? Action.VOLUME_UP : Action.VOLUME_DOWN;
            default -> null;
        };
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static void fitField(XiaomiProto.Command command, XiaomiProto.MusicInfo.Builder data,
                                 String text, boolean track, int limit) {
        int low = 0;
        int high = text.codePointCount(0, text.length());
        while (low < high) {
            int count = low + (high - low + 1) / 2;
            String prefix = text.substring(0, text.offsetByCodePoints(0, count));
            if (track) data.setTrack(prefix); else data.setArtist(prefix);
            if (replaceInfo(command, data.build()).getSerializedSize() <= limit) low = count;
            else high = count - 1;
        }
        String prefix = text.substring(0, text.offsetByCodePoints(0, low));
        if (track) {
            if (low == 0) data.clearTrack(); else data.setTrack(prefix);
        } else if (low == 0) data.clearArtist();
        else data.setArtist(prefix);
    }

    private static XiaomiProto.Command replaceInfo(XiaomiProto.Command command, XiaomiProto.MusicInfo info) {
        return command.toBuilder().setMusic(command.getMusic().toBuilder().setMusicInfo(info)).build();
    }
}
