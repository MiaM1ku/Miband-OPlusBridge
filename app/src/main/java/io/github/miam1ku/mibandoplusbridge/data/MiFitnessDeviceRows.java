// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/** Picks wearable rows out of Mi Fitness device.detail. Values stay with the caller. */
public final class MiFitnessDeviceRows {
    public static final class Detail {
        public final String model;
        public final String name;
        public final String productId;
        public final String did;
        public final String detail;

        public Detail(String model, String name, String productId, String did, String detail) {
            this.model = model;
            this.name = name;
            this.productId = productId;
            this.did = did;
            this.detail = detail;
        }
    }

    public static final class Row {
        public final String address;
        public final String model;
        public final String name;
        public final String productId;
        public final String did;
        public final String token;
        public final String phoneId;

        public Row(String address, String model, String name, String productId, String did,
                   String token, String phoneId) {
            this.address = address;
            this.model = model;
            this.name = name;
            this.productId = productId;
            this.did = did;
            this.token = token;
            this.phoneId = phoneId;
        }
    }

    private MiFitnessDeviceRows() {}

    public static List<Row> select(List<Detail> details, String preferredAddress) {
        String wanted = mac(preferredAddress);
        ArrayList<Row> rows = new ArrayList<>();
        if (details == null) return rows;
        for (Detail detail : details) {
            Row row = one(detail);
            if (row == null) continue;
            if (!wanted.isEmpty() && !wanted.equals(row.address)) continue;
            rows.add(row);
        }
        return rows;
    }

    static Row one(Detail detail) {
        if (detail == null || detail.detail == null || detail.detail.isBlank()) return null;
        JSONObject json;
        try {
            json = new JSONObject(detail.detail);
        } catch (org.json.JSONException invalid) {
            return null;
        }
        String token = AuthToken.firstKey(json.optString("encrypt_key"), json.optString("token"));
        if (!AuthToken.hex32(token)) return null;
        String address = mac(json.optString("mac"));
        if (!BindingShape.mac(address)) return null;
        String model = clip(detail.model, 128);
        if (model.isEmpty()) return null;
        return new Row(address, model, clip(detail.name, 64), clip(detail.productId, 32),
                clip(detail.did, 64), token, clip(json.optString("phone_id"), 128));
    }

    static String mac(String value) {
        if (value == null) return "";
        String compact = value.trim().replace(":", "").replace("-", "");
        if (!compact.matches("[0-9A-Fa-f]{12}")) return "";
        String upper = compact.toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(17);
        for (int index = 0; index < upper.length(); index += 2) {
            if (index > 0) out.append(':');
            out.append(upper, index, index + 2);
        }
        return out.toString();
    }

    private static String clip(String value, int limit) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() > limit ? "" : trimmed;
    }
}
