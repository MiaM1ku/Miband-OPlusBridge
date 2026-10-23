// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads the Mi Fitness body picture and keeps the last good file. */
public final class DeviceImageStore {
    private static final long FRESH_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long RETRY_MS = 6L * 60 * 60 * 1000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_EDGE = 4096;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "band-device-image");
        thread.setDaemon(true);
        return thread;
    });

    private DeviceImageStore() {}

    public static void requestRefresh(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        Context target = app;
        EXECUTOR.execute(() -> {
            try {
                if (refreshIfNeeded(target)) new io.github.miam1ku.mibandoplusbridge.data.BandStateRepository(target).noteDeviceImage();
            } catch (RuntimeException failure) {
                SessionLog.line(target, "bridge DEVICE_IMAGE_UNAVAILABLE " + failure.getClass().getSimpleName());
            }
        });
    }

    /** Content URI of the current body picture, or empty when this registration has none. */
    public static String uri(Context context) {
        try {
            LocalPrefs state = LocalPrefs.open(context, "band-state");
            if (!state.getBoolean("registered", false)) return "";
            LocalPrefs image = LocalPrefs.open(context, "device-image");
            if (!state.getString("modelId", "").equals(image.getString("model", ""))) return "";
            File file = new File(image.getString("file", ""));
            if (!file.isFile() || file.length() == 0) return "";
            long version = image.getLong("version", 0);
            return "content://" + DeviceCardProvider.AUTHORITY + "/device_image/miband-body-" + version + ".png";
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    public static File file(Context context) {
        if (uri(context).isEmpty()) return null;
        try {
            File stored = new File(LocalPrefs.open(context, "device-image").getString("file", ""));
            return stored.isFile() ? stored : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    static boolean refreshIfNeeded(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null || !users.isUserUnlocked()) return false;
        LocalPrefs state = LocalPrefs.open(context, "band-state");
        if (!state.getBoolean("registered", false)) return false;
        String model = state.getString("modelId", "");
        String sku = state.getString("sku", "");
        if (model.isBlank()) return false;
        LocalPrefs image = LocalPrefs.open(context, "device-image");
        boolean same = model.equals(image.getString("model", "")) && sku.equals(image.getString("sku", ""));
        boolean samePick = MiProductImage.PICK.equals(image.getString("pick", ""));
        File current = new File(image.getString("file", ""));
        boolean fresh = same && samePick && current.isFile() && current.length() > 0
                && System.currentTimeMillis() - image.getLong("fetchedAtMs", 0) < FRESH_MS;
        if (fresh) return false;
        long lastAttempt = image.getLong("lastAttemptMs", 0);
        boolean attempted = model.equals(image.getString("attemptModel", ""))
                && sku.equals(image.getString("attemptSku", ""))
                && samePick;
        if (attempted && lastAttempt > 0 && System.currentTimeMillis() - lastAttempt < RETRY_MS) return false;
        image.edit().putLong("lastAttemptMs", System.currentTimeMillis())
                .putString("attemptModel", model).putString("attemptSku", sku).commit();
        try {
            String config = MiFitnessDatabase.productConfig(context);
            MiProductImage.Choice choice = MiProductImage.choose(config, model, sku);
            if (choice == null) {
                SessionLog.line(context, "bridge DEVICE_IMAGE_UNAVAILABLE MI_PRODUCT_CONFIG_MISSING");
                return false;
            }
            byte[] encoded = download(choice.url());
            long version = image.getLong("version", 0) + 1;
            File dir = new File(context.getFilesDir(), "device-image");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                SessionLog.line(context, "bridge DEVICE_IMAGE_UNAVAILABLE DEVICE_IMAGE_STORE");
                return false;
            }
            File next = new File(dir, "body-" + version + ".png");
            File temp = new File(dir, "body-" + version + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(encoded);
            }
            if (!temp.renameTo(next)) {
                temp.delete();
                SessionLog.line(context, "bridge DEVICE_IMAGE_UNAVAILABLE DEVICE_IMAGE_STORE");
                return false;
            }
            String previous = image.getString("file", "");
            if (!image.edit().putString("model", model).putString("sku", sku)
                    .putString("pick", MiProductImage.PICK)
                    .putString("url", choice.url()).putString("source", choice.source())
                    .putString("file", next.getAbsolutePath())
                    .putLong("fetchedAtMs", System.currentTimeMillis())
                    .putLong("version", version).commit()) {
                next.delete();
                SessionLog.line(context, "bridge DEVICE_IMAGE_UNAVAILABLE DEVICE_IMAGE_STORE");
                return false;
            }
            if (!previous.isEmpty() && !previous.equals(next.getAbsolutePath())) new File(previous).delete();
            SessionLog.line(context, "bridge DEVICE_IMAGE source=" + choice.source() + " version=" + version);
            return true;
        } catch (MiFitnessDatabase.Failure failure) {
            SessionLog.line(context, "bridge DEVICE_IMAGE_UNAVAILABLE " + failure.code);
            return false;
        } catch (Exception failure) {
            SessionLog.line(context, "bridge DEVICE_IMAGE_UNAVAILABLE " + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static byte[] download(String address) throws Exception {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol())) throw new IllegalStateException("DEVICE_IMAGE_URL");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(true);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IllegalStateException("DEVICE_IMAGE_HTTP");
            if (connection.getURL() == null || !"https".equals(connection.getURL().getProtocol())) {
                throw new IllegalStateException("DEVICE_IMAGE_URL");
            }
            String type = connection.getContentType();
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
                throw new IllegalStateException("DEVICE_IMAGE_TYPE");
            }
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                while (total < MAX_BYTES) {
                    int read = in.read(buffer, 0, Math.min(buffer.length, MAX_BYTES - total));
                    if (read < 0) break;
                    raw.write(buffer, 0, read);
                    total += read;
                }
                if (in.read() >= 0) throw new IllegalStateException("DEVICE_IMAGE_SIZE");
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            byte[] bytes = raw.toByteArray();
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > MAX_EDGE || bounds.outHeight > MAX_EDGE) {
                throw new IllegalStateException("DEVICE_IMAGE_BOUNDS");
            }
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) throw new IllegalStateException("DEVICE_IMAGE_DECODE");
            try {
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) {
                    throw new IllegalStateException("DEVICE_IMAGE_DECODE");
                }
                return png.toByteArray();
            } finally {
                bitmap.recycle();
            }
        } finally {
            connection.disconnect();
        }
    }
}
