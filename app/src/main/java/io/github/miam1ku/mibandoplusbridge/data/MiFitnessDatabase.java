// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Copies com.mi.health device_db with root and reads it in this app. The copy is deleted. */
public final class MiFitnessDatabase {
    private static final String SOURCE = "/data/data/com.mi.health/databases";

    public static final class Snapshot {
        public final String userId;
        public final String region;
        public final List<MiFitnessDeviceRows.Detail> details;

        Snapshot(String userId, String region, List<MiFitnessDeviceRows.Detail> details) {
            this.userId = userId;
            this.region = region;
            this.details = details;
        }
    }

    public static final class Failure extends Exception {
        public final String code;

        Failure(String code) {
            this.code = code;
        }
    }

    private MiFitnessDatabase() {}

    public static Snapshot read(Context context) throws Failure {
        File dir = new File(context.getNoBackupFilesDir(), "mihealth-read");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Failure("MI_DATABASE_UNREADABLE");
        try {
            String userId = copy(dir);
            return open(dir, userId);
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) file.delete();
        }
    }

    private static Snapshot open(File dir, String userId) throws Failure {
        File device = new File(dir, "device_db");
        if (!device.isFile()) throw new Failure("MI_DATABASE_MISSING");
        SQLiteDatabase database = null;
        SQLiteDatabase profile = null;
        try {
            // The copy is private. Read-write applies the WAL. The live shm is not copied:
            // it carries the official app's lock and the open would wait on that process.
            database = SQLiteDatabase.openDatabase(device.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            database.rawQuery("PRAGMA busy_timeout=2000", null).close();
            ArrayList<MiFitnessDeviceRows.Detail> details = new ArrayList<>();
            try (Cursor cursor = database.rawQuery(
                    "SELECT model, name, productId, did, detail FROM device", null)) {
                while (cursor.moveToNext()) {
                    details.add(new MiFitnessDeviceRows.Detail(
                            cursor.getString(0), cursor.getString(1),
                            cursor.isNull(2) ? "" : String.valueOf(cursor.getLong(2)),
                            cursor.getString(3), cursor.getString(4)));
                }
            }
            String region = "";
            File users = new File(dir, "fitness_user");
            if (users.isFile()) {
                profile = SQLiteDatabase.openDatabase(users.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
                profile.rawQuery("PRAGMA busy_timeout=2000", null).close();
                try (Cursor cursor = profile.rawQuery("SELECT region FROM user_info_profile LIMIT 1", null)) {
                    if (cursor.moveToFirst()) region = cursor.getString(0) == null ? "" : cursor.getString(0).trim();
                }
            }
            if (region.length() > 8) region = "";
            return new Snapshot(userId, region, details);
        } catch (RuntimeException unreadable) {
            throw new Failure("MI_DATABASE_UNREADABLE");
        } finally {
            if (database != null) database.close();
            if (profile != null) profile.close();
        }
    }

    private static String copy(File dir) throws Failure {
        File script = new File(dir, "copy.sh");
        // KernelSU runs su -c through another shell. A quoted command is split and exits 1.
        // The script path has no spaces, so su receives one plain argument.
        writeScript(script, dir.getAbsolutePath());
        String text = execute(script.getAbsolutePath());
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.matches("[0-9]{5,}")) return trimmed;
        }
        return "";
    }

    private static void writeScript(File script, String dest) throws Failure {
        if (!dest.matches("[A-Za-z0-9_./-]+")) throw new Failure("MI_DATABASE_UNREADABLE");
        String source = SOURCE;
        String body = "set -eu\n"
                + "test -f " + source + "/device_db\n"
                + "rm -f " + dest + "/device_db " + dest + "/device_db-wal " + dest + "/device_db-shm "
                + dest + "/fitness_user " + dest + "/fitness_user-wal " + dest + "/fitness_user-shm\n"
                + "cp -a " + source + "/device_db " + dest + "/device_db\n"
                + "if [ -f " + source + "/device_db-wal ]; then cp -a " + source + "/device_db-wal " + dest + "/device_db-wal; fi\n"
                + "if [ -f " + source + "/fitness_user ]; then cp -a " + source + "/fitness_user " + dest + "/fitness_user; fi\n"
                + "if [ -f " + source + "/fitness_user-wal ]; then cp -a " + source + "/fitness_user-wal " + dest + "/fitness_user-wal; fi\n"
                + "owner=$(stat -c %u " + dest + ")\n"
                + "group=$(stat -c %g " + dest + ")\n"
                + "chown \"$owner:$group\" " + dest + "/*\n"
                + "chmod 600 " + dest + "/*\n"
                + "ls -1 " + source + " | grep -E '^[0-9]{5,}$' | head -n 1\n";
        try (FileOutputStream out = new FileOutputStream(script)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new Failure("MI_DATABASE_UNREADABLE");
        }
    }

    private static String execute(String scriptPath) throws Failure {
        if (!scriptPath.matches("[A-Za-z0-9_./-]+")) throw new Failure("MI_DATABASE_UNREADABLE");
        Process process = null;
        try {
            // -M leaves the app mount namespace, where other apps' /data/data is hidden.
            process = new ProcessBuilder("su", "-M", "-c", "sh " + scriptPath)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new Failure("ROOT_ACTION_TIMEOUT");
            }
            String text = readLimited(process.getInputStream(), 4096);
            if (process.exitValue() != 0) {
                throw new Failure(text.contains("No such file") ? "MI_DATABASE_MISSING" : "ROOT_ACTION_FAILED");
            }
            return text;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Failure("ROOT_ACTION_FAILED");
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String readLimited(InputStream in, int maxOutput) throws IOException {
        byte[] buf = new byte[1024];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int total = 0;
        while (total < maxOutput) {
            int n = in.read(buf, 0, Math.min(buf.length, maxOutput - total));
            if (n < 0) return out.toString(StandardCharsets.UTF_8);
            out.write(buf, 0, n);
            total += n;
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
