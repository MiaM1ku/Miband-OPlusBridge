// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Local session trace. Callers must not pass tokens, MAC addresses, nonces, or ciphertext. */
public final class SessionLog {
    private static final long MAX_BYTES = 256 * 1024;
    private static final Object LOCK = new Object();

    private SessionLog() {}

    public static void line(Context context, String message) {
        if (context == null || message == null || message.isBlank()) return;
        String row = Instant.now() + " " + message.replace('\n', ' ').replace('\r', ' ') + "\n";
        byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            try {
                File file = logFile(context);
                if (file.length() + bytes.length > MAX_BYTES && file.isFile()) {
                    byte[] existing = Files.readAllBytes(file.toPath());
                    int keep = existing.length / 2;
                    try (FileOutputStream out = new FileOutputStream(file, false)) {
                        out.write("… earlier lines trimmed\n".getBytes(StandardCharsets.UTF_8));
                        out.write(existing, existing.length - keep, keep);
                    }
                }
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(bytes);
                }
            } catch (IOException ignored) { }
        }
    }

    public static File export(Context context) throws IOException {
        File dir = new File(context.getCacheDir(), "debug");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("DEBUG_LOG_UNAVAILABLE");
        File out = new File(dir, "band-debug.txt");
        StringBuilder text = new StringBuilder();
        text.append("miband-oplusbridge debug\n");
        File live = new File(context.getNoBackupFilesDir(), "live-status.txt");
        if (live.isFile() && live.length() < 512) {
            text.append("live-status ").append(new String(Files.readAllBytes(live.toPath()), StandardCharsets.UTF_8).trim()).append('\n');
        }
        File log = logFile(context);
        if (log.isFile()) text.append(new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8));
        if (text.length() > MAX_BYTES) text.delete(0, text.length() - (int) MAX_BYTES);
        Files.write(out.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        return out;
    }

    private static File logFile(Context context) {
        return new File(context.getNoBackupFilesDir(), "session-log.txt");
    }
}
