// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Cipher;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class XiaomiSessionCryptoTest {
    // Synthetic public inputs, not captured credentials or health data.
    private static final byte[] KEY = hex("000102030405060708090a0b0c0d0e0f");
    private static final byte[] PHONE_NONCE = hex("101112131415161718191a1b1c1d1e1f");
    private static final byte[] WATCH_NONCE = hex("202122232425262728292a2b2c2d2e2f");
    private static final byte[] PLAINTEXT = hex(
            "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51");
    private static final byte[] PHONE_INFO = hex("0800150000104222065055424c49432a02434e");

    // Independent reference answers: Python hmac.digest (SHA-256) and cryptography AESCCM/CTR.
    // Extract key=PHONE_NONCE||WATCH_NONCE, input=KEY; expand info="miwear-auth".
    private static final byte[] WATCH_PROOF = hex(
            "17aa8eecac21d9d71cba6ea653d126d77abc2f4d0357f1cce01d433d40e42235");
    private static final byte[] PHONE_PROOF = hex(
            "7e6e4d786282c1af4441b2ed315da93c8ae2c820a72df584d62315d73913a9dc");
    private static final byte[] PHONE_INFO_CIPHERTEXT = hex(
            "806dad1c239f04f8437a1b62c364e7bcb2fd5e901aeab5");
    private static final byte[] PHONE_CIPHERTEXT = hex(
            "d425fede5ef8e622b5a485b4323a5e1f44deb91d2333aa67847a2b0713cfb80f");
    private static final byte[] WATCH_CIPHERTEXT = hex(
            "6ecbaa231ccc1369df2bb3c4282beca1c971cb13658c160154d13acecba77f15");

    @Test public void hmacMatchesRfc4231Case1() {
        // https://www.rfc-editor.org/rfc/rfc4231#section-4.2
        byte[] key = new byte[20];
        Arrays.fill(key, (byte) 0x0b);
        assertArrayEquals(hex("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
                XiaomiSessionCrypto.hmacSha256(key, "Hi There".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test public void ctrMatchesNistSp80038aF5() {
        // NIST SP 800-38A, F.5.1/F.5.2: CTR-AES128, first two blocks.
        byte[] key = hex("2b7e151628aed2a6abf7158809cf4f3c");
        byte[] iv = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
        byte[] ciphertext = hex(
                "874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff");
        assertArrayEquals(ciphertext,
                XiaomiSessionCrypto.ctrCrypt(Cipher.ENCRYPT_MODE, key, iv, PLAINTEXT));
        assertArrayEquals(PLAINTEXT,
                XiaomiSessionCrypto.ctrCrypt(Cipher.DECRYPT_MODE, key, iv, ciphertext));
    }

    @Test public void ccmMatchesNistCavsDvpt128Count60() {
        // CAVS 11.0 CCM-DVPT128: Alen=0, Plen=24, Nlen=7, Tlen=4, Count=60 (Pass).
        // https://github.com/pyca/cryptography/blob/main/vectors/cryptography_vectors/ciphers/AES/CCM/DVPT128.rsp
        assertArrayEquals(hex("a90e8ea44085ced791b2fdb7fd44b5cf0bd7d27718029bb703e1fa6b"),
                XiaomiSessionCrypto.ccmEncrypt(
                        hex("19ebfde2d5468ba0a3031bde629b11fd"),
                        hex("5a8aa485c316e9"),
                        hex("3796cf51b8726652a4204733b8fbb047cf00fb91a9837e22")));
    }

    @Test public void knownAnswersRequireProofThenExplicitDeviceConfirmation() {
        try (XiaomiSessionCrypto.Session session = newSession()) {
            assertThrows(IllegalStateException.class, session::activate);
            assertThrows(IllegalStateException.class, session::phoneProof);
            assertThrows(IllegalStateException.class, () -> session.encryptPhoneInfo(PHONE_INFO));
            assertThrows(IllegalStateException.class, () -> session.encryptV2(PLAINTEXT));
            assertThrows(IllegalStateException.class, () -> session.decryptV2(WATCH_CIPHERTEXT));

            assertTrue(session.verifyWatchProof(WATCH_PROOF));
            assertThrows(IllegalStateException.class, session::activate);
            assertArrayEquals(PHONE_PROOF, session.phoneProof());
            assertArrayEquals(PHONE_INFO_CIPHERTEXT, session.encryptPhoneInfo(PHONE_INFO));
            assertThrows(IllegalStateException.class, () -> session.encryptPhoneInfo(new byte[]{1, 2, 3}));
            assertThrows(IllegalStateException.class, () -> session.encryptV2(PLAINTEXT));
            assertThrows(IllegalStateException.class, () -> session.decryptV2(WATCH_CIPHERTEXT));

            session.activate();
            assertArrayEquals(PHONE_CIPHERTEXT, session.encryptV2(PLAINTEXT));
            assertArrayEquals(PLAINTEXT, session.decryptV2(WATCH_CIPHERTEXT));
            // The observed V2 contract resets key-as-IV CTR for every complete message.
            assertArrayEquals(PHONE_CIPHERTEXT, session.encryptV2(PLAINTEXT));
        }
    }

    @Test public void wrongProofClosesSessionWithoutAllowingASecondAttempt() {
        XiaomiSessionCrypto.Session session = newSession();
        byte[] wrongProof = WATCH_PROOF.clone();
        wrongProof[0] ^= 1;
        assertFalse(session.verifyWatchProof(wrongProof));
        assertClosed(session);
    }

    @Test public void validProofPrefixIsNotACompleteProof() {
        XiaomiSessionCrypto.Session session = newSession();
        assertFalse(session.verifyWatchProof(Arrays.copyOf(WATCH_PROOF, 31)));
        assertClosed(session);
    }

    @Test public void missingProofCannotActivate() {
        XiaomiSessionCrypto.Session session = newSession();
        assertFalse(session.verifyWatchProof(null));
        assertClosed(session);
    }

    @Test public void closingAnActiveSessionRejectsEveryOperation() {
        XiaomiSessionCrypto.Session session = newSession();
        assertTrue(session.verifyWatchProof(WATCH_PROOF));
        session.encryptPhoneInfo(PHONE_INFO);
        session.activate();
        session.close();
        session.close();
        assertClosed(session);
    }

    @Test public void callerCanClearInputsAfterDerivation() {
        byte[] key = KEY.clone();
        byte[] phoneNonce = PHONE_NONCE.clone();
        byte[] watchNonce = WATCH_NONCE.clone();
        try (XiaomiSessionCrypto.Session session = XiaomiSessionCrypto.derive(key, phoneNonce, watchNonce)) {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(phoneNonce, (byte) 0);
            Arrays.fill(watchNonce, (byte) 0);
            assertTrue(session.verifyWatchProof(WATCH_PROOF));
            assertArrayEquals(PHONE_PROOF, session.phoneProof());
        }
    }

    @Test public void rejectsMissingTruncatedAndOversizedDerivationInputs() {
        for (byte[] invalid : new byte[][] {null, new byte[15], new byte[17]}) {
            assertThrows(IllegalArgumentException.class,
                    () -> XiaomiSessionCrypto.derive(invalid, PHONE_NONCE, WATCH_NONCE));
            assertThrows(IllegalArgumentException.class,
                    () -> XiaomiSessionCrypto.derive(KEY, invalid, WATCH_NONCE));
            assertThrows(IllegalArgumentException.class,
                    () -> XiaomiSessionCrypto.derive(KEY, PHONE_NONCE, invalid));
        }
    }

    private static XiaomiSessionCrypto.Session newSession() {
        return XiaomiSessionCrypto.derive(KEY, PHONE_NONCE, WATCH_NONCE);
    }

    private static void assertClosed(XiaomiSessionCrypto.Session session) {
        assertThrows(IllegalStateException.class, () -> session.verifyWatchProof(WATCH_PROOF));
        assertThrows(IllegalStateException.class, session::phoneProof);
        assertThrows(IllegalStateException.class, () -> session.encryptPhoneInfo(PHONE_INFO));
        assertThrows(IllegalStateException.class, session::activate);
        assertThrows(IllegalStateException.class, () -> session.encryptV2(PLAINTEXT));
        assertThrows(IllegalStateException.class, () -> session.decryptV2(WATCH_CIPHERTEXT));
    }

    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }
}
