// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;
import io.github.miam1ku.mibandoplusbridge.protocol.BandSystemCommand;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Asks the running OHealth process to play or stop its own find-phone ring. */
public final class FindPhone {
    public static final String ACTION = "io.github.miam1ku.mibandoplusbridge.FIND_PHONE";
    public static final String PERMISSION = "io.github.miam1ku.mibandoplusbridge.permission.FIND_PHONE";
    private static final String HEALTH = "com.heytap.health";
    private static final ComponentName HEALTH_SERVICE = new ComponentName(
            HEALTH, "com.heytap.health.rpc.host.HealthRpcMsgService");

    private FindPhone() {}

    public static void onBandCommand(Context context, XiaomiProto.Command command) {
        BandSystemCommand.FindPhone action = BandSystemCommand.findPhone(command);
        if (action == null) return;
        if (action == BandSystemCommand.FindPhone.START) start(context);
        else stop(context);
    }

    public static void start(Context context) {
        dispatch(context, true);
    }

    public static void stop(Context context) {
        dispatch(context, false);
    }

    private static void dispatch(Context context, boolean start) {
        Context app = context.getApplicationContext();
        Intent ring = new Intent(ACTION).setPackage(HEALTH).putExtra("start", start);
        try {
            boolean bound = app.bindService(new Intent().setComponent(HEALTH_SERVICE), new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    app.sendBroadcast(ring);
                    Log.i("OplusBandBridge", start ? "FIND_PHONE start" : "FIND_PHONE stop");
                    try { app.unbindService(this); } catch (RuntimeException ignored) { }
                }
                @Override public void onServiceDisconnected(ComponentName name) { }
            }, Context.BIND_AUTO_CREATE);
            if (!bound) Log.i("OplusBandBridge", "FIND_PHONE native unavailable");
        } catch (RuntimeException unavailable) {
            Log.i("OplusBandBridge", "FIND_PHONE native unavailable");
        }
    }
}
