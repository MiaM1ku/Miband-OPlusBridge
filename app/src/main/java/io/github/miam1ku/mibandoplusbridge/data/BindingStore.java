// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

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
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Credential-encrypted storage. No plaintext file, backup, or direct-boot access. */
public final class BindingStore {
    private static final String ALIAS = "oplusband.binding.v1";
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
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(AAD);
        byte[] plaintext = value.toString().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted;
        try {
            encrypted = cipher.doFinal(plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
        byte[] iv = cipher.getIV();
        if (iv.length != 12) throw new IllegalStateException("INVALID_IV_LENGTH");
        byte[] envelope = ByteBuffer.allocate(1 + iv.length + encrypted.length)
                .put((byte) 1).put(iv).put(encrypted).array();
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(envelope);
            file.finishWrite(output);
            publicIdentity = null;
        } catch (Exception failure) {
            if (output != null) file.failWrite(output);
            throw failure;
        }
    }

    public synchronized JSONObject read() throws Exception {
        requireUnlocked();
        if (!file.getBaseFile().exists()) return null;
        if (file.getBaseFile().length() > 65536) throw new IllegalStateException("INVALID_BINDING_SIZE");
        byte[] envelope = file.readFully();
        if (envelope.length < 29 || envelope[0] != 1) throw new IllegalStateException("INVALID_ENVELOPE");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, envelope, 1, 12));
        cipher.updateAAD(AAD);
        byte[] plaintext = cipher.doFinal(envelope, 13, envelope.length - 13);
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
            // Incomplete imports stay available to configuration, never to a live identity check.
        }
    }

    private void requireUnlocked() {
        if (!context.getSystemService(UserManager.class).isUserUnlocked()) {
            throw new IllegalStateException("USER_LOCKED");
        }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        // Never silently replace the key for an existing ciphertext.
        if (file.getBaseFile().exists()) throw new IllegalStateException("BINDING_KEY_UNAVAILABLE");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUnlockedDeviceRequired(true).build());
        return generator.generateKey();
    }
}
