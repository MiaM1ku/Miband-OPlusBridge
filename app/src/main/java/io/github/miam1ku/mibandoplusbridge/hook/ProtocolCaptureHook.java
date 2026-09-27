// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.BuildConfig;
import io.github.miam1ku.mibandoplusbridge.data.ProtocolCaptureStore;
import io.github.miam1ku.mibandoplusbridge.integration.CredentialProvider;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Analysis-only, explicit-window API capture for the already selected wearable. */
public final class ProtocolCaptureHook {
    private ProtocolCaptureHook() {}

    public static void install(Context context, ClassLoader loader) throws Exception {
        if (!BuildConfig.DEBUG) return;
        var writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), runnable -> {
                    Thread thread = new Thread(runnable, "OplusBandProtocolCapture");
                    thread.setDaemon(true);
                    return thread;
                });
        Class<?> api = Class.forName("com.xiaomi.wearable.wear.api.WearApiCall", false, loader);
        Class<?> callback = Class.forName("com.xiaomi.wearable.core.ICallback", false, loader);
        Class<?> result = Class.forName("com.xiaomi.wearable.core.WearApiResult", false, loader);
        XposedBridge.hookMethod(api.getDeclaredMethod("call", int.class, byte[].class, boolean.class,
                callback, int.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Bundle request = authorizedRequest(context, param.thisObject);
                if (request == null) return;
                Bundle meta = new Bundle();
                meta.putInt("callArgument0", (Integer) param.args[0]);
                meta.putBoolean("responseRequested", (Boolean) param.args[2]);
                capture(context, writer, request, "tx", (byte[]) param.args[1], meta);
            }
        });
        XposedBridge.hookMethod(api.getDeclaredMethod("massCall", int.class, int.class, byte[].class,
                boolean.class, callback, int.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Bundle request = authorizedRequest(context, param.thisObject);
                if (request == null) return;
                Bundle meta = new Bundle();
                meta.putInt("callArgument0", (Integer) param.args[1]);
                meta.putInt("massChannel", (Integer) param.args[0]);
                meta.putBoolean("responseRequested", (Boolean) param.args[3]);
                capture(context, writer, request, "tx", (byte[]) param.args[2], meta);
            }
        });
        XposedBridge.hookMethod(api.getDeclaredMethod("callbackClient", callback, result), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Bundle request = authorizedRequest(context, param.thisObject);
                    if (request == null) return;
                    Bundle meta = new Bundle();
                    meta.putInt("resultCode", (Integer) XposedHelpers.callMethod(param.args[1], "a"));
                    capture(context, writer, request, "rx",
                            (byte[]) XposedHelpers.callMethod(param.args[1], "c"), meta);
                } catch (Throwable failure) {
                    Log.i("OplusBandBridge", "PROTOCOL_RESPONSE_CAPTURE_FAILED");
                }
            }
        });
        XposedBridge.hookMethod(api.getDeclaredMethod("handleData", int.class, byte[].class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Bundle request = authorizedRequest(context, param.thisObject);
                if (request == null) return;
                Bundle meta = new Bundle();
                meta.putInt("callArgument0", (Integer) param.args[0]);
                capture(context, writer, request, "event", (byte[]) param.args[1], meta);
            }
        });
    }

    private static Bundle authorizedRequest(Context context, Object api) {
        try {
            Bundle request = context.getContentResolver().call(CredentialProvider.URI, "getCaptureRequest", null, null);
            if (request == null || !"PROTOCOL_CAPTURE_OPEN".equals(request.getString("status"))) return null;
            Object info = XposedHelpers.callMethod(api, "getDeviceInfo");
            String address = (String) XposedHelpers.callMethod(info, "getAddress");
            return address != null && address.toUpperCase(Locale.ROOT).equals(request.getString("address")) ? request : null;
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "PROTOCOL_CAPTURE_GATE_FAILED");
            return null;
        }
    }

    private static void capture(Context context, ThreadPoolExecutor writer, Bundle request,
                                String direction, byte[] payload, Bundle metadata) {
        try {
            if (payload != null && payload.length > ProtocolCaptureStore.MAX_PAYLOAD) {
                captureFault(context, request, "CAPTURE_PAYLOAD_TOO_LARGE");
                return;
            }
            metadata.putString("address", request.getString("address"));
            metadata.putString("nonce", request.getString("nonce"));
            metadata.putString("direction", direction);
            metadata.putLong("capturedAtMs", System.currentTimeMillis());
            metadata.putLong("elapsedRealtimeNs", SystemClock.elapsedRealtimeNanos());
            byte[] snapshot = payload == null ? null : payload.clone();
            metadata.putByteArray("payload", snapshot);
            try {
                writer.execute(() -> {
                    try {
                        context.getContentResolver().call(CredentialProvider.URI, "recordPacket", null, metadata);
                    } catch (RuntimeException rejected) {
                        captureFault(context, request, "CAPTURE_WRITE_REJECTED");
                    } finally {
                        if (snapshot != null) Arrays.fill(snapshot, (byte) 0);
                        metadata.clear();
                    }
                });
            } catch (RuntimeException full) {
                if (snapshot != null) Arrays.fill(snapshot, (byte) 0);
                captureFault(context, request, "CAPTURE_QUEUE_FULL");
            }
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "PROTOCOL_CAPTURE_FAILED");
        }
    }

    private static void captureFault(Context context, Bundle request, String code) {
        try {
            Bundle failure = new Bundle(request);
            failure.putString("failure", code);
            context.getContentResolver().call(CredentialProvider.URI, "captureFault", null, failure);
        } catch (RuntimeException rejected) {
            Log.i("OplusBandBridge", "PROTOCOL_CAPTURE_FAULT_NOT_RECORDED");
        }
        Log.i("OplusBandBridge", code);
    }
}
