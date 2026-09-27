// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/**
 * App state that must survive LSPosed's module-preference redirect.
 * {@link Context#getSharedPreferences} in this package is pointed at
 * {@code /data/misc/apexdata/.../prefs/}, which keeps a stale OFFICIAL copy.
 */
public final class LocalPrefs {
    private final File file;
    private JSONObject json;

    private LocalPrefs(File file, JSONObject json) {
        this.file = file;
        this.json = json;
    }

    public static LocalPrefs open(Context context, String name) {
        Context app = context.getApplicationContext();
        if (app != null) context = app;
        File file = new File(context.getNoBackupFilesDir(), name + ".json");
        JSONObject json = readJson(file);
        if (json.length() == 0) {
            json = importShared(context, name);
            JSONObject disk = importXml(new File(context.getDataDir(), "shared_prefs/" + name + ".xml"));
            if (prefer(disk, json, name)) json = disk;
            if (json.length() > 0) writeJson(file, json);
        }
        return new LocalPrefs(file, json);
    }

    public boolean getBoolean(String key, boolean fallback) { return json.optBoolean(key, fallback); }
    public String getString(String key, String fallback) {
        return json.has(key) ? json.optString(key, fallback) : fallback;
    }
    public int getInt(String key, int fallback) { return json.optInt(key, fallback); }
    public long getLong(String key, long fallback) { return json.optLong(key, fallback); }

    public Editor edit() { return new Editor(); }

    public final class Editor {
        private final JSONObject next;
        Editor() {
            try { next = new JSONObject(json.toString()); }
            catch (Exception failed) { throw new IllegalStateException("STATE_COPY_FAILED", failed); }
        }
        public Editor putBoolean(String key, boolean value) { return put(key, value); }
        public Editor putString(String key, String value) { return put(key, value); }
        public Editor putInt(String key, int value) { return put(key, value); }
        public Editor putLong(String key, long value) { return put(key, value); }
        public Editor remove(String key) { next.remove(key); return this; }
        private Editor put(String key, Object value) {
            try { next.put(key, value); return this; }
            catch (Exception failed) { throw new IllegalStateException("STATE_WRITE_FAILED", failed); }
        }
        public boolean commit() {
            if (!writeJson(file, next)) return false;
            json = next;
            return true;
        }
    }

    private static boolean prefer(JSONObject disk, JSONObject current, String name) {
        if (disk.length() == 0) return false;
        if ("ownership".equals(name) && "NATIVE".equals(disk.optString("mode"))
                && !"NATIVE".equals(current.optString("mode"))) return true;
        if ("band-state".equals(name) && disk.optBoolean("registered")
                && disk.optLong("revision", 0) >= current.optLong("revision", 0)) return true;
        return current.length() == 0;
    }

    private static JSONObject readJson(File file) {
        if (!file.isFile()) return new JSONObject();
        try {
            byte[] bytes = new byte[(int) Math.min(file.length(), 32_768)];
            try (FileInputStream in = new FileInputStream(file)) {
                int n = in.read(bytes);
                if (n <= 0) return new JSONObject();
                return new JSONObject(new String(bytes, 0, n, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static boolean writeJson(File file, JSONObject json) {
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception failed) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(file) || (file.delete() && tmp.renameTo(file));
    }

    private static JSONObject importShared(Context context, String name) {
        JSONObject json = new JSONObject();
        try {
            SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            for (var entry : prefs.getAll().entrySet()) {
                if (entry.getValue() != null) json.put(entry.getKey(), entry.getValue());
            }
        } catch (Exception ignored) { }
        return json;
    }

    private static JSONObject importXml(File file) {
        JSONObject json = new JSONObject();
        if (!file.isFile()) return json;
        try (FileInputStream in = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());
            String type = null;
            String key = null;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    type = parser.getName();
                    key = parser.getAttributeValue(null, "name");
                    if ("boolean".equals(type) && key != null) {
                        json.put(key, Boolean.parseBoolean(parser.getAttributeValue(null, "value")));
                    } else if ("int".equals(type) && key != null) {
                        json.put(key, Integer.parseInt(parser.getAttributeValue(null, "value")));
                    } else if ("long".equals(type) && key != null) {
                        json.put(key, Long.parseLong(parser.getAttributeValue(null, "value")));
                    }
                } else if (event == XmlPullParser.TEXT && "string".equals(type) && key != null) {
                    json.put(key, parser.getText());
                } else if (event == XmlPullParser.END_TAG) {
                    type = null;
                    key = null;
                }
            }
        } catch (Exception ignored) { }
        return json;
    }
}
