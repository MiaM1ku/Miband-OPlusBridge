// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import org.junit.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class SppNegotiationTest {
    // The first two official reconnect frames contain only envelope fields and protocol version.
    private static final byte[] REQUEST = HexFormat.of().parseHex("badcfe00c00300000300ef");
    private static final byte[] RESPONSE = HexFormat.of().parseHex("badcfe0000060001020003020fef");

    @Test public void capturedStartupRequestAndResponseHaveIndependentSerials() {
        assertArrayEquals(REQUEST, SppNegotiation.versionRequest(3));
        SppNegotiation.VersionResponse response = SppNegotiation.decodeVersionResponse(RESPONSE);
        assertEquals(2, response.opcodeSerial());
        assertEquals("3.2.15", response.versionName());
        assertEquals(197135, response.apiVersion());
    }

    @Test public void startupEnvelopeMustBeCompleteAndTerminated() {
        for (int size = 0; size < RESPONSE.length; size++) {
            byte[] truncated = Arrays.copyOf(RESPONSE, size);
            assertThrows(IllegalArgumentException.class, () -> SppNegotiation.decodeVersionResponse(truncated));
        }
        byte[] damaged = RESPONSE.clone();
        damaged[damaged.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> SppNegotiation.decodeVersionResponse(damaged));
    }
}
