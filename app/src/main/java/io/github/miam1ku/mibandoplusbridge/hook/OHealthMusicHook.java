// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider;
import io.github.miam1ku.mibandoplusbridge.notify.FindPhone;
import io.github.miam1ku.mibandoplusbridge.notify.NativeMusic;
import io.github.miam1ku.mibandoplusbridge.protocol.BandMusicCommand;

/** OHealth already watches the phone's media session. This copies that state to the band. */
public final class OHealthMusicHook {
    private static final String PRESENTER =
            "com.heytap.health.watch.music.control.SendInfoPresenter$Companion";
    private static final String MANAGER = "com.heytap.health.watch.music.control.MusicControlManager";
    private static Context app;
    private static int lastState = Integer.MIN_VALUE;
    private static int lastVolume = Integer.MIN_VALUE;
    private static int lastDuration = Integer.MIN_VALUE;
    private static String lastTrack = "";
    private static String lastArtist = "";

    private OHealthMusicHook() {}

    public static void install(Context context, ClassLoader loader) {
        String process = android.app.Application.getProcessName();
        if (process == null || !process.startsWith("com.heytap.health")) return;
        if (!process.equals("com.heytap.health:transport")) return;
        app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        Class<?> presenter;
        try {
            presenter = XposedHelpers.findClass(PRESENTER, loader);
        } catch (Throwable failure) {
            android.util.Log.i("OplusBandBridge", "MUSIC native unavailable");
            return;
        }
        XC_MethodHook mirror = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                mirror(param.method.getName(), param.args);
            }
        };
        XposedBridge.hookAllMethods(presenter, "sendPlayInfo", mirror);
        XposedBridge.hookAllMethods(presenter, "sendPlayState", mirror);
        XposedBridge.hookAllMethods(presenter, "sendTotalInfo", mirror);
        XposedBridge.hookAllMethods(presenter, "sendVolumeInfo", mirror);
        XposedBridge.hookAllMethods(presenter, "sendMusicCloseInfo", mirror);
        XposedBridge.hookAllMethods(presenter, "responseTotalInfo", mirror);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || !NativeMusic.ACTION.equals(intent.getAction())) return;
                if (intent.getBooleanExtra("refresh", false)) refresh(loader);
                else key(loader, intent.getIntExtra("key", -1), intent.getIntExtra("volume", 0));
            }
        }, new IntentFilter(NativeMusic.ACTION), FindPhone.PERMISSION, null, Context.RECEIVER_EXPORTED);
        try {
            Class<?> service = XposedHelpers.findClass(
                    "com.heytap.health.watch.music.control.MusicService", loader);
            XposedBridge.hookAllMethods(service, "handleNoControllers", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    publish(true, 0, Math.max(0, lastVolume), "", "", 0, 0);
                }
            });
            // The manager's static block is what registers the media-session listener.
            // Nothing else loads it until an OPPO watch message arrives.
            XposedHelpers.getStaticObjectField(XposedHelpers.findClass(MANAGER, loader), "INSTANCE");
        } catch (Throwable failure) {
            android.util.Log.i("OplusBandBridge", "MUSIC native unavailable");
        }
    }

    private static void mirror(String method, Object[] args) {
        try {
            if ("sendMusicCloseInfo".equals(method)) {
                publish(true, 0, Math.max(0, lastVolume), "", "", 0, 0);
                return;
            }
            if ("sendVolumeInfo".equals(method)) {
                int percent = BandMusicCommand.percent(number(args, 0), number(args, 1));
                publish(false, lastState < 0 ? 0 : lastState, percent, lastTrack, lastArtist,
                        0, Math.max(0, lastDuration));
                return;
            }
            int base = "responseTotalInfo".equals(method) ? 1 : 0;
            Object playback = args != null && args.length > base ? args[base] : null;
            Object metadata = args != null && args.length > base + 1 ? args[base + 1] : null;
            int volume = number(args, base + 4);
            int max = number(args, base + 5);
            boolean force = "sendTotalInfo".equals(method) || "responseTotalInfo".equals(method)
                    || "sendPlayInfo".equals(method);
            if (!(playback instanceof PlaybackState stateObject)) {
                publish(force, metadata instanceof MediaMetadata ? 2 : 0,
                        BandMusicCommand.percent(volume, max), "", "", 0, 0);
                return;
            }
            MediaMetadata meta = metadata instanceof MediaMetadata cast ? cast : null;
            int state = stateObject.getState() == PlaybackState.STATE_PLAYING ? 1 : 2;
            long positionMs = stateObject.getPosition();
            int position = positionMs <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, positionMs / 1000L);
            long durationMs = meta == null ? 0 : meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
            int duration = durationMs <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, durationMs / 1000L);
            String title = meta == null ? null : meta.getString(MediaMetadata.METADATA_KEY_TITLE);
            String name = meta == null ? null : meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
            publish(force, state, BandMusicCommand.percent(volume, max),
                    title == null ? "" : title, name == null ? "" : name, position, duration);
        } catch (RuntimeException failure) {
            android.util.Log.i("OplusBandBridge", "MUSIC native unavailable");
        }
    }

    private static void publish(boolean force, int state, int volume, String track, String artist,
                                int position, int duration) {
        volume = Math.max(0, Math.min(100, volume));
        if (!force && state == lastState && volume == lastVolume && duration == lastDuration
                && track.equals(lastTrack) && artist.equals(lastArtist)) return;
        if (app == null) return;
        Bundle extras = new Bundle();
        extras.putInt("state", state);
        extras.putInt("volume", volume);
        extras.putString("track", track);
        extras.putString("artist", artist);
        extras.putInt("position", Math.max(0, position));
        extras.putInt("duration", Math.max(0, duration));
        try {
            Bundle result = app.getContentResolver().call(HostNotifyProvider.URI, "music", null, extras);
            if (result == null || !"QUEUED".equals(result.getString("status"))) return;
            lastState = state;
            lastVolume = volume;
            lastDuration = duration;
            lastTrack = track;
            lastArtist = artist;
        } catch (RuntimeException failure) {
            android.util.Log.i("OplusBandBridge", "MUSIC native unavailable");
        }
    }

    private static void refresh(ClassLoader loader) {
        Object service = service(loader);
        if (service == null) {
            android.util.Log.i("OplusBandBridge", "MUSIC native unavailable");
            return;
        }
        try {
            XposedHelpers.callMethod(service, "onRequestTotalInfo");
        } catch (Throwable failure) {
            android.util.Log.i("OplusBandBridge", "MUSIC native unavailable");
        }
    }

    private static void key(ClassLoader loader, int key, int requestedPercent) {
        Object listener = listener(loader);
        if (listener == null || app == null) {
            android.util.Log.i("OplusBandBridge", "MUSIC native unavailable");
            return;
        }
        try {
            if (key == 5) {
                AudioManager audio = app.getSystemService(AudioManager.class);
                int current = audio == null ? 0 : BandMusicCommand.percent(
                        audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                XposedHelpers.callMethod(listener, "adjustVolume", requestedPercent > current ? 1 : -1);
            } else {
                int code = switch (key) {
                    case 0 -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY;
                    case 1 -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE;
                    case 3 -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                    case 4 -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
                    default -> -1;
                };
                if (code < 0) return;
                XposedHelpers.callMethod(listener, "dispatchMediaKeyEvent", code);
            }
        } catch (Throwable failure) {
            android.util.Log.i("OplusBandBridge", "MUSIC native unavailable");
        }
    }

    private static Object listener(ClassLoader loader) {
        try {
            Class<?> manager = XposedHelpers.findClass(MANAGER, loader);
            return XposedHelpers.getStaticObjectField(manager, "mListener");
        } catch (Throwable failure) {
            return null;
        }
    }

    private static Object service(ClassLoader loader) {
        Object listener = listener(loader);
        if (listener == null) return null;
        try {
            return XposedHelpers.getObjectField(listener, "mMusicService");
        } catch (Throwable failure) {
            return null;
        }
    }

    private static int number(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length || !(args[index] instanceof Number value)) return 0;
        return value.intValue();
    }
}
