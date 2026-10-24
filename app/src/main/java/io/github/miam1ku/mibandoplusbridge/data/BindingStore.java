// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.UserManager;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Credential-encrypted storage. No plaintext file, backup, or direct-boot access. */
public final class BindingStore {
    private static final String ALIAS = "oplusband.binding.v2";
    private static final String LEGACY_ALIAS = "oplusband.binding.v1";
    private static final byte[] AAD = "io.github.miam1ku.mibandoplusbridge.binding.v1".getBytes(StandardCharsets.UTF_8);
    private final Context context;
    private final AtomicFile file;
    public record Identity(String deviceId, String did, String address, String model) { }
    private record IdentitySnapshot(String path, long modified, long length, Identity identity) { }
    private static volatile IdentitySnapshot publicIdentity;

    public BindingStore(Context context) {
        this.context = context;
        if (context.isDeviceProtectedStorage()) throw new IllegalArgumentException("CE_STORAGE_REQUIRED");
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "binding.enc"));
    }

    public synchronized void save(JSONObject value) throws Exception {
        requireUnlocked();
        byte[] plaintext = value.toString().getBytes(StandardCharsets.UTF_8);
        try {
            write(key(), plaintext);
            publicIdentity = null;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public synchronized JSONObject read() throws Exception {
        requireUnlocked();
        if (!file.getBaseFile().exists()) return null;
        SecretKey secret = key();
        if (!file.getBaseFile().exists()) return null;
        byte[] plaintext = decrypt(secret);
        try {
            JSONObject binding = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
            cacheIdentity(binding);
            return binding;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
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

    private boolean screenLocked() {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isDeviceLocked();
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            SecretKey current = usable(store, ALIAS);
            if (current != null) return current;
            return generate();
        }
        if (store.containsAlias(LEGACY_ALIAS)) {
            // v1 required an unlocked screen. That breaks pocket sync and sticks Keystore on OPlus.
            SecretKey legacy = usable(store, LEGACY_ALIAS);
            if (legacy == null) return generate();
            return migrate(store, legacy);
        }
        if (file.getBaseFile().exists()) throw new IllegalStateException("BINDING_KEY_UNAVAILABLE");
        return generate();
    }

    /** Usable key, or null after discard. A locked screen throws USER_LOCKED instead of discarding. */
    private SecretKey usable(KeyStore store, String alias) throws Exception {
        SecretKey existing;
        try {
            existing = (SecretKey) store.getKey(alias, null);
        } catch (UnrecoverableKeyException | java.security.KeyStoreException unreadable) {
            if (screenLocked()) throw new IllegalStateException("USER_LOCKED");
            discard(store, alias);
            return null;
        }
        if (existing == null) {
            if (screenLocked()) throw new IllegalStateException("USER_LOCKED");
            discard(store, alias);
            return null;
        }
        try {
            Cipher probe = Cipher.getInstance("AES/GCM/NoPadding");
            probe.init(Cipher.ENCRYPT_MODE, existing);
            return existing;
        } catch (java.security.InvalidKeyException unusable) {
            if (screenLocked()) throw new IllegalStateException("USER_LOCKED");
            discard(store, alias);
            return null;
        }
    }

    private SecretKey migrate(KeyStore store, SecretKey legacy) throws Exception {
        byte[] plaintext = file.getBaseFile().exists() ? decrypt(legacy) : null;
        try {
            if (file.getBaseFile().exists() && !file.getBaseFile().delete()) {
                throw new IllegalStateException("BINDING_KEY_UNAVAILABLE");
            }
            publicIdentity = null;
            store.deleteEntry(LEGACY_ALIAS);
            SecretKey created = generate();
            if (plaintext != null) write(created, plaintext);
            SessionLog.line(context, "bridge BINDING_KEY_RESET");
            return created;
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private void discard(KeyStore store, String alias) throws Exception {
        if (file.getBaseFile().exists() && !file.getBaseFile().delete()) {
            throw new IllegalStateException("BINDING_KEY_UNAVAILABLE");
        }
        publicIdentity = null;
        if (store.containsAlias(alias)) store.deleteEntry(alias);
        SessionLog.line(context, "bridge BINDING_KEY_RESET");
    }

    private SecretKey generate() throws Exception {
        if (file.getBaseFile().exists()) throw new IllegalStateException("BINDING_KEY_UNAVAILABLE");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private void write(SecretKey key, byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(AAD);
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] iv = cipher.getIV();
        if (iv.length != 12) throw new IllegalStateException("INVALID_IV_LENGTH");
        byte[] envelope = ByteBuffer.allocate(1 + iv.length + encrypted.length)
                .put((byte) 1).put(iv).put(encrypted).array();
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(envelope);
            file.finishWrite(output);
        } catch (Exception failure) {
            if (output != null) file.failWrite(output);
            throw failure;
        }
    }

    private byte[] decrypt(SecretKey key) throws Exception {
        if (file.getBaseFile().length() > 65536) throw new IllegalStateException("INVALID_BINDING_SIZE");
        byte[] envelope = file.readFully();
        if (envelope.length < 29 || envelope[0] != 1) throw new IllegalStateException("INVALID_ENVELOPE");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, envelope, 1, 12));
        cipher.updateAAD(AAD);
        return cipher.doFinal(envelope, 13, envelope.length - 13);
    }
}
