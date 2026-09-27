// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Bounded, local-only analysis captures. No public read IPC and no logcat payloads. */
public final class ProtocolCaptureStore implements AutoCloseable {
    public static final int MAX_PAYLOAD = 65_536;
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private final File directory;
    private File file;
    private FileOutputStream output;
    private int records;
    private long bytes;

    public ProtocolCaptureStore(Context context) {
        if (context.isDeviceProtectedStorage()) throw new IllegalArgumentException("CE_STORAGE_REQUIRED");
        directory = new File(context.getNoBackupFilesDir(), "protocol-captures");
    }

    public void start() throws Exception {
        close();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("CAPTURE_DIRECTORY_FAILED");
        Os.chmod(directory.getPath(), 0700);
        file = File.createTempFile("capture-", ".jsonl", directory);
        Os.chmod(file.getPath(), 0600);
        output = new FileOutputStream(file);
        records = 0;
        bytes = 0;
    }

    public void append(Bundle record) throws Exception {
        if (output == null) throw new IllegalStateException("CAPTURE_CLOSED");
        byte[] payload = record.getByteArray("payload");
        String direction = record.getString("direction");
        if ((payload != null && payload.length > MAX_PAYLOAD)
                || !record.containsKey("capturedAtMs") || !record.containsKey("elapsedRealtimeNs")
                || !("tx".equals(direction) || "rx".equals(direction) || "event".equals(direction))) {
            throw new IllegalArgumentException("INVALID_CAPTURE_RECORD");
        }
        JSONObject row = new JSONObject();
        row.put("direction", direction);
        row.put("capturedAtMs", record.getLong("capturedAtMs"));
        row.put("elapsedRealtimeNs", record.getLong("elapsedRealtimeNs"));
        row.put("receivedAtMs", System.currentTimeMillis());
        row.put("receivedElapsedMs", SystemClock.elapsedRealtime());
        if (record.containsKey("massChannel")) row.put("massChannel", record.getInt("massChannel"));
        if (record.containsKey("callArgument0")) row.put("callArgument0", record.getInt("callArgument0"));
        if (record.containsKey("resultCode")) row.put("resultCode", record.getInt("resultCode"));
        if (record.containsKey("responseRequested")) row.put("responseRequested", record.getBoolean("responseRequested"));
        row.put("payloadBase64", payload == null ? JSONObject.NULL : Base64.encodeToString(payload, Base64.NO_WRAP));
        byte[] line = (row + "\n").getBytes(StandardCharsets.UTF_8);
        if (records >= 4096 || bytes + line.length > MAX_FILE_BYTES) {
            close();
            throw new IllegalStateException("CAPTURE_LIMIT_REACHED");
        }
        output.write(line);
        records++;
        bytes += line.length;
    }

    public void addStatus(Bundle status) {
        status.putInt("captureRecords", records);
        status.putLong("captureBytes", bytes);
        if (file != null) status.putString("captureFile", file.getName());
    }

    @Override public void close() throws Exception {
        if (output != null) {
            FileOutputStream closing = output;
            output = null;
            try {
                closing.getFD().sync();
            } finally {
                closing.close();
            }
        }
    }
}
