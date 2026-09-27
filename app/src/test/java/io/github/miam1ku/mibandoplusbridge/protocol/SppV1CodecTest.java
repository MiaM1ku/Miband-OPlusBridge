// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import org.junit.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SppV1CodecTest {
    private static final byte[] VERSION_REQUEST = HexFormat.of().parseHex("badcfe00c00300000300ef");
    private static final byte[] VERSION_RESPONSE = HexFormat.of().parseHex("badcfe0000060001020003020fef");
    private static final byte[] V1_VERSION = HexFormat.of().parseHex("badcfe00000600010000010032ef");

    @Test public void capturedBand11VersionEnvelopeRoundTrips() {
        assertArrayEquals(VERSION_REQUEST, SppNegotiation.versionRequest(3));
        SppV1Codec.Packet request = SppV1Codec.decode(VERSION_REQUEST);
        assertEquals(0, request.channel());
        assertTrue(request.needsResponse());
        assertEquals(0, request.opcode());
        SppV1Codec.Packet response = SppV1Codec.decode(VERSION_RESPONSE);
        assertEquals(0, response.channel());
        assertArrayEquals(new byte[]{3, 2, 15}, response.payload());
        assertTrue((response.payload()[0] & 0xff) >= 2);
    }

    @Test public void band8ProStyleVersionStaysOnV1() {
        SppV1Codec.Packet packet = SppV1Codec.decode(V1_VERSION);
        assertEquals(0, packet.channel());
        assertEquals(1, packet.payload()[0] & 0xff);
        assertArrayEquals(V1_VERSION, SppV1Codec.encode(packet));
    }

    @Test public void encryptedProtobufCarriesLittleEndianCounter() {
        byte[] cipher = new byte[]{10, 11, 12};
        assertArrayEquals(new byte[]{1, 0, 10, 11, 12}, SppV1Codec.sealEncrypted(1, cipher));
        assertArrayEquals(new byte[]{0x34, 0x12, 10, 11, 12}, SppV1Codec.sealEncrypted(0x1234, cipher));
    }

    @Test public void protobufPacketsSurviveByteSplits() {
        byte[] payload = new byte[]{8, 1, 16, 26};
        byte[] encoded = SppV1Codec.encode(SppV1Codec.protobuf(4, SppV1Codec.DATA_AUTH, payload));
        SppV1Codec.Decoder decoder = new SppV1Codec.Decoder();
        List<SppV1Codec.Packet> packets = List.of();
        for (int i = 0; i < encoded.length; i++) {
            List<SppV1Codec.Packet> part = decoder.feed(encoded, i, 1);
            if (!part.isEmpty()) packets = part;
        }
        assertEquals(1, packets.size());
        assertEquals(SppV1Codec.CHANNEL_PROTO_TX, packets.get(0).channel());
        assertEquals(SppV1Codec.DATA_AUTH, packets.get(0).dataType());
        assertArrayEquals(payload, packets.get(0).payload());
    }
}
