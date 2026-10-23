// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Picks the server picture Mi Fitness uses for the device page.
 * {@code big_preview} already contains the default watch face. The icon fields are the empty body.
 */
public final class MiProductImage {
    static final String PICK = "preview";
    private static final int MAX_DEPTH = 12;
    private static final int MAX_URL = 1024;
    private static final String[] SOURCES = {
            "sku_big_preview", "big_preview", "sku_big_icon", "big_icon_3", "big_icon"
    };

    public record Choice(String url, String source, int productType) {}

    private MiProductImage() {}

    /** Segment before {@code /} in the band serial. Anything else stays empty. */
    public static String sku(String serialNumber) {
        if (serialNumber == null) return "";
        String head = serialNumber;
        int slash = serialNumber.indexOf('/');
        if (slash >= 0) head = serialNumber.substring(0, slash);
        head = head.trim();
        if (!head.matches("[A-Za-z0-9._-]{1,32}")) return "";
        return head;
    }

    /** First product whose {@code model} matches. Preview pictures come before the empty body. */
    public static Choice choose(String productJson, String model, String sku) {
        if (productJson == null || productJson.isBlank() || model == null || model.isBlank()) return null;
        String wanted = model.trim();
        String color = sku == null ? "" : sku.trim();
        JSONObject root;
        try {
            root = new JSONObject(productJson);
        } catch (org.json.JSONException invalid) {
            return null;
        }
        ArrayList<JSONObject> matches = new ArrayList<>();
        walk(root, wanted, 0, matches);
        for (JSONObject product : matches) {
            Choice choice = fromProduct(product, color);
            if (choice != null) return choice;
        }
        return null;
    }

    private static Choice fromProduct(JSONObject product, String sku) {
        for (String source : SOURCES) {
            String url = picture(product, source, sku);
            if (https(url)) return new Choice(url, source, product.optInt("pd_type", -1));
        }
        return null;
    }

    private static String picture(JSONObject product, String source, String sku) {
        if (source.startsWith("sku_")) return mapped(product.opt(source), sku);
        String direct = product.optString(source, "");
        return direct == null ? "" : direct.trim();
    }

    private static String mapped(Object raw, String sku) {
        if (sku.isEmpty() || raw == null) return "";
        JSONObject map = null;
        if (raw instanceof JSONObject object) map = object;
        else if (raw instanceof String text && text.trim().startsWith("{")) {
            try {
                map = new JSONObject(text);
            } catch (org.json.JSONException ignored) {
                return "";
            }
        }
        if (map == null || !map.has(sku)) return "";
        return map.optString(sku, "").trim();
    }

    private static boolean https(String url) {
        return url != null && url.startsWith("https://") && url.length() <= MAX_URL && !url.contains(" ");
    }

    private static void walk(Object node, String model, int depth, List<JSONObject> matches) {
        if (node == null || depth > MAX_DEPTH) return;
        if (node instanceof JSONObject object) {
            if (object.has("model") && model.equals(object.optString("model", ""))) matches.add(object);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) walk(object.opt(keys.next()), model, depth + 1, matches);
        } else if (node instanceof JSONArray array) {
            for (int index = 0; index < array.length(); index++) {
                walk(array.opt(index), model, depth + 1, matches);
            }
        }
    }
}
