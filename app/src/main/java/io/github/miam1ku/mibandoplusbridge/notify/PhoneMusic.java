// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.util.Log;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;
import io.github.miam1ku.mibandoplusbridge.protocol.BandMusicCommand;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import java.util.List;

/**
 * Reads the phone's media session from this app's own notification listener.
 * OPPO Health only starts its watcher after its listener connects, which this ROM does not do.
 */
public final class PhoneMusic {
    private static final String TAG = "OplusBandBridge";
    private static BandNotificationListener listener;
    private static Handler main;
    private static MediaController controller;
    private static int lastState = Integer.MIN_VALUE;
    private static int lastVolume = Integer.MIN_VALUE;
    private static String lastPackage = "";
    private static String lastDrop = "";

    private static final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { publish("state"); }
        @Override public void onMetadataChanged(android.media.MediaMetadata metadata) { publish("metadata"); }
        @Override public void onSessionDestroyed() {
            controller = null;
            publish("destroyed");
        }
    };

    private static final MediaSessionManager.OnActiveSessionsChangedListener sessions =
            PhoneMusic::controllers;

    private static final BroadcastReceiver volume = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { publish("volume"); }
    };

    private PhoneMusic() {}

    /** Playback state 3 is playing. 2 and 6 stay paused. Everything else is idle. */
    static int bandState(int playbackState) {
        if (playbackState == PlaybackState.STATE_PLAYING) return 1;
        if (playbackState == PlaybackState.STATE_PAUSED || playbackState == PlaybackState.STATE_BUFFERING) {
            return 2;
        }
        return 0;
    }

    public static boolean attached() { return listener != null; }

    public static void attach(BandNotificationListener service, Handler handler) {
        listener = service;
        main = handler;
        MediaSessionManager manager = service.getSystemService(MediaSessionManager.class);
        if (manager == null) {
            drop("no-manager", "", 0, 0);
            return;
        }
        ComponentName component = new ComponentName(service, BandNotificationListener.class);
        try {
            manager.addOnActiveSessionsChangedListener(sessions, component, handler);
            controllers(manager.getActiveSessions(component));
        } catch (SecurityException denied) {
            drop("denied", "", 0, 0);
            return;
        }
        IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        service.registerReceiver(volume, filter, Context.RECEIVER_NOT_EXPORTED);
        Log.i(TAG, "MUSIC_WATCH attached");
        SessionLog.line(service, "MUSIC_WATCH attached");
    }

    public static void detach() {
        BandNotificationListener service = listener;
        listener = null;
        if (service == null) return;
        MediaSessionManager manager = service.getSystemService(MediaSessionManager.class);
        if (manager != null) {
            try { manager.removeOnActiveSessionsChangedListener(sessions); }
            catch (RuntimeException ignored) { }
        }
        try { service.unregisterReceiver(volume); } catch (RuntimeException ignored) { }
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;
        lastState = Integer.MIN_VALUE;
        Log.i(TAG, "MUSIC_WATCH detached");
    }

    /** @return false when this process is not the connected listener, so the caller can try Health */
    public static boolean handle(int key, int volumePercent, boolean refresh) {
        if (listener == null || main == null) return false;
        main.post(() -> {
            if (refresh) publish("refresh");
            else applyKey(key, volumePercent);
        });
        return true;
    }

    private static void controllers(List<MediaController> active) {
        int count = active == null ? 0 : active.size();
        MediaController next = null;
        int rank = -1;
        if (active != null) {
            for (MediaController candidate : active) {
                if (candidate == null) continue;
                PlaybackState state = candidate.getPlaybackState();
                int score = state == null ? 0 : bandState(state.getState()) == 1 ? 3
                        : bandState(state.getState()) == 2 ? 2 : 1;
                if (score > rank) {
                    rank = score;
                    next = candidate;
                }
            }
        }
        if (controller != next) {
            if (controller != null) controller.unregisterCallback(callback);
            controller = next;
            if (next != null && main != null) next.registerCallback(callback, main);
        }
        String chosen = next == null ? "none" : next.getPackageName();
        Log.i(TAG, "MUSIC_SESSIONS count=" + count + " chosen=" + chosen);
        if (listener != null) SessionLog.line(listener, "MUSIC_SESSIONS count=" + count + " chosen=" + chosen);
        publish("sessions");
    }

    private static void publish(String reason) {
        BandNotificationListener service = listener;
        if (service == null) return;
        MediaController current = controller;
        PlaybackState playback = current == null ? null : current.getPlaybackState();
        int state = playback == null ? 0 : bandState(playback.getState());
        int volume = volumePercent(service);
        String pkg = current == null ? "" : current.getPackageName();
        if (!BandLiveService.notificationSessionReady(service)) {
            drop("session", pkg, state, volume);
            return;
        }
        int limit = BandLiveService.notificationPayloadLimit();
        if (limit <= 0) {
            drop("limit", pkg, state, volume);
            return;
        }
        if (state == lastState && volume == lastVolume && pkg.equals(lastPackage) && !"refresh".equals(reason)) {
            return;
        }
        android.media.MediaMetadata metadata = current == null ? null : current.getMetadata();
        String track = metadata == null ? "" : metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata == null ? "" : metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
        long positionMs = playback == null ? 0 : playback.getPosition();
        long durationMs = metadata == null ? 0 : metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
        int position = positionMs <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, positionMs / 1000L);
        int duration = durationMs <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, durationMs / 1000L);
        try {
            var command = state == 0
                    ? BandMusicCommand.nothing(volume)
                    : BandMusicCommand.playback(volume, track == null ? "" : track, artist == null ? "" : artist,
                            position, duration, state == 1);
            BandLiveService.sendSessionCommand(BandMusicCommand.fit(command, limit));
            lastState = state;
            lastVolume = volume;
            lastPackage = pkg;
            lastDrop = "";
            String line = "MUSIC_OUT reason=" + reason + " pkg=" + (pkg.isBlank() ? "none" : pkg)
                    + " state=" + state + " volume=" + volume;
            Log.i(TAG, line);
            SessionLog.line(service, line);
        } catch (RuntimeException failure) {
            Log.i(TAG, "MUSIC_SEND_FAILED " + failure.getClass().getSimpleName());
            SessionLog.line(service, "MUSIC_SEND_FAILED " + failure.getClass().getSimpleName());
        }
    }

    private static void applyKey(int key, int requestedPercent) {
        BandNotificationListener service = listener;
        MediaController current = controller;
        String pkg = current == null ? "none" : current.getPackageName();
        try {
            if (key == 5) {
                AudioManager audio = service.getSystemService(AudioManager.class);
                if (audio == null) {
                    Log.i(TAG, "MUSIC_KEY key=5 missed pkg=" + pkg);
                    return;
                }
                int currentPercent = BandMusicCommand.percent(
                        audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        requestedPercent > currentPercent
                                ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            } else if (current == null) {
                Log.i(TAG, "MUSIC_KEY key=" + key + " missed pkg=none");
                return;
            } else {
                MediaController.TransportControls controls = current.getTransportControls();
                switch (key) {
                    case 0 -> controls.play();
                    case 1 -> controls.pause();
                    case 3 -> controls.skipToPrevious();
                    case 4 -> controls.skipToNext();
                    default -> {
                        Log.i(TAG, "MUSIC_KEY key=" + key + " ignored pkg=" + pkg);
                        return;
                    }
                }
            }
            Log.i(TAG, "MUSIC_KEY key=" + key + " pkg=" + pkg);
            SessionLog.line(service, "MUSIC_KEY key=" + key + " pkg=" + pkg);
        } catch (RuntimeException failure) {
            Log.i(TAG, "MUSIC_KEY_FAILED key=" + key + " " + failure.getClass().getSimpleName());
        }
    }

    private static int volumePercent(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return 0;
        return BandMusicCommand.percent(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    private static void drop(String reason, String pkg, int state, int volume) {
        String line = reason + " " + pkg + " " + state + " " + volume;
        if (line.equals(lastDrop)) return;
        lastDrop = line;
        String message = "MUSIC_DROP reason=" + reason + " pkg=" + (pkg == null || pkg.isBlank() ? "none" : pkg)
                + " state=" + state + " volume=" + volume;
        Log.i(TAG, message);
        if (listener != null) SessionLog.line(listener, message);
    }
}
