// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Device-center projection and authenticated bridge commands. */
public final class DeviceCardProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.miam1ku.mibandoplusbridge.devices";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/device_info_table_new");
    private static final String HOST = "com.heytap.mydevices";
    private static final String HEALTH = "com.heytap.health";
    private static final String[] COLUMNS = {"device_id", "device_mac", "device_data", "authority"};

    @Override public boolean onCreate() { return true; }

    /** 设备空间进程里才挂卡片钩子。不核对签名，系统升级轮换证书后仍要能注入。 */
    public static boolean matchesInstalledHost(Context context) {
        return HostIdentity.installed(context, HOST);
    }

    private void requireReader() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid() || uid == 0 || uid == 2000) return; // self, root, developer shell
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length != 1 || !allowed(packages[0])) {
            throw new SecurityException("DEVICE_CARD_CALLER_NOT_AUTHORIZED");
        }
    }

    private void requireSyncCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length != 1 || !HEALTH.equals(packages[0])) {
            throw new SecurityException("DEVICE_SYNC_CALLER_NOT_AUTHORIZED");
        }
    }

    private io.github.miam1ku.mibandoplusbridge.data.LocalPrefs state() {
        UserManager users = getContext().getSystemService(UserManager.class);
        if (users == null || !users.isUserUnlocked()) throw new IllegalStateException("USER_LOCKED");
        return io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(getContext(), "band-state");
    }

    private boolean allowed(String caller) {
        if (HOST.equals(caller) || HEALTH.equals(caller)) return true;
        return false;
    }

    private static void requireUri(Uri uri) {
        if (!URI.equals(uri)) throw new IllegalArgumentException("UNSUPPORTED_DEVICE_CARD_URI");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        requireReader();
        requireUri(uri);
        if (sortOrder != null) throw new IllegalArgumentException("UNSUPPORTED_SORT_ORDER");
        String key = null;
        if (selection == null) {
            if (selectionArgs != null && selectionArgs.length != 0) throw new IllegalArgumentException("UNEXPECTED_ARGUMENTS");
        } else {
            if (selectionArgs == null || selectionArgs.length != 1 || selectionArgs[0] == null)
                throw new IllegalArgumentException("INVALID_SELECTION_ARGUMENTS");
            key = switch (selection) {
                case "device_id=?", "device_id = ?" -> "device_id";
                case "device_mac=?", "device_mac = ?" -> "device_mac";
                case "authority=?", "authority = ?" -> "authority";
                default -> throw new IllegalArgumentException("UNSUPPORTED_SELECTION");
            };
        }
        String[] requested = projection == null ? COLUMNS : projection.clone();
        for (String column : requested) {
            if (!isColumn(column)) throw new IllegalArgumentException("UNSUPPORTED_COLUMN");
        }
        MatrixCursor result = new MatrixCursor(requested, 1);
        result.setNotificationUri(getContext().getContentResolver(), URI);
        var state = state();
        if (!state.getBoolean("registered", false)) return result;
        String deviceId = state.getString("deviceId", "");
        if (deviceId == null || deviceId.isBlank()) return result;
        String mac = state.getString("mac", "");
        if (key != null) {
            String actual = switch (key) {
                case "device_id" -> deviceId;
                case "device_mac" -> mac;
                default -> AUTHORITY;
            };
            if (!actual.equals(selectionArgs[0])) return result;
        }
        try {
            JSONObject data = deviceData(state, deviceId, mac);
            MatrixCursor.RowBuilder row = result.newRow();
            for (String column : requested) {
                row.add(switch (column) {
                    case "device_id" -> deviceId;
                    case "device_mac" -> mac;
                    case "device_data" -> data.toString();
                    default -> AUTHORITY;
                });
            }
            return result;
        } catch (JSONException failure) {
            result.close();
            throw new IllegalStateException("DEVICE_CARD_SERIALIZATION_FAILED", failure);
        }
    }

    private static boolean isColumn(String column) {
        for (String allowed : COLUMNS) if (allowed.equals(column)) return true;
        return false;
    }

    private static JSONObject deviceData(io.github.miam1ku.mibandoplusbridge.data.LocalPrefs state, String deviceId, String mac)
            throws JSONException {
        boolean connected = state.getBoolean("registered", false) && state.getBoolean("connected", false);
        String connectState = connected ? "CONNECTED" : "DISCONNECTED";
        int battery = state.getInt("battery", -1);
        JSONArray batteries = new JSONArray();
        JSONArray levels = new JSONArray();
        if (battery >= 0 && battery <= 100) {
            batteries.put(new JSONObject().put("batteryType", "SINGLE")
                    .put("value", battery).put("charge", state.getBoolean("charging", false)));
            levels.put(battery);
        }
        JSONObject connection = new JSONObject().put("connectState", connectState)
                .put("coordinationState", "DEFAULT")
                .put("lastConnectTime", connected ? state.getLong("lastUpdateMs", 0) : 0)
                .put("lastDisconnectTime", connected ? 0 : state.getLong("lastUpdateMs", 0));
        JSONObject data = new JSONObject()
                .put("mDeviceId", deviceId)
                .put("mDeviceName", state.getString("name", ""))
                .put("mMacAddress", mac)
                .put("modelId", state.getString("modelId", ""))
                .put("mDeviceType", "WRISTBAND")
                .put("mBatteryInfoList", batteries)
                .put("mBatteryList", levels)
                .put("connection", connection)
                .put("mConnectState", connectState)
                .put("mAuthority", AUTHORITY)
                .put("isActive", connected)
                .put("cardStyle", 1)
                .put("timestamp", state.getLong("lastUpdateMs", 0))
                .put("versionCode", 1L)
                .put("feature", 0)
                .put("linkageVersion", 0)
                .put("autoSwitch", false)
                .put("revision", state.getLong("revision", 0));
        return data;
    }

    @Override public String getType(Uri uri) {
        requireReader();
        requireUri(uri);
        return "vnd.android.cursor.dir/vnd." + AUTHORITY + ".device_info";
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        requireReader();
        throw new SecurityException("DEVICE_CARD_READ_ONLY");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        requireReader();
        throw new SecurityException("DEVICE_CARD_READ_ONLY");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        requireReader();
        throw new SecurityException("DEVICE_CARD_READ_ONLY");
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("requestSync".equals(method)) {
            requireSyncCaller();
            long identity = Binder.clearCallingIdentity();
            try {
                Bundle result = new Bundle();
                // Service admission checks registration/ownership without root or Bluetooth work here.
                result.putString("status", BandLiveService.requestSync(getContext()));
                return result;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        requireReader();
        if (!"bandDisplay".equals(method)) throw new SecurityException("DEVICE_CARD_READ_ONLY");
        var state = state();
        Bundle display = new Bundle();
        boolean registered = state.getBoolean("registered", false);
        display.putBoolean("registered", registered);
        display.putString("name", state.getString("name", ""));
        display.putString("deviceId", state.getString("deviceId", ""));
        display.putString("mac", state.getString("mac", ""));
        display.putString("modelId", state.getString("modelId", ""));
        display.putString("firmware", state.getString("verifiedFirmware", ""));
        display.putString("hardware", state.getString("verifiedHardware", ""));
        display.putBoolean("connected", registered && state.getBoolean("connected", false));
        display.putInt("battery", state.getInt("battery", -1));
        display.putBoolean("charging", state.getBoolean("charging", false));
        display.putInt("historyFiles", new io.github.miam1ku.mibandoplusbridge.data.RawFitnessFileStore(getContext()).storedFiles());
        display.putLong("stepsToday", state.getLong("stepsToday", -1));
        display.putInt("heartRate", state.getInt("heartRate", -1));
        display.putLong("registeredAtMs", state.getLong("registeredAtMs", 0));
        display.putLong("lastSyncAtMs", state.getLong("lastSyncAtMs", 0));
        display.putLong("heartRateAtMs", state.getLong("heartRateAtMs", 0));
        display.putLong("stepsAtMs", state.getLong("stepsAtMs", 0));
        display.putLong("lastUpdateMs", state.getLong("lastUpdateMs", 0));
        display.putLong("revision", state.getLong("revision", 0));
        return display;
    }
}