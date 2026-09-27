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

    /** Copy live-profile evidence onto the encrypted binding so import can complete without firmware. */
    public static void applyToBinding(JSONObject binding, JSONObject observation) throws org.json.JSONException {
        if (binding == null || observation == null) return;
        if (binding.optString("firmware", "").isBlank()) {
            String firmware = observation.optString("firmware", "");
            if (!firmware.isBlank()) binding.put("firmware", firmware);
        }
        String transport = observation.optString("transport", "");
        if (!transport.isBlank()) binding.put("observedTransport", transport);
        String framing = framingVersion(observation);
        if (!framing.isBlank()) binding.put("framingVersion", framing);
        if ("WearAuthV2".equals(observation.optString("authImplementation"))
                && !observation.optBoolean("authOobPresent")
                && !observation.optBoolean("authAppDeviceIdPresent")) {
            binding.put("authenticationBranch", "WearAuthV2");
        }
    }

    public static String framingVersion(JSONObject observation) {
        if (observation == null) return "";
        String queue = observation.optString("queueClass", "");
        if (queue.contains("SppTaskQueueV1")) return "1";
        if (queue.contains("SppTaskQueueV2")) return "2";
        if ("GATT".equals(observation.optString("transport"))) return "1";
        String version = observation.optString("versionName", "");
        if (version.startsWith("1.")) return "1";
        if (version.startsWith("2.") || version.startsWith("3.")) return "2";
        return "";
    }

    public static boolean useV1Framing(JSONObject observation, int versionMajor) {
        String framed = framingVersion(observation);
        if ("1".equals(framed)) return true;
        if ("2".equals(framed)) return false;
        return versionMajor < 2;
    }

    public static String missingForLive(JSONObject binding, JSONObject observation) {
        StringBuilder missing = new StringBuilder();
        if (binding == null) return "address,model,userId,region,token";
        for (String key : new String[]{"address", "model", "userId", "region", "token"}) {
            if (binding.optString(key, "").isBlank()) {
                if (missing.length() > 0) missing.append(',');
                missing.append(key);
            }
        }
        String model = binding.optString("model", "");
        if (supportsLive(observation, model)) return missing.toString();
        if (observation == null || observation.optString("transport", "").isBlank()) append(missing, "observedTransport");
        if (framingVersion(observation).isBlank()) append(missing, "framingVersion");
        if (!"WearAuthV2".equals(observation == null ? "" : observation.optString("authImplementation"))
                || (observation != null && observation.optBoolean("authOobPresent"))) {
            append(missing, "authenticationBranch");
        }
        return missing.toString();
    }

    private static void append(StringBuilder missing, String field) {
        if (missing.length() > 0) missing.append(',');
        missing.append(field);
    }
}
