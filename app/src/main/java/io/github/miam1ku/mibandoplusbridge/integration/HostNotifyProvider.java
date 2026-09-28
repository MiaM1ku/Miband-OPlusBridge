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
import io.github.miam1ku.mibandoplusbridge.notify.CallPresentation;
import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import java.time.Instant;
import java.time.ZoneId;

/** OHealth process cannot see BandLiveService.instance. This is the only notify IPC. */
public final class HostNotifyProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://io.github.miam1ku.mibandoplusbridge.notify");
    private int postedCallState;
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        HostIdentity.requireCaller(getContext(), HostIdentity.HEALTH_PACKAGE);
        if ("trace".equals(method)) return trace(extras);
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
            if (extras.getBoolean("phoneAlarm", false)) {
                io.github.miam1ku.mibandoplusbridge.notify.PhoneAlarmNotice.send(getContext(),
                        extras.getInt("alarmOp", removed ? 1 : 0), extras.getString("title", ""));
                Bundle alarm = new Bundle();
                alarm.putString("status", "QUEUED");
                return alarm;
            }
            int id = extras.getInt("id", 0);
            if (id == 0) id = Math.max(1, key.hashCode() & 0x7fffffff);
            boolean call = extras.getBoolean("call", false);
            int callState = call ? callState(extras) : 0;
            if (call && !removed && callState == 0) {
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(getContext(), "CALL_HOST_UNRESOLVED");
                Bundle pending = new Bundle();
                pending.putString("status", "QUEUED");
                return pending;
            }
            if (call && BandLiveService.callsOwned()) {
                if (removed) postedCallState = 0;
                else if (callState == BandNotificationCommand.CALL_ACTIVE) BandLiveService.noteCallAnswered();
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(getContext(),
                        "CALL_HOST_SKIPPED removed=" + removed + " state=" + callState);
                Bundle owned = new Bundle();
                owned.putString("status", "QUEUED");
                return owned;
            }
            int previous;
            synchronized (this) {
                if (call && !removed && callState == postedCallState) {
                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(getContext(),
                            "CALL_HOST_UNCHANGED state=" + callState);
                    Bundle unchanged = new Bundle();
                    unchanged.putString("status", "QUEUED");
                    return unchanged;
                }
                previous = postedCallState;
                if (call) postedCallState = removed ? 0 : callState;
            }
            Instant when = Instant.ofEpochMilli(Math.max(1, extras.getLong("when", System.currentTimeMillis())));
            String app = blankTo(extras.getString("app"), "");
            if (app.isBlank() || app.equals(pkg)) {
                String resolved = io.github.miam1ku.mibandoplusbridge.notify.AppLabels.label(getContext(), pkg);
                if (!resolved.isBlank()) app = resolved;
            }
            if (app.isBlank()) app = pkg;
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(getContext(),
                    call ? "CALL_HOST removed=" + removed + " state=" + callState + " previous=" + previous
                            : "NOTIFY_HOST pkg=" + pkg + " app=" + (app.equals(pkg) ? "package" : "label")
                                    + " removed=" + removed + " call=false");
            var command = call
                    ? (removed ? BandNotificationCommand.endCall()
                            : BandNotificationCommand.call(
                                    CallPresentation.displayName(extras.getString("title"),
                                            CallPresentation.kind(callState)),
                                    null, callState, when, ZoneId.systemDefault(), false))
                    : (removed
                            ? BandNotificationCommand.dismiss(pkg, key, id)
                            : BandNotificationCommand.post(pkg, app, key, id,
                                    extras.getString("title", ""), extras.getString("body", ""),
                                    when, ZoneId.systemDefault()));
            if (call && !removed && previous != 0) {
                var next = command;
                BandLiveService.forwardHostNotification(getContext(), BandNotificationCommand.endCall())
                        .whenComplete((ignored, error) -> BandLiveService.forwardHostNotification(getContext(), next));
                Bundle replaced = new Bundle();
                replaced.putString("status", "QUEUED");
                return replaced;
            }
            var pending = BandLiveService.forwardHostNotification(getContext(), command);
            String status = "QUEUED";
            if (pending.toCompletableFuture().isCompletedExceptionally()) {
                try {
                    pending.toCompletableFuture().getNow(null);
                } catch (Throwable failure) {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    status = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                    Log.i("OplusBandBridge", "NOTIFY_SEND_FAILED " + status + " pkg=" + pkg);
                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(getContext(),
                            "NOTIFY_SEND_FAILED " + status + " pkg=" + pkg);
                }
            }
            Bundle result = new Bundle();
            result.putString("status", status);
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
    private Bundle trace(Bundle extras) {
        String line = extras == null ? "" : extras.getString("line", "");
        if (line.length() > 240) line = line.substring(0, 240);
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(getContext(), "health " + line);
        Bundle result = new Bundle();
        result.putString("status", "LOGGED");
        return result;
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
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(getContext(), "MUSIC_DROP reason=session");
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
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(getContext(),
                    "MUSIC_OUT state=" + command.getMusic().getMusicInfo().getState()
                    + " volume=" + command.getMusic().getMusicInfo().getVolume());
            Bundle result = new Bundle();
            result.putString("status", "QUEUED");
            return result;
        } catch (RuntimeException failure) {
            Log.i("OplusBandBridge", "MUSIC_IPC_FAILED " + failure.getClass().getSimpleName());
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(getContext(),
                    "MUSIC_IPC_FAILED " + failure.getClass().getSimpleName());
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

    private static int callState(Bundle extras) {
        int stated = extras.getInt("callState", 0);
        if (stated == BandNotificationCommand.CALL_INCOMING
                || stated == BandNotificationCommand.CALL_ACTIVE
                || stated == BandNotificationCommand.CALL_OUTGOING) return stated;
        return CallPresentation.wire(CallPresentation.kind(
                extras.getBoolean("chronometer", false) || extras.getBoolean("liveChronometer", false),
                false, extras.getInt("callType", 0),
                extras.getString("body", ""), extras.getString("title", ""), extras.getString("subText", "")));
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
