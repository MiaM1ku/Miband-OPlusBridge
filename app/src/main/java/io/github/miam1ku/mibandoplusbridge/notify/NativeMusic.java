// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Asks the running OHealth music controller to refresh or handle a band key. */
public final class NativeMusic {
    public static final String ACTION = "io.github.miam1ku.mibandoplusbridge.MUSIC";
    private static final String HEALTH = "com.heytap.health";
    private static final ComponentName HEALTH_SERVICE = new ComponentName(
            HEALTH, "com.heytap.health.rpc.host.HealthRpcMsgService");

    private NativeMusic() {}

    public static void onBandCommand(Context context, XiaomiProto.Command command) {
        if (command == null || command.getType() != 18) return;
        int subtype = command.getSubtype();
        if (subtype == 0) dispatch(context, 0, 0, true);
        else if (subtype == 2 && command.hasMusic() && command.getMusic().hasMediaKey()) {
            var key = command.getMusic().getMediaKey();
            dispatch(context, key.getKey(), key.getVolume(), false);
        }
    }

    private static void dispatch(Context context, int key, int volume, boolean refresh) {
        Context app = context.getApplicationContext();
        Intent event = new Intent(ACTION).setPackage(HEALTH)
                .putExtra("refresh", refresh)
                .putExtra("key", key)
                .putExtra("volume", volume);
        try {
            boolean bound = app.bindService(new Intent().setComponent(HEALTH_SERVICE), new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    app.sendBroadcast(event);
                    if (!refresh) Log.i("OplusBandBridge", "MUSIC_KEY " + key);
                    try { app.unbindService(this); } catch (RuntimeException ignored) { }
                }

                @Override public void onServiceDisconnected(ComponentName name) { }
            }, Context.BIND_AUTO_CREATE);
            if (!bound) Log.i("OplusBandBridge", "MUSIC native unavailable");
        } catch (RuntimeException unavailable) {
            Log.i("OplusBandBridge", "MUSIC native unavailable");
        }
    }
}
