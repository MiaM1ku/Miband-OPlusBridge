// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.util.Locale;
import org.json.JSONObject;

/** Registration gate and a secret-free description of a saved binding. */
public final class BindingShape {
    private BindingShape() {}

    /**
     * Null when the binding can be registered.
     * V2 auth sends region and the 16-byte key. It does not send userId.
     */
    public static String reject(JSONObject binding) {
        if (binding == null) return "UNPROVISIONED";
        if (!mac(binding.optString("address", ""))) return "BINDING_ADDRESS";
        if (binding.optString("region", "").isBlank()) return "BINDING_REGION";
        if (!AuthToken.hex32(binding.optString("token", ""))) return "TOKEN_ENCODING_UNSUPPORTED";
        return null;
    }

    /** Field classes only. Never the address, user id, region text, or token. */
    public static String describe(JSONObject binding) {
        if (binding == null) return "binding=absent";
        return "address=" + (mac(binding.optString("address", "")) ? "ok" : "bad")
                + " userId=" + (binding.optString("userId", "").isBlank() ? "blank" : "set")
                + " region=" + (binding.optString("region", "").isBlank() ? "blank" : "set")
                + " token=" + token(binding.optString("token", ""))
                + " model=" + model(binding.optString("model", ""));
    }

    public static boolean mac(String address) {
        return address != null && address.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}");
    }

    private static String token(String value) {
        if (value == null || value.isBlank()) return "blank";
        if (AuthToken.hex32(value)) return "hex32";
        String kind = AuthToken.hex32(AuthToken.normalize(value)) ? "normalizable" : "other";
        int length = value.length();
        return kind + " len=" + (length > 128 ? "long" : length);
    }

    private static String model(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) return "other";
        return value.toLowerCase(Locale.ROOT);
    }
}
