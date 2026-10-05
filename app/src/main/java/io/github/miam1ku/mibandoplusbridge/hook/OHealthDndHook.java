// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd;

/** Health is a privileged app and can write zen_mode. The bridge process cannot. */
public final class OHealthDndHook {
    public static final String ACTION = "io.github.miam1ku.mibandoplusbridge.action.SET_DND";
    public static final String PERMISSION = "io.github.miam1ku.mibandoplusbridge.permission.SET_DND";
    private static final String BRIDGE = "io.github.miam1ku.mibandoplusbridge";

    private OHealthDndHook() {}

    public static void install(Context context) {
        IntentFilter filter = new IntentFilter(ACTION);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (intent == null || !ACTION.equals(intent.getAction()) || !fromBridge(ctx, getSentFromUid())) return;
                PhoneDnd.Attempt attempt = PhoneDnd.apply(ctx, intent.getBooleanExtra("on", false));
                if (attempt.applied()) {
                    setResultCode(1);
                    setResultData(attempt.via());
                }
                OHealthDeviceHook.traceLine(ctx, "DND_HOST_WRITE on=" + intent.getBooleanExtra("on", false)
                        + " applied=" + attempt.applied() + " policy=" + attempt.policy()
                        + " via=" + attempt.via()
                        + " process=" + android.app.Application.getProcessName());
            }
        }, filter, PERMISSION, null, Context.RECEIVER_EXPORTED);
        OHealthDeviceHook.traceLine(context, "DND_HOST_READY process=" + android.app.Application.getProcessName());
    }

    private static boolean fromBridge(Context context, int uid) {
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String pkg : packages) if (BRIDGE.equals(pkg)) return true;
        return false;
    }
}
