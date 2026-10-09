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
import io.github.miam1ku.mibandoplusbridge.service.HostKeepAlive;
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
        if ("policy".equals(method)) return policy(extras);
        if ("policySnapshot".equals(method)) return policySnapshot(extras);
        if ("healthListener".equals(method)) return healthListener(extras);
        if ("listener".equals(method)) return listenerState();
        if ("findWatch".equals(method) || "music".equals(method) || "forward".equals(method)) {
            HostKeepAlive.ensureBridge(getContext());
        }
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

    private static final java.util.concurrent.atomic.AtomicBoolean HEALTH_GRANT_TRIED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private Bundle healthListener(Bundle extras) {
        long identity = Binder.clearCallingIdentity();
        try {
            return healthListenerBody(extras);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Bundle healthListenerBody(Bundle extras) {
        boolean connected = extras != null && extras.getBoolean("connected", false);
        boolean reportsConnection = extras != null && extras.containsKey("connected");
        String process = extras == null ? "" : extras.getString("process", "");
        if (reportsConnection && (connected || process.endsWith(":transport"))) {
            io.github.miam1ku.mibandoplusbridge.notify.HealthListenerState.connected(connected);
        }
        var prefs = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(getContext(),
                io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.SETTINGS);
        var edit = prefs.edit();
        if (extras != null && extras.containsKey("approved")) {
            boolean approved = extras.getBoolean("approved");
            boolean secure = extras.getBoolean("secure");
            edit.putBoolean("healthApproved", approved).putBoolean("healthSecure", secure);
            if (!approved && secure && HEALTH_GRANT_TRIED.compareAndSet(false, true)) {
                boolean ok = io.github.miam1ku.mibandoplusbridge.service.OwnershipController.allowHealthListener();
                edit.putBoolean("healthGrantFailed", !ok);
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(getContext(),
                        "HEALTH_LISTENER_ALLOW " + (ok ? "ok" : "failed"));
            }
        }
        edit.commit();
        String line = "HEALTH_LISTENER connected=" + io.github.miam1ku.mibandoplusbridge.notify.HealthListenerState.connected()
                + " approved=" + (extras != null && extras.getBoolean("approved"))
                + " secure=" + (extras != null && extras.getBoolean("secure"))
                + " process=" + (process.isBlank() ? "none" : process);
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(getContext(), line);
        Bundle result = new Bundle();
        result.putString("status", "OK");
        return result;
    }

    private Bundle policySnapshot(Bundle extras) {
        long identity = Binder.clearCallingIdentity();
        try {
            return policySnapshotBody(extras);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Bundle policySnapshotBody(Bundle extras) {
        var edit = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(getContext(),
                io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.SETTINGS).edit();
        edit.putBoolean("policyKnown", extras != null);
        edit.putBoolean("policyMain", extras != null && extras.getBoolean("main", true));
        edit.putBoolean("policyScreenOnPush", extras != null && extras.getBoolean("screenOnPush", true));
        edit.putString("policyOpenList", join(extras == null ? null : extras.getStringArray("open")));
        edit.putString("policyClosedList", join(extras == null ? null : extras.getStringArray("closed")));
        if (extras != null && extras.containsKey("defaults")) {
            edit.putBoolean("policyDefaultsKnown", true);
            edit.putString("policyDefaultsList", join(extras.getStringArray("defaults")));
        } else {
            edit.putBoolean("policyDefaultsKnown", false);
            edit.remove("policyDefaultsList");
        }
        edit.putString("policyMmsOrder", join(extras == null ? null : extras.getStringArray("mms")));
        edit.putLong("policyAtMs", System.currentTimeMillis());
        edit.commit();
        Bundle result = new Bundle();
        result.putString("status", "OK");
        return result;
    }

    private android.content.SharedPreferences notificationPrefs() {
        return getContext().getSharedPreferences(
                io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.SETTINGS,
                android.content.Context.MODE_PRIVATE);
    }

    private static java.util.Set<String> setOf(Bundle extras, String key) {
        java.util.Set<String> values = new java.util.HashSet<>();
        if (extras == null) return values;
        String[] items = extras.getStringArray(key);
        if (items == null) return values;
        for (String item : items) {
            if (item != null && !item.isBlank()) values.add(item);
        }
        return values;
    }

    private static String join(String[] items) {
        if (items == null || items.length == 0) return "";
        StringBuilder builder = new StringBuilder();
        for (String item : items) {
            if (item == null || item.isBlank() || item.indexOf('\n') >= 0) continue;
            if (builder.length() > 0) builder.append('\n');
            builder.append(item);
        }
        return builder.toString();
    }

    private Bundle listenerState() {
        Bundle result = new Bundle();
        result.putBoolean("connected",
                io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.listenerConnected());
        result.putString("status", "OK");
        return result;
    }

    private Bundle policy(Bundle extras) {
        var prefs = getContext().getSharedPreferences(
                io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.SETTINGS,
                android.content.Context.MODE_PRIVATE);
        var edit = prefs.edit();
        if (extras != null && extras.containsKey("main")) {
            edit.putBoolean("mainSwitchKnown", true).putBoolean("mainSwitch", extras.getBoolean("main"));
        }
        if (extras != null && extras.containsKey("screenOnPush")) {
            edit.putBoolean("screenOnPush", extras.getBoolean("screenOnPush"));
        }
        String pkg = extras == null ? "" : extras.getString("pkg", "");
        if (extras != null && !pkg.isBlank() && extras.containsKey("packageOn")) {
            java.util.Set<String> denied = new java.util.HashSet<>(prefs.getStringSet("deniedPackages", java.util.Set.of()));
            if (extras.getBoolean("packageOn")) denied.remove(pkg);
            else if (denied.size() < io.github.miam1ku.mibandoplusbridge.notify.NotificationRelay.CAPACITY) denied.add(pkg);
            edit.putStringSet("deniedPackages", denied);
        }
        edit.apply();
        Bundle result = new Bundle();
        result.putString("status", "OK");
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
