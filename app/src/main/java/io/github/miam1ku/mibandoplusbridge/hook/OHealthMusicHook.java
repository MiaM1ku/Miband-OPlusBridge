// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedBridge;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider;
import io.github.miam1ku.mibandoplusbridge.notify.FindPhone;
import io.github.miam1ku.mibandoplusbridge.notify.NativeMusic;
import io.github.miam1ku.mibandoplusbridge.protocol.BandMusicCommand;

/** OHealth already watches the phone's media session. This copies that state to the band. */
public final class OHealthMusicHook {
    private static final String PRESENTER =
            "com.heytap.health.watch.music.control.SendInfoPresenter$Companion";
    private static final String PRESENTER_6117 = "com.heytap.health.watch.music.control.z";
    private static final String MANAGER = "com.heytap.health.watch.music.control.MusicControlManager";
    private static final String MANAGER_6117 = "com.heytap.health.watch.music.control.r";
    private static final String SERVICE = "com.heytap.health.watch.music.control.MusicService";
    private static final String SERVICE_6117 = "com.heytap.health.watch.music.control.w";
    private static final String HANDLER_6117 = "ay1.d";
    private static Context app;
    private static volatile boolean renamedBuild;
    private static int lastState = Integer.MIN_VALUE;
    private static int lastVolume = Integer.MIN_VALUE;
    private static int lastDuration = Integer.MIN_VALUE;
    private static String lastTrack = "";
    private static String lastArtist = "";
    private static long lastPublishedNanos;

    private OHealthMusicHook() {}

    public static void install(Context context, ClassLoader loader) {
        String process = android.app.Application.getProcessName();
        if (process == null || !process.startsWith("com.heytap.health")) return;
        app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        trace("MUSIC_HOOK process=" + process);
        Class<?> presenter = null;
        boolean renamed = false;
        try {
            presenter = XposedHelpers.findClass(PRESENTER, loader);
        } catch (Throwable missing) {
            try {
                presenter = XposedHelpers.findClass(PRESENTER_6117, loader);
                renamed = true;
            } catch (Throwable failure) {
                HookRuntime.log("MUSIC native unavailable");
                trace("MUSIC_HOOK presenter-missing");
                return;
            }
        }
        renamedBuild = renamed;
        if (!renamed) {
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
        } else {
            XposedBridge.hookAllMethods(presenter, "i", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    mirror("sendMusicCloseInfo", param.args);
                }
            });
            XposedBridge.hookAllMethods(presenter, "g", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    mirror("responseTotalInfo", param.args);
                }
            });
        }
        if (!"com.heytap.health:transport".equals(process)) return;
        HookRuntime.receive(app, new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || !NativeMusic.ACTION.equals(intent.getAction())) return;
                if (intent.getBooleanExtra("refresh", false)) refresh(loader);
                else key(loader, intent.getIntExtra("key", -1), intent.getIntExtra("volume", 0));
            }
        }, new IntentFilter(NativeMusic.ACTION), FindPhone.PERMISSION, null, Context.RECEIVER_EXPORTED);
        try {
            Class<?> service = XposedHelpers.findClass(renamed ? SERVICE_6117 : SERVICE, loader);
            XposedBridge.hookAllMethods(service, renamed ? "c" : "handleNoControllers", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    publish(false, 0, Math.max(0, lastVolume), "", "", 0, 0);
                }
            });
            if (renamed) {
                // 6.11.7 inlined sendTotalInfo/sendPlayState/sendPlayInfo/sendVolumeInfo into w.g() and ay1.d.a(Message).
                XposedBridge.hookAllMethods(service, "g", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        mirrorServiceState(param.thisObject, true, false);
                    }
                });
                Class<?> handlerClass = XposedHelpers.findClass(HANDLER_6117, loader);
                java.lang.reflect.Method dispatch = handlerClass.getDeclaredMethod("a", android.os.Message.class);
                dispatch.setAccessible(true);
                XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length == 0 || !(param.args[0] instanceof android.os.Message msg)) return;
                        int what = msg.what;
                        if (what != 2 && what != 3 && what != 6) return;
                        Object owner = XposedHelpers.getObjectField(param.thisObject, "b");
                        if (!service.isInstance(owner)) return;
                        mirrorServiceState(owner, what == 3, what == 6);
                    }
                });
            }
            // The manager's static block is what registers the media-session listener.
            // Nothing else loads it until an OPPO watch message arrives.
            XposedHelpers.getStaticObjectField(XposedHelpers.findClass(renamed ? MANAGER_6117 : MANAGER, loader), "INSTANCE");
        } catch (Throwable failure) {
            HookRuntime.log("MUSIC native unavailable");
            trace("MUSIC_HOOK manager-missing " + failure.getClass().getSimpleName());
        }
    }

    private static void mirrorServiceState(Object musicService, boolean force, boolean volumeOnly) {
        if (musicService == null) return;
        try {
            Object wrapper = XposedHelpers.getObjectField(musicService, "i");
            Object volumeObserver = XposedHelpers.getObjectField(musicService, "f");
            int volume = volumeObserver == null ? 0 : number(new Object[]{XposedHelpers.callMethod(volumeObserver, "a")}, 0);
            int max = volumeObserver == null ? 0 : number(new Object[]{XposedHelpers.callMethod(volumeObserver, "b")}, 0);
            if (volumeOnly) {
                if (wrapper == null) return;
                int percent = BandMusicCommand.percent(volume, max);
                publish(false, lastState < 0 ? 0 : lastState, percent, lastTrack, lastArtist,
                        0, Math.max(0, lastDuration));
                return;
            }
            if (wrapper == null) return;
            Object playback = XposedHelpers.callMethod(wrapper, "d");
            Object metadata = XposedHelpers.callMethod(wrapper, "c");
            publishMedia(force, playback, metadata, volume, max);
        } catch (Throwable failure) {
            HookRuntime.log("MUSIC native unavailable");
        }
    }

    private static void mirror(String method, Object[] args) {
        try {
            if ("sendMusicCloseInfo".equals(method)) {
                publish(false, 0, Math.max(0, lastVolume), "", "", 0, 0);
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
            publishMedia(force, playback, metadata, volume, max);
        } catch (RuntimeException failure) {
            HookRuntime.log("MUSIC native unavailable");
        }
    }

    private static void publishMedia(boolean force, Object playback, Object metadata, int volume, int max) {
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
    }

    private static void publish(boolean force, int state, int volume, String track, String artist,
                                int position, int duration) {
        volume = Math.max(0, Math.min(100, volume));
        boolean same = state == lastState && volume == lastVolume && duration == lastDuration
                && java.util.Objects.equals(track, lastTrack) && java.util.Objects.equals(artist, lastArtist);
        if (MusicRepeat.suppress(force, same, System.nanoTime() - lastPublishedNanos)) return;
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
            lastPublishedNanos = System.nanoTime();
        } catch (RuntimeException failure) {
            HookRuntime.log("MUSIC native unavailable");
            trace("MUSIC_HOOK publish-failed " + failure.getClass().getSimpleName());
        }
    }

    private static void refresh(ClassLoader loader) {
        Object service = service(loader);
        if (service == null) {
            HookRuntime.log("MUSIC native unavailable");
            return;
        }
        try {
            if (!renamedBuild) {
                XposedHelpers.callMethod(service, "onRequestTotalInfo");
            } else {
                android.os.Handler handler = (android.os.Handler) XposedHelpers.getObjectField(service, "c");
                handler.sendEmptyMessage(7);
            }
        } catch (Throwable failure) {
            HookRuntime.log("MUSIC native unavailable");
        }
    }

    private static void key(ClassLoader loader, int key, int requestedPercent) {
        Object listener = listener(loader);
        if (listener == null || app == null) {
            HookRuntime.log("MUSIC native unavailable");
            return;
        }
        try {
            if (key == 5) {
                AudioManager audio = app.getSystemService(AudioManager.class);
                int current = audio == null ? 0 : BandMusicCommand.percent(
                        audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                XposedHelpers.callMethod(listener, renamedBuild ? "j" : "adjustVolume",
                        requestedPercent > current ? 1 : -1);
            } else {
                int code = switch (key) {
                    case 0 -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY;
                    case 1 -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE;
                    case 3 -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                    case 4 -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
                    default -> -1;
                };
                if (code < 0) return;
                if (!renamedBuild) {
                    XposedHelpers.callMethod(listener, "dispatchMediaKeyEvent", code);
                } else {
                    XposedHelpers.callMethod(listener, "k");
                    Object service = XposedHelpers.getObjectField(listener, "b");
                    if (service == null) return;
                    android.os.Handler handler = (android.os.Handler) XposedHelpers.getObjectField(service, "c");
                    android.os.Message msg = handler.obtainMessage(5);
                    msg.arg1 = code;
                    handler.sendMessage(msg);
                }
            }
        } catch (Throwable failure) {
            HookRuntime.log("MUSIC native unavailable");
        }
    }

    private static Object listener(ClassLoader loader) {
        try {
            Class<?> manager = XposedHelpers.findClass(renamedBuild ? MANAGER_6117 : MANAGER, loader);
            return XposedHelpers.getStaticObjectField(manager, renamedBuild ? "i" : "mListener");
        } catch (Throwable failure) {
            return null;
        }
    }

    private static Object service(ClassLoader loader) {
        Object listener = listener(loader);
        if (listener == null) return null;
        try {
            return XposedHelpers.getObjectField(listener, renamedBuild ? "b" : "mMusicService");
        } catch (Throwable failure) {
            return null;
        }
    }

    private static int number(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length || !(args[index] instanceof Number value)) return 0;
        return value.intValue();
    }

    private static void trace(String line) {
        if (app == null || line == null || line.isBlank()) return;
        try {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString("line", line);
            app.getContentResolver().call(HostNotifyProvider.URI, "trace", null, extras);
        } catch (RuntimeException ignored) { }
    }
}
