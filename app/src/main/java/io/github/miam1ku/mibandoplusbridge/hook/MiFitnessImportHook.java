// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.data.AuthToken;
import io.github.miam1ku.mibandoplusbridge.integration.CredentialProvider;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MiFitnessImportHook {
    private MiFitnessImportHook() {}

    public static void install(Context context, ClassLoader loader) throws Exception {
        Class<?> source = Class.forName("com.xiaomi.fitness.device.manager.export.bean.WearableDeviceInfo", false, loader);
        Class<?> owner = Class.forName("com.xiaomi.fit.device.extensions.DeviceModelExtKt", false, loader);
        Method convert = owner.getDeclaredMethod("convert", source);
        if (!convert.getReturnType().getName().equals("com.xiaomi.wearable.core.DeviceInfo")) {
            throw new NoSuchMethodException("HOST_VERSION_UNSUPPORTED");
        }
        ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "OplusBandImport");
            thread.setDaemon(true);
            return thread;
        });
        AtomicBoolean pending = new AtomicBoolean();
        XposedBridge.hookMethod(convert, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || param.getResult() == null) return;
                if (!pending.compareAndSet(false, true)) return;
                boolean dispatched = false;
                try {
                    Bundle request = context.getContentResolver().call(CredentialProvider.URI,
                            "getImportRequest", null, null);
                    if (request == null || !"IMPORT_WINDOW_OPEN".equals(request.getString("status"))) return;
                    Object info = param.getResult();
                    String address = (String) XposedHelpers.callMethod(info, "getAddress");
                    if (address == null || !address.toUpperCase(Locale.ROOT).equals(request.getString("address"))) return;
                    // Only the selected device's credential getters are read.
                    Bundle binding = new Bundle();
                    binding.putString("nonce", request.getString("nonce"));
                    binding.putString("address", request.getString("address"));
                    String[][] strings = {{"did", "getDid"}, {"model", "getModel"},
                            {"productId", "getProductId"}, {"userId", "getUserId"},
                            {"region", "getRegion"}, {"appDeviceId", "getAppDeviceId"},
                            {"token", "getToken"}, {"firmware", "getFirmwareVersion"},
                            {"oob", "getOob"}, {"deviceName", "getDeviceName"}};
                    for (String[] field : strings) binding.putString(field[0],
                            (String) XposedHelpers.callMethod(info, field[1]));
                    fillTokenFromSource(binding, param.args[0], info);
                    binding.putInt("type", (Integer) XposedHelpers.callMethod(info, "getType"));
                    binding.putInt("accessType", (Integer) XposedHelpers.callMethod(info, "getAccessType"));
                    Object privateUUID = XposedHelpers.callMethod(info, "getPrivateUUID");
                    if (privateUUID != null) {
                        Bundle uuids = new Bundle();
                        for (String key : new String[]{"fitness", "mass", "otaRX", "otaTX", "protoRX", "protoTX", "service", "voice"}) {
                            String getter = "get" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
                            uuids.putString(key, (String) XposedHelpers.callMethod(privateUUID, getter));
                        }
                        binding.putBundle("privateUUID", uuids);
                    }
                    writer.execute(() -> {
                        try {
                            context.getContentResolver().call(CredentialProvider.URI, "importBinding", null, binding);
                        } catch (RuntimeException rejected) {
                            android.util.Log.i("OplusBandBridge", "OplusBandBridge: IMPORT_REJECTED");
                        } finally {
                            binding.clear();
                            pending.set(false);
                        }
                    });
                    dispatched = true;
                } catch (Throwable incompatible) {
                    // Do not log exception objects: their messages can contain host credentials.
                    android.util.Log.i("OplusBandBridge", "OplusBandBridge: IMPORT_CAPTURE_FAILED");
                } finally {
                    if (!dispatched) pending.set(false);
                }
            }
        });
    }

    /** convert() picks one of authKey/token/appToken/encryptKey; 10 Pro often leaves getToken() empty. */
    private static void fillTokenFromSource(Bundle binding, Object source, Object converted) {
        if (AuthToken.hex32(binding.getString("token"))) return;
        Object device = call(source, "getDevice");
        if (device == null) device = field(source, "device");
        Object detail = call(device, "getDetail");
        String token = AuthToken.firstHex32(
                binding.getString("token"),
                (String) call(converted, "getToken"),
                (String) call(detail, "getEncryptKey"),
                (String) call(detail, "getToken"),
                (String) call(detail, "getAuthKey"),
                (String) call(detail, "getAppToken"));
        if (AuthToken.hex32(token)) binding.putString("token", token);
    }

    private static Object call(Object target, String getter) {
        try {
            return XposedHelpers.callMethod(target, getter);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object field(Object target, String name) {
        try {
            return XposedHelpers.getObjectField(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

}
