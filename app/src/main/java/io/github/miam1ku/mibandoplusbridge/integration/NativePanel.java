// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 控制中心点到手环时，把详情意图交给健康里响应该动作的面板。
 * 目标类按动作解析，不写死某一版的类名。
 */
public final class NativePanel {
    public static final String PANEL = "com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL";
    public static final String PAGE = "com.oplus.mydevices.ACTION_DEVICE_DETAILED_PAGE";
    public static final String HEALTH = "com.heytap.health";
    /** 健康原生页只认这只 OPPO 手环型号。 */
    public static final String BAND_MODEL = "OB19B1";
    private static final Pattern BAND = Pattern.compile("miband11_[0-9a-f]{64}");

    private NativePanel() {}

    public static boolean panelAction(String action) {
        return PANEL.equals(action) || PAGE.equals(action);
    }

    public static boolean bandDevice(String deviceId) {
        return deviceId != null && BAND.matcher(deviceId).matches();
    }

    public static ComponentName resolve(Context context) {
        Intent probe = new Intent(PANEL);
        probe.setPackage(HEALTH);
        probe.addCategory(Intent.CATEGORY_DEFAULT);
        ResolveInfo info = context.getPackageManager().resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null || info.activityInfo == null || info.activityInfo.name == null) return null;
        return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
    }

    /** 就地改到健康面板。已经指向该面板、或不是这只手环时不动。 */
    public static boolean redirect(Context context, Intent intent) {
        if (intent == null || !panelAction(intent.getAction())) return false;
        String deviceId = intent.getStringExtra("device_id");
        if (deviceId == null) deviceId = intent.getStringExtra("key_device_id");
        if (!bandDevice(deviceId)) return false;
        ComponentName panel = resolve(context);
        if (panel == null || panel.equals(intent.getComponent())) return false;
        String mac = intent.getStringExtra("device_mac_info");
        String name = intent.getStringExtra("device_title");
        if (mac == null || mac.isBlank() || name == null || name.isBlank()) {
            try (Cursor cursor = context.getContentResolver().query(DeviceCardProvider.URI,
                    new String[]{"device_mac", "device_data"}, "device_id=?", new String[]{deviceId}, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    if (mac == null || mac.isBlank()) {
                        mac = cursor.getString(cursor.getColumnIndexOrThrow("device_mac"));
                    }
                    if (name == null || name.isBlank()) {
                        name = new JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("device_data")))
                                .optString("mDeviceName", "");
                    }
                }
            } catch (RuntimeException | JSONException ignored) {
                if (mac == null) mac = "";
            }
        }
        if (mac == null || mac.isBlank()) return false;
        intent.setComponent(panel);
        intent.setPackage(panel.getPackageName());
        intent.putExtra("device_id", deviceId);
        intent.putExtra("device_title", name == null ? "" : name);
        intent.putExtra("model_id", BAND_MODEL);
        intent.putExtra("device_mac_info", mac);
        return true;
    }
}
