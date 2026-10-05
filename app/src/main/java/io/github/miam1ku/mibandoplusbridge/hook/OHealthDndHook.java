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

    private OHealthDndHook() {}

    public static void install(Context context) {
        IntentFilter filter = new IntentFilter(ACTION);
        filter.setPriority(999);
        context.getApplicationContext().registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (intent == null || !ACTION.equals(intent.getAction())) return;
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
    }
}
