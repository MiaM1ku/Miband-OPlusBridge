// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class BleV1CodecTest {
    @Test public void smallPayloadUsesSingleFrame() {
        byte[] payload = new byte[]{1, 2, 3, 4};
        List<byte[]> frames = BleV1Codec.encodeOutgoing(payload, 20, false);
        assertEquals(1, frames.size());
        BleV1Codec.Reassembler reassembler = new BleV1Codec.Reassembler();
        assertArrayEquals(payload, reassembler.accept(frames.get(0)));
    }

    @Test public void largePayloadReassemblesInOrder() {
        byte[] payload = new byte[40];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        List<byte[]> frames = BleV1Codec.encodeOutgoing(payload, 10, false);
        assertTrue(frames.size() > 2);
        assertEquals(BleV1Codec.TYPE_CHUNK_START, frames.get(0)[2]);
        BleV1Codec.Reassembler reassembler = new BleV1Codec.Reassembler();
        byte[] result = null;
        for (byte[] frame : frames) {
            byte[] part = reassembler.accept(frame);
            if (part != null) result = part;
        }
        assertArrayEquals(payload, result);
    }

    @Test public void encryptedSingleFrameCarriesCounter() {
        byte[] payload = new byte[]{9, 8, 7};
        List<byte[]> frames = BleV1Codec.encodeOutgoing(payload, 20, true, 2);
        assertEquals(1, frames.size());
        assertEquals(1, frames.get(0)[3]);
        assertEquals(2, frames.get(0)[4]);
        assertEquals(0, frames.get(0)[5]);
        assertEquals(9, frames.get(0)[6]);
    }

    @Test public void encryptedChunkedFramePrefixesCounter() {
        byte[] payload = new byte[40];
        List<byte[]> frames = BleV1Codec.encodeOutgoing(payload, 10, true, 1);
        assertEquals(1, frames.get(0)[3]);
        BleV1Codec.Reassembler reassembler = new BleV1Codec.Reassembler();
        byte[] result = null;
        for (byte[] frame : frames) {
            byte[] part = reassembler.accept(frame);
            if (part != null) result = part;
        }
        assertEquals(42, result.length);
        assertEquals(1, result[0]);
        assertEquals(0, result[1]);
    }

    @Test public void payloadAckIsIgnored() {
        BleV1Codec.Reassembler reassembler = new BleV1Codec.Reassembler();
        assertNull(reassembler.accept(BleV1Codec.PAYLOAD_ACK));
        assertTrue(reassembler.awaitingAck(BleV1Codec.PAYLOAD_ACK));
    }
}
