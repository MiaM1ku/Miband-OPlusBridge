// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Reads GitHub Releases. Does not download or install APKs. */
public final class UpdateChecker {
    public static final String OWNER_REPO = "MiaM1ku/Miband-OPlusBridge";
    public static final String PAGE = "https://github.com/" + OWNER_REPO + "/releases/latest";
    private static final String API = "https://api.github.com/repos/" + OWNER_REPO + "/releases/latest";

    public record Result(boolean newer, String tag, String versionName, int versionCode,
                         String htmlUrl, String notes) {}

    private UpdateChecker() {}

    public static Result check(int installedCode, String installedName) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(8_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", OWNER_REPO);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) throw new IllegalStateException("UPDATE_HTTP_" + status);
        String body = read(stream);
        if (status == 404) return new Result(false, "", installedName, installedCode, PAGE, "还没有正式版本。");
        if (status >= 400) throw new IllegalStateException("UPDATE_HTTP_" + status);
        JSONObject json = new JSONObject(body);
        String tag = json.optString("tag_name", "");
        Parsed parsed = parseTag(tag, json.optString("name", tag));
        String html = json.optString("html_url", PAGE);
        String notes = json.optString("body", "").trim();
        if (notes.length() > 400) notes = notes.substring(0, 400);
        boolean newer = parsed.code > installedCode
                || (parsed.code == installedCode && !parsed.name.equals(installedName)
                && !parsed.name.isBlank());
        return new Result(newer, tag, parsed.name, parsed.code, html, notes);
    }

    static Parsed parseTag(String tag, String fallbackName) {
        String value = tag == null ? "" : tag.trim();
        if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        int dash = value.indexOf('-');
        if (dash > 0) {
            try {
                int code = Integer.parseInt(value.substring(0, dash));
                String name = value.substring(dash + 1).trim();
                if (name.isBlank()) name = fallbackName;
                return new Parsed(code, name);
            } catch (NumberFormatException ignored) { }
        }
        String name = value.isBlank() ? fallbackName : value;
        return new Parsed(0, name);
    }

    record Parsed(int code, String name) {}

    private static String read(InputStream stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = stream.read(buffer)) >= 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8);
    }
}
