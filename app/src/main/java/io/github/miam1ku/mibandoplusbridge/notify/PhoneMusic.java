// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.content.ComponentName;
import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import io.github.miam1ku.mibandoplusbridge.protocol.BandMusicCommand;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import java.util.List;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Pushes the active media session to the band and applies the band's media keys. */
public final class PhoneMusic {
    private static volatile PhoneMusic instance;
    private final Context context;
    private final Handler main;
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { push(false); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { push(false); }
        @Override public void onSessionDestroyed() {
            bindController();
            push(false);
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = controllers -> {
        bindController();
        push(false);
    };
    private final ContentObserver volumeObserver;
    private MediaSessionManager sessions;
    private MediaController controller;
    private Snapshot queued;
    private boolean watching;
    private boolean inFlight;
    private boolean dirty;
    private int observedPercent = Integer.MIN_VALUE;

    public PhoneMusic(Context context, Handler main) {
        this.context = context;
        this.main = main;
        volumeObserver = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                int percent = volumePercent();
                if (percent == observedPercent) return;
                observedPercent = percent;
                push(false);
            }
        };
        instance = this;
    }

    public void close() {
        watch(false);
        queued = null;
        dirty = false;
        if (instance == this) instance = null;
    }

    public void onListenerConnected() {
        watch(true);
        if (BandLiveService.notificationSessionReady(context)) push(true);
    }

    public void onListenerDisconnected() {
        watch(false);
        queued = null;
        dirty = false;
    }

    /** Band session appeared or dropped. Forgets the snapshot when the session is gone. */
    public void onBandSessionChanged(boolean listenerConnected) {
        if (listenerConnected && BandLiveService.notificationSessionReady(context)) push(true);
        else {
            queued = null;
            dirty = false;
        }
    }

    public static void requestRefresh(Context context) {
        PhoneMusic music = instance;
        if (music == null) {
            sendStandalone(context.getApplicationContext(), BandMusicCommand.nothing(volumePercent(context)));
            return;
        }
        music.main.post(() -> music.push(true));
    }

    public static void onMediaKey(Context context, XiaomiProto.Command command) {
        PhoneMusic music = instance;
        if (music != null) {
            music.main.post(() -> music.handleKey(command));
            return;
        }
        handleWithoutSession(context.getApplicationContext(), command);
    }

    private void handleKey(XiaomiProto.Command command) {
        BandMusicCommand.Action action = BandMusicCommand.action(command, volumePercent());
        if (action == null) return;
        Log.i("OplusBandBridge", "MUSIC_KEY " + command.getMusic().getMediaKey().getKey());
        if (action == BandMusicCommand.Action.VOLUME_UP || action == BandMusicCommand.Action.VOLUME_DOWN) {
            adjust(action);
        } else if (controller != null) {
            MediaController.TransportControls controls = controller.getTransportControls();
            switch (action) {
                case PLAY -> controls.play();
                case PAUSE -> controls.pause();
                case PREVIOUS -> controls.skipToPrevious();
                case NEXT -> controls.skipToNext();
                default -> { }
            }
        } else {
            dispatch(context, action);
        }
        push(true);
    }

    private void watch(boolean on) {
        if (watching == on) return;
        watching = on;
        if (!on) {
            if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
            if (controller != null) controller.unregisterCallback(callback);
            controller = null;
            context.getContentResolver().unregisterContentObserver(volumeObserver);
            return;
        }
        observedPercent = volumePercent();
        context.getContentResolver().registerContentObserver(
                Settings.System.CONTENT_URI, true, volumeObserver);
        sessions = context.getSystemService(MediaSessionManager.class);
        if (sessions == null) return;
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged,
                    new ComponentName(context, BandNotificationListener.class), main);
            bindController();
        } catch (SecurityException denied) {
            controller = null;
        }
    }

    private void bindController() {
        if (!watching || sessions == null) return;
        MediaController next = null;
        try {
            List<MediaController> active = sessions.getActiveSessions(
                    new ComponentName(context, BandNotificationListener.class));
            if (active != null && !active.isEmpty()) next = active.get(0);
        } catch (SecurityException denied) {
            next = null;
        }
        if (same(controller, next)) return;
        if (controller != null) controller.unregisterCallback(callback);
        controller = next;
        if (controller != null) controller.registerCallback(callback, main);
    }

    private void push(boolean force) {
        if (!BandLiveService.notificationSessionReady(context)) {
            queued = null;
            dirty = false;
            return;
        }
        Snapshot now = read();
        if (!force && queued != null && queued.same(now)) return;
        if (inFlight) {
            dirty = true;
            return;
        }
        send(now);
    }

    private void send(Snapshot now) {
        if (inFlight) {
            dirty = true;
            return;
        }
        if (!BandLiveService.notificationSessionReady(context)) {
            queued = null;
            dirty = false;
            return;
        }
        int limit = BandLiveService.notificationPayloadLimit();
        if (limit <= 0) {
            queued = null;
            return;
        }
        XiaomiProto.Command fitted;
        try {
            fitted = BandMusicCommand.fit(command(now), limit);
        } catch (IllegalArgumentException dropped) {
            queued = null;
            return;
        }
        inFlight = true;
        dirty = false;
        queued = now;
        BandLiveService.sendSessionCommand(fitted).whenComplete((ignored, error) -> main.post(() -> {
            inFlight = false;
            if (error != null) {
                if (dirty && BandLiveService.notificationSessionReady(context)) {
                    queued = null;
                    dirty = false;
                    push(false);
                }
                return;
            }
            Log.i("OplusBandBridge", "MUSIC_OUT state=" + now.state + " volume=" + now.volume);
            if (dirty && BandLiveService.notificationSessionReady(context)) {
                dirty = false;
                push(false);
            }
        }));
    }

    private Snapshot read() {
        int volume = volumePercent();
        MediaController current = controller;
        if (current == null) return idle();
        MediaMetadata metadata = current.getMetadata();
        PlaybackState playback = current.getPlaybackState();
        if (metadata == null || playback == null) return idle();
        long durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        int duration = durationMs <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, durationMs / 1000L);
        String track = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        int state = playback.getState() == PlaybackState.STATE_PLAYING ? 1 : 2;
        return new Snapshot(state, volume, track == null ? "" : track, artist == null ? "" : artist, duration);
    }

    private Snapshot idle() {
        return new Snapshot(0, volumePercent(), "", "", 0);
    }

    private XiaomiProto.Command command(Snapshot snapshot) {
        if (snapshot.state == 0) return BandMusicCommand.nothing(snapshot.volume);
        PlaybackState playback = controller == null ? null : controller.getPlaybackState();
        long positionMs = playback == null ? 0 : playback.getPosition();
        int position = positionMs <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, positionMs / 1000L);
        return BandMusicCommand.playback(snapshot.volume, snapshot.track, snapshot.artist, position,
                snapshot.duration, snapshot.state == 1);
    }

    private int volumePercent() {
        return volumePercent(context);
    }

    private static int volumePercent(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return 0;
        return BandMusicCommand.percent(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    private void adjust(BandMusicCommand.Action action) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return;
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                action == BandMusicCommand.Action.VOLUME_UP
                        ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI);
    }

    private static void handleWithoutSession(Context context, XiaomiProto.Command command) {
        BandMusicCommand.Action action = BandMusicCommand.action(command, volumePercent(context));
        if (action == null) return;
        Log.i("OplusBandBridge", "MUSIC_KEY " + command.getMusic().getMediaKey().getKey());
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio != null && (action == BandMusicCommand.Action.VOLUME_UP
                || action == BandMusicCommand.Action.VOLUME_DOWN)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    action == BandMusicCommand.Action.VOLUME_UP
                            ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
        } else {
            dispatch(context, action);
        }
        sendStandalone(context, BandMusicCommand.nothing(volumePercent(context)));
    }

    private static void dispatch(Context context, BandMusicCommand.Action action) {
        int code = switch (action) {
            case PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY;
            case PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE;
            case PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT;
            default -> 0;
        };
        if (code == 0) return;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return;
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    private static void sendStandalone(Context context, XiaomiProto.Command command) {
        if (!BandLiveService.notificationSessionReady(context)) return;
        int limit = BandLiveService.notificationPayloadLimit();
        if (limit <= 0) return;
        XiaomiProto.Command fitted;
        try {
            fitted = BandMusicCommand.fit(command, limit);
        } catch (IllegalArgumentException dropped) {
            return;
        }
        int state = fitted.getMusic().getMusicInfo().getState();
        int volume = fitted.getMusic().getMusicInfo().getVolume();
        BandLiveService.sendSessionCommand(fitted).whenComplete((ignored, error) -> {
            if (error == null) Log.i("OplusBandBridge", "MUSIC_OUT state=" + state + " volume=" + volume);
        });
    }

    private static boolean same(MediaController left, MediaController right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.getSessionToken().equals(right.getSessionToken());
    }

    private record Snapshot(int state, int volume, String track, String artist, int duration) {
        boolean same(Snapshot other) {
            return state == other.state && volume == other.volume && duration == other.duration
                    && track.equals(other.track) && artist.equals(other.artist);
        }
    }
}
