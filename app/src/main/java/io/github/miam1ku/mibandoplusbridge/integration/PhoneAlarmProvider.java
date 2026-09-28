// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.hook.ClockAlarmHook;
import io.github.miam1ku.mibandoplusbridge.protocol.BandAlarmCommand;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;

/** Clock process to bridge. Only the OPPO clock uid may call. */
public final class PhoneAlarmProvider extends ContentProvider {
    private static android.os.Messenger replyTo;

    /** Band dismissed or snoozed. Calls the messenger the clock left when it started ringing. */
    public static void offer(int op, int id) {
        if (op != 1 && op != 2) return;
        android.os.Messenger reply = replyTo;
        replyTo = null;
        if (reply == null) return;
        android.os.Message message = android.os.Message.obtain();
        message.arg1 = op;
        message.arg2 = id;
        try {
            reply.send(message);
        } catch (android.os.RemoteException closed) {
            android.util.Log.i("OplusBandBridge", "CLOCK_ALARM_REPLY_DEAD");
        }
    }

    @Override public boolean onCreate() { return true; }


    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!clockCaller()) return null;
        if ("status".equals(method)) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(getContext(),
                    "CLOCK_HOOK " + (extras == null ? "" : extras.getString("hook", "")));
            Bundle status = new Bundle();
            status.putString("status", "QUEUED");
            return status;
        }
        if (extras == null) return null;
        android.os.Messenger reply = extras.getParcelable("reply", android.os.Messenger.class);
        if (reply != null) replyTo = reply;
        int op = extras.getInt("op", -1);
        int id = extras.getInt("id", -1);
        int alertTimeSec = extras.getInt("alertTimeSec", -1);
        String label = extras.getString("label");
        long identity = Binder.clearCallingIdentity();
        try {
            if (io.github.miam1ku.mibandoplusbridge.notify.PhoneAlarmNotice.claim(op)) {
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(getContext(),
                        "ALARM_PHONE op=" + op + " id=" + id);
                BandLiveService.sendSessionCommand(BandAlarmCommand.operation(op, id, alertTimeSec, label));
            }
            Bundle result = new Bundle();
            result.putString("status", "QUEUED");
            return result;
        } catch (IllegalArgumentException rejected) {
            return null;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean clockCaller() {
        Context context = getContext();
        if (context == null) return false;
        String[] packages = context.getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages == null) return false;
        for (String name : packages) {
            if (ClockAlarmHook.CLOCK.equals(name)) return true;
        }
        return false;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new SecurityException("PHONE_ALARM_IPC_ONLY");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("PHONE_ALARM_IPC_ONLY"); }
    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new SecurityException("PHONE_ALARM_IPC_ONLY");
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new SecurityException("PHONE_ALARM_IPC_ONLY");
    }
}
