// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.integration.CredentialProvider;
import java.util.Locale;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Records SPP metadata during the setup import/profile window. Never key or payload bytes. */
public final class TransportProbeHook {
    private TransportProbeHook() {}

    public static void install(Context context, ClassLoader loader) throws Exception {
        var writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), runnable -> {
                    Thread thread = new Thread(runnable, "OplusBandTransportProbe");
                    thread.setDaemon(true);
                    return thread;
                });
        Class<?> apiClass = Class.forName("com.xiaomi.wearable.wear.api.WearApiCall", false, loader);
        Class<?> infoClass = Class.forName("com.xiaomi.wearable.core.DeviceInfo", false, loader);
        XC_MethodHook observeApi = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable()) observe(context, writer, param.thisObject, null);
            }
        };
        XposedBridge.hookMethod(apiClass.getDeclaredMethod("onConnected"), observeApi);
        XposedBridge.hookMethod(apiClass.getDeclaredMethod("onConnectSuccessInternal"), observeApi);
        XposedBridge.hookMethod(apiClass.getDeclaredMethod("onUpdate", infoClass), observeApi);
        Class<?> ble = Class.forName("com.xiaomi.wearable.connection.BleConnection", false, loader);
        XposedBridge.hookMethod(ble.getDeclaredMethod("onConnectSuccess"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable()) observeConnection(context, writer, param.thisObject, "getBleApiCall");
            }
        });
        Class<?> spp = Class.forName("com.xiaomi.wearable.connection.SppConnection", false, loader);
        XposedBridge.hookMethod(spp.getDeclaredMethod("onConnectStatusChanged", boolean.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable() && Boolean.TRUE.equals(param.args[0])) {
                    observeConnection(context, writer, param.thisObject, "getSppApiCall");
                }
            }
        });
        Class<?> auth = Class.forName("com.xiaomi.wearable.wear.api.WearAuthV2", false, loader);
        XposedBridge.hookMethod(auth.getDeclaredConstructor(String.class, byte[].class, String.class,
                String.class, boolean.class, apiClass, int.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                Bundle metadata = new Bundle();
                metadata.putString("authImplementation", "WearAuthV2");
                metadata.putBoolean("authFlagObserved", (Boolean) param.args[4]);
                metadata.putInt("authCtorVersion", (Integer) param.args[6]);
                // Record optional-credential presence, never their values or the binding key.
                metadata.putBoolean("authAppDeviceIdPresent", param.args[2] != null && !((String) param.args[2]).isEmpty());
                metadata.putBoolean("authOobPresent", param.args[3] != null && !((String) param.args[3]).isEmpty());
                observe(context, writer, param.args[5], metadata);
            }
        });
        installSocketProbe(context, writer);
    }

    private static void installSocketProbe(Context context, ThreadPoolExecutor writer) throws Exception {
        Map<Object, Bundle> pendingSockets = Collections.synchronizedMap(new WeakHashMap<>());
        for (String method : new String[]{"createRfcommSocketToServiceRecord", "createInsecureRfcommSocketToServiceRecord"}) {
            XposedBridge.hookMethod(BluetoothDevice.class.getDeclaredMethod(method, UUID.class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.getResult() == null) return;
                    try {
                        Bundle request = context.getContentResolver().call(CredentialProvider.URI, "getDiagnosticRequest", null, null);
                        if (request == null || !"DIAGNOSTIC_WINDOW_OPEN".equals(request.getString("status"))) return;
                        String address = ((BluetoothDevice) param.thisObject).getAddress();
                        if (!request.getString("address").equals(address.toUpperCase(Locale.ROOT))) return;
                        Bundle metadata = new Bundle();
                        metadata.putString("address", request.getString("address"));
                        metadata.putString("nonce", request.getString("nonce"));
                        metadata.putString("rfcommUuid", ((UUID) param.args[0]).toString());
                        metadata.putBoolean("rfcommSecure", method.equals("createRfcommSocketToServiceRecord"));
                        pendingSockets.put(param.getResult(), metadata);
                    } catch (Throwable failure) {
                        Log.i("OplusBandBridge", "RFCOMM_OBSERVATION_FAILED");
                    }
                }
            });
        }
        XposedBridge.hookMethod(BluetoothSocket.class.getDeclaredMethod("connect"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Bundle metadata = pendingSockets.remove(param.thisObject);
                if (metadata == null) return;
                metadata.putBoolean("rfcommSocketConnected", !param.hasThrowable());
                try {
                    writer.execute(() -> {
                        try {
                            context.getContentResolver().call(CredentialProvider.URI, "observeTransport", null, metadata);
                        } catch (RuntimeException rejected) {
                            Log.i("OplusBandBridge", "RFCOMM_OBSERVATION_REJECTED");
                        } finally {
                            metadata.clear();
                        }
                    });
                } catch (RuntimeException rejected) {
                    Log.i("OplusBandBridge", "RFCOMM_OBSERVATION_QUEUE_FULL");
                }
            }
        });
    }

    private static void observeConnection(Context context, ThreadPoolExecutor writer, Object connection, String getter) {
        try {
            observe(context, writer, XposedHelpers.callMethod(connection, getter), null);
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "TRANSPORT_OBSERVATION_FAILED");
        }
    }

    private static void observe(Context context, ThreadPoolExecutor writer, Object api, Bundle auth) {
        try {
            Bundle request = context.getContentResolver().call(CredentialProvider.URI, "getDiagnosticRequest", null, null);
            if (request == null || !"DIAGNOSTIC_WINDOW_OPEN".equals(request.getString("status"))) return;
            Object info = XposedHelpers.callMethod(api, "getDeviceInfo");
            String address = (String) XposedHelpers.callMethod(info, "getAddress");
            if (address == null || !address.toUpperCase(Locale.ROOT).equals(request.getString("address"))) return;
            Bundle metadata = auth == null ? new Bundle() : new Bundle(auth);
            metadata.putString("address", request.getString("address"));
            metadata.putString("nonce", request.getString("nonce"));
            Object connection = XposedHelpers.callMethod(api, "getConnection");
            if (connection != null) {
                String name = connection.getClass().getName();
                metadata.putString("connectionClass", name);
                if (name.equals("com.xiaomi.wearable.connection.BleConnection")) metadata.putString("transport", "GATT");
                if (name.equals("com.xiaomi.wearable.connection.SppConnection")) metadata.putString("transport", "SPP");
                metadata.putBoolean("officialAuthConnected", (Boolean) XposedHelpers.callMethod(api, "isAuthConnected"));
            }
            Object queue = XposedHelpers.getObjectField(api, "mQueue");
            if (queue != null) metadata.putString("queueClass", queue.getClass().getName());
            metadata.putInt("apiVersion", (Integer) XposedHelpers.callMethod(api, "getVersion"));
            metadata.putInt("appCapability", (Integer) XposedHelpers.callMethod(api, "getAppCapability"));
            metadata.putInt("deviceType", (Integer) XposedHelpers.callMethod(info, "getType"));
            metadata.putInt("accessType", (Integer) XposedHelpers.callMethod(info, "getAccessType"));
            metadata.putString("model", (String) XposedHelpers.callMethod(info, "getModel"));
            metadata.putString("firmware", (String) XposedHelpers.callMethod(info, "getFirmwareVersion"));
            metadata.putString("productId", (String) XposedHelpers.callMethod(info, "getProductId"));
            if (api.getClass().getName().equals("ynq")) {
                Object transport = XposedHelpers.callMethod(api, "d");
                metadata.putString("versionName", (String) XposedHelpers.callMethod(transport, "getVersionName"));
            }
            writer.execute(() -> {
                try {
                    context.getContentResolver().call(CredentialProvider.URI, "observeTransport", null, metadata);
                } catch (RuntimeException rejected) {
                    Log.i("OplusBandBridge", "TRANSPORT_OBSERVATION_REJECTED");
                } finally {
                    metadata.clear();
                }
            });
        } catch (Throwable failure) {
            Log.i("OplusBandBridge", "TRANSPORT_OBSERVATION_FAILED");
        }
    }
}
