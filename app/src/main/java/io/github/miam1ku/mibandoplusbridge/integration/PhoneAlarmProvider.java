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
    private static volatile int pendingOp = -1;
    private static volatile int pendingId = -1;
    private static Context notifier;
    private static final Uri CHANGES = Uri.parse("content://io.github.miam1ku.mibandoplusbridge.phone-alarm");

    /** Band dismissed or snoozed. The ringing clock is observing CHANGES. */
    public static void offer(int op, int id) {
        if (op != 1 && op != 2) return;
        pendingOp = op;
        pendingId = id;
        Context context = notifier;
        if (context != null) context.getContentResolver().notifyChange(CHANGES, null);
    }

    @Override public boolean onCreate() {
        notifier = getContext();
        return true;
    }


    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!clockCaller()) return null;
        if ("take".equals(method)) return take();
        if (extras == null) return null;
        int op = extras.getInt("op", -1);
        int id = extras.getInt("id", -1);
        int alertTimeSec = extras.getInt("alertTimeSec", -1);
        String label = extras.getString("label");
        long identity = Binder.clearCallingIdentity();
        try {
            BandLiveService.sendSessionCommand(BandAlarmCommand.operation(op, id, alertTimeSec, label));
            Bundle result = new Bundle();
            result.putString("status", "QUEUED");
            return result;
        } catch (IllegalArgumentException rejected) {
            return null;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static Bundle take() {
        int op = pendingOp;
        if (op != 1 && op != 2) return null;
        pendingOp = -1;
        Bundle result = new Bundle();
        result.putInt("op", op);
        result.putInt("id", pendingId);
        return result;
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
