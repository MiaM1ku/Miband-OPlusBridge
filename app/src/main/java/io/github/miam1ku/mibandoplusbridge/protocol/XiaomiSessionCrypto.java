/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo, Yoran Vulker
 *  SPDX-License-Identifier: AGPL-3.0-or-later
 *
 *  Adapted from Gadgetbridge XiaomiAuthService.java at
 *  75f923904f8504b03fabdee0987fd1c269a92278.
 *
 *  Gadgetbridge is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published
 *  by the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Gadgetbridge is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CCMBlockCipher;
import org.bouncycastle.crypto.modes.CCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/** WearAuthV2 cryptography for the non-OOB branch. Does not parse protobuf or confirm device readiness. */
public final class XiaomiSessionCrypto {
    private static final byte[] AUTH_INFO = "miwear-auth".getBytes(StandardCharsets.US_ASCII);

    private XiaomiSessionCrypto() {}

    /**
     * Derives an inactive session. Each input must contain exactly 16 bytes.
     * The caller retains ownership of the inputs and must clear its binding key when no longer needed.
     * Callers must reject an OOB requirement before using this non-OOB API.
     */
    public static Session derive(byte[] bindingKey, byte[] phoneNonce, byte[] watchNonce) {
        require16(bindingKey, "bindingKey");
        require16(phoneNonce, "phoneNonce");
        require16(watchNonce, "watchNonce");
        byte[] nonces = new byte[32];
        System.arraycopy(phoneNonce, 0, nonces, 0, 16);
        System.arraycopy(watchNonce, 0, nonces, 16, 16);
        byte[] material = null;
        try {
            material = deriveMaterial(bindingKey, nonces);
            return new Session(material, nonces);
        } catch (RuntimeException e) {
            Arrays.fill(nonces, (byte) 0);
            throw e;
        } finally {
            if (material != null) Arrays.fill(material, (byte) 0);
        }
    }

    private static byte[] deriveMaterial(byte[] bindingKey, byte[] nonces) {
        byte[] extracted = hmacSha256(nonces, bindingKey);
        byte[] first = null;
        byte[] second = null;
        try {
            Mac mac = newHmac(extracted);
            mac.update(AUTH_INFO);
            mac.update((byte) 1);
            first = mac.doFinal();
            mac.update(first);
            mac.update(AUTH_INFO);
            mac.update((byte) 2);
            second = mac.doFinal();
            // Upstream expands 64 bytes; only the first 40 bytes are used.
            byte[] material = new byte[40];
            System.arraycopy(first, 0, material, 0, 32);
            System.arraycopy(second, 0, material, 32, 8);
            return material;
        } finally {
            Arrays.fill(extracted, (byte) 0);
            if (first != null) Arrays.fill(first, (byte) 0);
            if (second != null) Arrays.fill(second, (byte) 0);
        }
    }

    private static void require16(byte[] input, String name) {
        if (input == null || input.length != 16) {
            throw new IllegalArgumentException(name + " must contain exactly 16 bytes");
        }
    }

    private static Mac newHmac(byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot initialize HMAC-SHA256", e);
        }
    }

    static byte[] hmacSha256(byte[] key, byte[] input) {
        return newHmac(key).doFinal(input);
    }

    static byte[] ccmEncrypt(byte[] key, byte[] nonce, byte[] plaintext) {
        return ccm(true, key, nonce, plaintext);
    }

    static byte[] ccmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext) {
        return ccm(false, key, nonce, ciphertext);
    }

    private static byte[] ccm(boolean encrypt, byte[] key, byte[] nonce, byte[] input) {
        CCMModeCipher cipher = CCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(encrypt, new AEADParameters(new KeyParameter(key), 32, nonce, null));
        byte[] output = new byte[cipher.getOutputSize(input.length)];
        try {
            int written = cipher.processBytes(input, 0, input.length, output, 0);
            cipher.doFinal(output, written);
            return output;
        } catch (InvalidCipherTextException e) {
            Arrays.fill(output, (byte) 0);
            throw new IllegalStateException(encrypt ? "Cannot encrypt phone information" : "Cannot decrypt V1 payload", e);
        }
    }

    static byte[] ctrCrypt(int operation, byte[] key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(operation, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot process SPP V2 ciphertext", e);
        }
    }

    public static final class Session implements AutoCloseable {
        private enum State { DERIVED, WATCH_VERIFIED, ACTIVE, CLOSED }

        private final byte[] watchKey;
        private final byte[] phoneKey;
        private final byte[] phoneInfoNonce = new byte[12];
        private final byte[] watchIv = new byte[4];
        private final byte[] nonces;
        private State state = State.DERIVED;
        private boolean phoneInfoEncrypted;

        private Session(byte[] material, byte[] nonces) {
            watchKey = Arrays.copyOfRange(material, 0, 16);
            phoneKey = Arrays.copyOfRange(material, 16, 32);
            System.arraycopy(material, 32, watchIv, 0, 4);
            System.arraycopy(material, 36, phoneInfoNonce, 0, 4);
            this.nonces = nonces;
        }

        /** Verifies the full 32-byte proof once. A failed proof closes the session. */
        public synchronized boolean verifyWatchProof(byte[] proof) {
            requireState(State.DERIVED);
            Mac mac = newHmac(watchKey);
            mac.update(nonces, 16, 16);
            mac.update(nonces, 0, 16);
            byte[] expected = mac.doFinal();
            boolean verified;
            try {
                verified = MessageDigest.isEqual(expected, proof);
            } finally {
                Arrays.fill(expected, (byte) 0);
            }
            if (verified) {
                state = State.WATCH_VERIFIED;
            } else {
                close();
            }
            return verified;
        }

        /** Returns HMAC(phoneKey, phoneNonce || watchNonce) after successful watch verification. */
        public synchronized byte[] phoneProof() {
            requireState(State.WATCH_VERIFIED);
            return hmacSha256(phoneKey, nonces);
        }

        /**
         * Encrypts serialized phone information with a 4-byte CCM tag and no associated data.
         * The nonce is phoneIV || eight zero bytes. The caller must reuse the ciphertext for a retry.
         */
        public synchronized byte[] encryptPhoneInfo(byte[] plaintext) {
            requireState(State.WATCH_VERIFIED);
            Objects.requireNonNull(plaintext, "plaintext");
            if (phoneInfoEncrypted) throw new IllegalStateException("Phone information nonce already used");
            byte[] ciphertext = ccmEncrypt(phoneKey, phoneInfoNonce, plaintext);
            phoneInfoEncrypted = true;
            return ciphertext;
        }

        /** The caller must call this only after receiving a successful device authentication confirmation. */
        public synchronized void activate() {
            requireState(State.WATCH_VERIFIED);
            if (!phoneInfoEncrypted) throw new IllegalStateException("Phone confirmation has not been constructed");
            state = State.ACTIVE;
        }

        /** SPP V2 resets CTR for each message and uses the phone key itself as the IV. */
        public synchronized byte[] encryptV2(byte[] plaintext) {
            requireState(State.ACTIVE);
            Objects.requireNonNull(plaintext, "plaintext");
            return ctrCrypt(Cipher.ENCRYPT_MODE, phoneKey, phoneKey, plaintext);
        }

        /** SPP V2 uses the watch key as the IV. CTR does not authenticate the ciphertext. */
        public synchronized byte[] decryptV2(byte[] ciphertext) {
            requireState(State.ACTIVE);
            Objects.requireNonNull(ciphertext, "ciphertext");
            return ctrCrypt(Cipher.DECRYPT_MODE, watchKey, watchKey, ciphertext);
        }

        /** SPP V1 / BLE V1 encrypt with CCM. Counter 0 is the phone-info nonce. */
        public synchronized byte[] encryptV1(byte[] plaintext, int counter) {
            requireState(counter == 0 ? State.WATCH_VERIFIED : State.ACTIVE);
            Objects.requireNonNull(plaintext, "plaintext");
            return ccmEncrypt(phoneKey, v1Nonce(phoneInfoNonce, counter), plaintext);
        }

        /** SPP V1 / BLE V1 decrypt with the watch CCM IV and counter 0. */
        public synchronized byte[] decryptV1(byte[] ciphertext) {
            requireState(State.ACTIVE);
            Objects.requireNonNull(ciphertext, "ciphertext");
            return ccmDecrypt(watchKey, v1Nonce(watchIv, 0), ciphertext);
        }

        private static byte[] v1Nonce(byte[] iv, int counter) {
            byte[] nonce = new byte[12];
            System.arraycopy(iv, 0, nonce, 0, 4);
            nonce[8] = (byte) counter;
            nonce[9] = (byte) (counter >> 8);
            nonce[10] = (byte) (counter >> 16);
            nonce[11] = (byte) (counter >> 24);
            return nonce;
        }

        private void requireState(State required) {
            if (state != required) {
                throw new IllegalStateException("Session is " + state + "; required " + required);
            }
        }

        /** Clears arrays owned by this session. Does not clear caller-owned inputs or returned arrays. */
        @Override public synchronized void close() {
            Arrays.fill(watchKey, (byte) 0);
            Arrays.fill(phoneKey, (byte) 0);
            Arrays.fill(phoneInfoNonce, (byte) 0);
            Arrays.fill(watchIv, (byte) 0);
            Arrays.fill(nonces, (byte) 0);
            state = State.CLOSED;
        }
    }
}
