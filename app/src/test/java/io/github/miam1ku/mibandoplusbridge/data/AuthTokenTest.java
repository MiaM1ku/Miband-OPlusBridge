// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AuthTokenTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String ENCRYPT_KEY = "fedcba9876543210fedcba9876543210";

    @Test public void firstHex32SkipsInvalidThenReturns32Hex() {
        assertEquals(TOKEN, AuthToken.firstHex32("", "nothex", TOKEN));
    }

    @Test public void emptyTokenThenEncryptKeyWinsAndAllBlankIsEmpty() {
        assertEquals(ENCRYPT_KEY, AuthToken.firstHex32("", ENCRYPT_KEY));
        assertEquals("", AuthToken.firstHex32("", null, "short", "not-hex"));
        assertEquals("", AuthToken.firstHex32());
        assertEquals("", AuthToken.firstHex32((String[]) null));
    }

    @Test public void fromKeyBytesRequiresExactly16Bytes() {
        byte[] key = new byte[16];
        java.util.Arrays.fill(key, (byte) 0x01);
        assertEquals("01010101010101010101010101010101", AuthToken.fromKeyBytes(key));
        assertEquals("", AuthToken.fromKeyBytes(null));
        assertEquals("", AuthToken.fromKeyBytes(new byte[15]));
    }

    @Test public void hex32RejectsWrongLengthAndNonHex() {
        assertTrue(AuthToken.hex32(TOKEN));
        assertFalse(AuthToken.hex32(TOKEN.substring(1)));
        assertFalse(AuthToken.hex32("0123456789abcdef0123456789abcdeg"));
        assertFalse(AuthToken.hex32(null));
    }
}
