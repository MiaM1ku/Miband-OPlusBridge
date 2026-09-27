// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import org.json.JSONObject;

/** Last verified SPP profile. Survives LSPosed preference redirect and diagnostic recapture. */
public final class TransportObservation {
    public static final int APP_CAPABILITY = 25_171_686;
    private static final String NAME = "transport-observation";
    private static final String KEY = "snapshot";
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

    /**
     * Official reconnect: WearAuthV2, no OOB. SPP uses secure RFCOMM; GATT uses the captured BLE path.
     */
    public static boolean supportsLive(JSONObject observation, String bindingModel) {
        if (observation == null || bindingModel == null || bindingModel.isBlank()) return false;
        String model = observation.optString("model", "");
        if (!observation.has("model") || !observation.has("transport")
                || !observation.has("officialAuthConnected") || !observation.has("appCapability")
                || !observation.has("authOobPresent") || !observation.has("authAppDeviceIdPresent")
                || !bindingModel.equals(model)
                || !observation.optBoolean("officialAuthConnected")
                || observation.optInt("appCapability") <= 0
                || observation.optBoolean("authOobPresent")
                || observation.optBoolean("authAppDeviceIdPresent")) {
            return false;
        }
        String transport = observation.optString("transport");
        if ("GATT".equals(transport)) return true;
        String versionName = observation.optString("versionName", "");
        return "SPP".equals(transport)
                && observation.has("versionName") && observation.has("rfcommUuid") && observation.has("rfcommSecure")
                && !versionName.isBlank()
                && SERVICE.equals(observation.optString("rfcommUuid"))
                && observation.optBoolean("rfcommSecure");
    }
}
