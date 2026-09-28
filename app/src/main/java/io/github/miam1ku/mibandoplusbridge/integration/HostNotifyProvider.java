// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import java.time.Instant;
import java.time.ZoneId;

/** OHealth process cannot see BandLiveService.instance. This is the only notify IPC. */
public final class HostNotifyProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://io.github.miam1ku.mibandoplusbridge.notify");

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        HostIdentity.requireCaller(getContext(), HostIdentity.HEALTH_PACKAGE);
        if ("findWatch".equals(method)) return findWatch(extras);
        if ("music".equals(method)) return music(extras);
        if (!"forward".equals(method) || extras == null) {
            throw new SecurityException("NOTIFY_METHOD_UNSUPPORTED");
        }
        long identity = Binder.clearCallingIdentity();
        try {
            boolean removed = extras.getBoolean("removed", false);
            String pkg = extras.getString("pkg", "");
            String key = extras.getString("key", "");
            if (pkg.isBlank() || key.isBlank()) {
                Bundle result = new Bundle();
                result.putString("status", "NOTIFY_IDENTITY_REQUIRED");
                return result;
            }
            int id = extras.getInt("id", 0);
            if (id == 0) id = Math.max(1, key.hashCode() & 0x7fffffff);
            boolean call = extras.getBoolean("call", false);
            Instant when = Instant.ofEpochMilli(Math.max(1, extras.getLong("when", System.currentTimeMillis())));
            var command = call
                    ? (removed ? BandNotificationCommand.endCall()
                            : BandNotificationCommand.incomingCall(
                                    extras.getString("title"), extras.getString("body"),
                                    when, ZoneId.systemDefault(), false))
                    : (removed
                            ? BandNotificationCommand.dismiss(pkg, key, id)
                            : BandNotificationCommand.post(pkg,
                                    blankTo(extras.getString("app"), pkg),
                                    key, id, extras.getString("title", ""), extras.getString("body", ""),
                                    when, ZoneId.systemDefault()));
            BandLiveService.forwardHostNotification(getContext(), command);
            Bundle result = new Bundle();
            result.putString("status", "QUEUED");
            return result;
        } catch (RuntimeException failure) {
            Log.i("OplusBandBridge", "HOST_NOTIFY_IPC_FAILED " + failure.getClass().getSimpleName());
            Bundle result = new Bundle();
            result.putString("status", "FAILED");
            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Bundle findWatch(Bundle extras) {
        long identity = Binder.clearCallingIdentity();
        try {
            boolean start = extras == null || extras.getBoolean("start", true);
            BandLiveService.sendSessionCommand(
                    io.github.miam1ku.mibandoplusbridge.protocol.BandSystemCommand.findWatch(start));
            Bundle result = new Bundle();
            result.putString("status", "QUEUED");
            return result;
        } catch (RuntimeException failure) {
            Log.i("OplusBandBridge", "FIND_WATCH_IPC_FAILED " + failure.getClass().getSimpleName());
            Bundle result = new Bundle();
            result.putString("status", "FAILED");
            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Bundle music(Bundle extras) {
        long identity = Binder.clearCallingIdentity();
        try {
            if (extras == null || !BandLiveService.notificationSessionReady(getContext())) {
                Bundle result = new Bundle();
                result.putString("status", "FAILED");
                return result;
            }
            int state = extras.getInt("state", 0);
            int volume = extras.getInt("volume", 0);
            var command = state == 0
                    ? io.github.miam1ku.mibandoplusbridge.protocol.BandMusicCommand.nothing(volume)
                    : io.github.miam1ku.mibandoplusbridge.protocol.BandMusicCommand.playback(
                            volume, extras.getString("track", ""), extras.getString("artist", ""),
                            extras.getInt("position", 0), extras.getInt("duration", 0), state == 1);
            int limit = BandLiveService.notificationPayloadLimit();
            command = io.github.miam1ku.mibandoplusbridge.protocol.BandMusicCommand.fit(command, limit);
            BandLiveService.sendSessionCommand(command);
            Log.i("OplusBandBridge", "MUSIC_OUT state=" + command.getMusic().getMusicInfo().getState()
                    + " volume=" + command.getMusic().getMusicInfo().getVolume());
            Bundle result = new Bundle();
            result.putString("status", "QUEUED");
            return result;
        } catch (RuntimeException failure) {
            Log.i("OplusBandBridge", "MUSIC_IPC_FAILED " + failure.getClass().getSimpleName());
            Bundle result = new Bundle();
            result.putString("status", "FAILED");
            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new SecurityException("NOTIFY_IPC_ONLY");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("NOTIFY_IPC_ONLY"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new SecurityException("NOTIFY_IPC_ONLY"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new SecurityException("NOTIFY_IPC_ONLY");
    }
}
