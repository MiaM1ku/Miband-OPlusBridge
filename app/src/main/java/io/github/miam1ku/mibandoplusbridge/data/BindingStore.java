// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import android.os.UserManager;
import android.util.AtomicFile;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/**
 * Credential-encrypted storage, no AndroidKeyStore.
 *
 * History: v1/v2 encrypted the binding with an AndroidKeyStore key. On ColorOS the keystore
 * alias silently disappears (OTA, system cleanup), which permanently bricked every saved
 * binding ("BINDING_KEY_UNAVAILABLE" loop). The binding now lives in a plaintext JSON file
 * inside the credential-encrypted no-backup directory: not readable by other apps, not
 * backed up, and immune to keystore loss. A legacy binding.enc is migrated when its key
 * still works, otherwise it is discarded and the user re-imports once.
 */
public final class BindingStore {
    private static final String LEGACY_ALIAS_V2 = "oplusband.binding.v2";
    private static final String LEGACY_ALIAS_V1 = "oplusband.binding.v1";
    private static final byte[] LEGACY_AAD =
            "io.github.miam1ku.mibandoplusbridge.binding.v1".getBytes(StandardCharsets.UTF_8);
    private final Context context;
    private final AtomicFile file;
    private final File legacyFile;
    public record Identity(String deviceId, String did, String address, String model) { }
    private record IdentitySnapshot(String path, long modified, long length, Identity identity) { }
    private static volatile IdentitySnapshot publicIdentity;

    public BindingStore(Context context) {
        this.context = context;
        if (context.isDeviceProtectedStorage()) throw new IllegalArgumentException("CE_STORAGE_REQUIRED");
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "binding.json"));
        legacyFile = new File(context.getNoBackupFilesDir(), "binding.enc");
    }

    public synchronized void save(JSONObject value) throws Exception {
        requireUnlocked();
        byte[] plaintext = value.toString().getBytes(StandardCharsets.UTF_8);
        try {
            write(plaintext);
            publicIdentity = null;
            discardLegacy();
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public synchronized JSONObject read() throws Exception {
        requireUnlocked();
        if (file.getBaseFile().exists()) return parse(readFile());
        if (!legacyFile.exists()) return null;
        JSONObject legacy = migrateLegacy();
        if (legacy != null) {
            save(legacy);
            SessionLog.line(context, "bridge BINDING_MIGRATED_PLAINTEXT");
            return legacy;
        }
        return null;
    }

    /** An established session can check public identity without decrypting its token while locked. */
    public Identity readIdentity() throws Exception {
        requireUnlocked();
        IdentitySnapshot known = publicIdentity;
        File source = file.getBaseFile();
        if (known != null && known.path.equals(source.getAbsolutePath())
                && known.modified == source.lastModified() && known.length == source.length()) return known.identity;
        JSONObject binding = read();
        if (binding == null) throw new IllegalStateException("UNPROVISIONED");
        known = publicIdentity;
        if (known == null) throw new IllegalStateException("DEVICE_IDENTITY_UNCONFIRMED");
        return known.identity;
    }

    private void cacheIdentity(JSONObject binding) {
        publicIdentity = null;
        try {
            Identity identity = new Identity(BandStateRepository.deviceId(binding), binding.optString("did", ""),
                    binding.optString("address", ""), binding.optString("model", ""));
            File source = file.getBaseFile();
            publicIdentity = new IdentitySnapshot(source.getAbsolutePath(), source.lastModified(), source.length(), identity);
        } catch (Exception incomplete) {
            // Incomplete imports stay available to configuration. A live identity check waits for a complete import.
        }
    }

    private void requireUnlocked() {
        if (!context.getSystemService(UserManager.class).isUserUnlocked()) {
            throw new IllegalStateException("USER_LOCKED");
        }
    }

    private JSONObject parse(byte[] plaintext) throws Exception {
        try {
            JSONObject binding = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
            cacheIdentity(binding);
            return binding;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private byte[] readFile() throws Exception {
        if (file.getBaseFile().length() > 65536) throw new IllegalStateException("INVALID_BINDING_SIZE");
        return file.readFully();
    }

    private void write(byte[] plaintext) throws Exception {
        java.io.FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(plaintext);
            file.finishWrite(output);
        } catch (Exception failure) {
            if (output != null) file.failWrite(output);
            throw failure;
        }
    }

    private void discardLegacy() {
        if (legacyFile.exists() && legacyFile.delete()) {
            SessionLog.line(context, "bridge BINDING_KEY_RESET");
        }
    }

    /** Best-effort one-shot migration of the old keystore-encrypted binding. */
    private JSONObject migrateLegacy() {
        try {
            byte[] plaintext = legacyDecrypt();
            if (plaintext == null) return null;
            return new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
        } catch (Exception failed) {
            SessionLog.line(context, "bridge BINDING_MIGRATE_FAILED "
                    + failed.getClass().getSimpleName());
            return null;
        } finally {
            // Key is unreadable or envelope broken: the binding is unrecoverable by design.
            discardLegacy();
        }
    }

    private byte[] legacyDecrypt() throws Exception {
        if (legacyFile.length() > 65536) return null;
        byte[] envelope = new AtomicFile(legacyFile).readFully();
        if (envelope.length < 29 || envelope[0] != 1) return null;
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        for (String alias : new String[]{LEGACY_ALIAS_V2, LEGACY_ALIAS_V1}) {
            if (!store.containsAlias(alias)) continue;
            SecretKey key = (SecretKey) store.getKey(alias, null);
            if (key == null) continue;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, envelope, 1, 12));
            cipher.updateAAD(LEGACY_AAD);
            byte[] plaintext = cipher.doFinal(envelope, 13, envelope.length - 13);
            store.deleteEntry(alias);
            return plaintext;
        }
        return null;
    }
}
