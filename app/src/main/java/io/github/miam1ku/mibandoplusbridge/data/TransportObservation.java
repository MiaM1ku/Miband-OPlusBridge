// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import org.json.JSONObject;

/** Last verified SPP profile. Survives LSPosed preference redirect and diagnostic recapture. */
public final class TransportObservation {
    public static final int APP_CAPABILITY = 25_171_686;
    private static final String NAME = "transport-observation";
    private static final String KEY = "snapshot";
    private static final String MODEL = "miwear.watch.q66cn";
    private static final String VERSION = "3.2.15";
    private static final String SERVICE = "00001101-0000-1000-8000-00805f9b34fb";

    private TransportObservation() {}

    public static JSONObject read(Context context) {
        String snapshot = LocalPrefs.open(context, NAME).getString(KEY, "");
        if (snapshot.isBlank()) return new JSONObject();
        try {
            return new JSONObject(snapshot);
        } catch (org.json.JSONException invalid) {
            return new JSONObject();
        }
    }

    public static boolean write(Context context, JSONObject observation) {
        if (observation == null) return false;
        return LocalPrefs.open(context, NAME).edit().putString(KEY, observation.toString()).commit();
    }

    public static boolean supportsLive(JSONObject observation, String bindingModel) {
        if (observation == null || bindingModel == null) return false;
        return observation.has("model") && observation.has("transport") && observation.has("versionName")
                && observation.has("rfcommUuid") && observation.has("rfcommSecure")
                && observation.has("officialAuthConnected") && observation.has("appCapability")
                && observation.has("authOobPresent") && observation.has("authAppDeviceIdPresent")
                && MODEL.equals(bindingModel) && MODEL.equals(observation.optString("model"))
                && "SPP".equals(observation.optString("transport"))
                && VERSION.equals(observation.optString("versionName"))
                && SERVICE.equals(observation.optString("rfcommUuid"))
                && observation.optBoolean("rfcommSecure")
                && observation.optBoolean("officialAuthConnected")
                && observation.optInt("appCapability") == APP_CAPABILITY;
    }
}
