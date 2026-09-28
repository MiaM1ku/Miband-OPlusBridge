// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SppV2CodecTest {
    // Version/configuration/ACK bytes only, from the 2026-09-24 official reconnect capture.
    // These contain no device identity, nonces, proofs, credentials or health data.
    private static final byte[] SESSION_REQUEST = hex(
            "a5a5020016001d4d0101030001000002020000fc03020020000402001027");
    private static final byte[] SESSION_RESPONSE = hex(
            "a5a50200160084330201030003020f020200008003020003000402007017");
    private static final byte[] ACK_ONE = hex("a5a5010100000000");

    @Test public void capturedFramesSurviveEverySplitAndCoalescingBoundary() {
        byte[] stream = join(SESSION_RESPONSE, ACK_ONE, SESSION_REQUEST);
        for (int split = 0; split <= stream.length; split++) {
            SppV2Codec.Decoder decoder = new SppV2Codec.Decoder();
            List<SppV2Codec.Frame> frames = new ArrayList<>(decoder.feed(stream, 0, split));
            frames.addAll(decoder.feed(stream, split, stream.length - split));
            assertFrames(frames);
        }
        SppV2Codec.Decoder decoder = new SppV2Codec.Decoder();
        List<SppV2Codec.Frame> frames = new ArrayList<>();
        for (int i = 0; i < stream.length; i++) frames.addAll(decoder.feed(stream, i, 1));
        assertFrames(frames);
    }

    @Test public void checksumFailureCannotBeSkippedBeforeAValidFrame() {
        byte[] damaged = SESSION_RESPONSE.clone();
        damaged[damaged.length - 1] ^= 1;
        byte[] stream = join(damaged, ACK_ONE);
        SppV2Codec.Decoder decoder = new SppV2Codec.Decoder();
        assertThrows(IllegalArgumentException.class, () -> decoder.feed(stream, 0, stream.length));
        assertThrows(IllegalStateException.class, () -> decoder.feed(ACK_ONE, 0, ACK_ONE.length));
        decoder.reset();
        List<SppV2Codec.Frame> frames = decoder.feed(ACK_ONE, 0, ACK_ONE.length);
        assertEquals(1, frames.size());
        assertEquals(1, frames.get(0).sequence());
    }

    @Test public void declaredPayloadLimitIsEnforcedAtTheHeaderBoundary() {
        SppV2Codec.Decoder decoder = new SppV2Codec.Decoder(21);
        assertTrue(decoder.feed(SESSION_RESPONSE, 0, 7).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> decoder.feed(SESSION_RESPONSE, 7, 1));
    }

    @Test public void capturedConfigurationRequestHasTheExactWireChecksum() {
        assertArrayEquals(SESSION_REQUEST, SppV2Codec.encode(SppNegotiation.startSessionRequest()));
    }

    private static void assertFrames(List<SppV2Codec.Frame> frames) {
        assertEquals(3, frames.size());
        SppV2Codec.Frame response = frames.get(0);
        assertEquals(SppV2Codec.TYPE_SESSION_CONFIG, response.type());
        assertEquals(0, response.sequence());
        assertArrayEquals(hex("0201030003020f020200008003020003000402007017"), response.payload());
        SppV2Codec.SessionConfig config = SppV2Codec.parseSessionConfig(response);
        assertEquals(2, config.opcode());
        assertArrayEquals(hex("03020f"), config.version());
        assertEquals(Integer.valueOf(32768), config.maxPacketSize());
        assertEquals(Integer.valueOf(3), config.txWindow());
        assertEquals(Integer.valueOf(6000), config.sendTimeoutMillis());
        assertTrue(config.unknownParameters().isEmpty());
        assertEquals(SppV2Codec.TYPE_ACK, frames.get(1).type());
        assertEquals(1, frames.get(1).sequence());
        assertArrayEquals(new byte[0], frames.get(1).payload());
        assertEquals(SppV2Codec.TYPE_SESSION_CONFIG, frames.get(2).type());
        assertArrayEquals(hex("0101030001000002020000fc03020020000402001027"), frames.get(2).payload());
    }

    @Test public void frxAndNakUseTheTypeNibble() {
        byte[] frx = hex("a5a51300020000000102");
        // checksum of {1, 2}
        int crc = 0;
        for (byte value : new byte[] {1, 2}) {
            crc = (crc ^ (value & 0xff)) & 0xffff;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 1) == 0 ? crc >>> 1 : (crc >>> 1) ^ 0xa001;
        }
        frx[6] = (byte) crc;
        frx[7] = (byte) (crc >>> 8);
        SppV2Codec.Frame frame = new SppV2Codec.Decoder().feed(frx, 0, frx.length).get(0);
        assertEquals(SppV2Codec.TYPE_DATA, frame.type());
        assertTrue(frame.frx());
        assertEquals(0, frame.sequence());
        byte[] nak = hex("a5a5000400000000");
        SppV2Codec.Frame nakFrame = new SppV2Codec.Decoder().feed(nak, 0, nak.length).get(0);
        assertEquals(SppV2Codec.TYPE_NAK, nakFrame.type());
        assertEquals(4, nakFrame.sequence());
        assertFalse(nakFrame.frx());
    }

    @Test public void aheadPacketIsNakedOnceThenAccepted() {
        SppV2Codec.ReceiveCursor cursor = new SppV2Codec.ReceiveCursor();
        assertEquals(SppV2Codec.ReceiveCursor.Decision.TAKE, cursor.offer(0, false, 1));
        assertEquals(SppV2Codec.ReceiveCursor.Decision.NAK, cursor.offer(2, false, 1));
        assertEquals(1, cursor.expected());
        assertEquals(SppV2Codec.ReceiveCursor.Decision.TAKE, cursor.offer(2, false, 1));
        assertEquals(3, cursor.expected());
        assertEquals(SppV2Codec.ReceiveCursor.Decision.DROP, cursor.offer(9, false, 7));
        assertEquals(3, cursor.expected());
        assertEquals(SppV2Codec.ReceiveCursor.Decision.TAKE, cursor.offer(0, false, 1));
        assertEquals(1, cursor.expected());
        assertEquals(SppV2Codec.ReceiveCursor.Decision.TAKE, cursor.offer(7, true, 1));
        assertEquals(8, cursor.expected());
    }

    private static byte[] join(byte[]... packets) {
        int size = 0;
        for (byte[] packet : packets) size += packet.length;
        byte[] stream = new byte[size];
        int offset = 0;
        for (byte[] packet : packets) {
            System.arraycopy(packet, 0, stream, offset, packet.length);
            offset += packet.length;
        }
        return stream;
    }

    private static byte[] hex(String value) { return HexFormat.of().parseHex(value); }
}
