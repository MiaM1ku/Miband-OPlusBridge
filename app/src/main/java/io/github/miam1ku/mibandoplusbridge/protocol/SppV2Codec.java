/*  Copyright (C) 2024 Yoran Vulker

    This file is adapted from Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program. If not, see <http://www.gnu.org/licenses/>. */
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** SPP V2 framing only; payloads remain encrypted where the data opcode requires it. */
public final class SppV2Codec {
    public static final int TYPE_ACK = 1;
    public static final int TYPE_SESSION_CONFIG = 2;
    public static final int TYPE_DATA = 3;
    public static final int HEADER_LENGTH = 8;
    public static final int MAX_PAYLOAD_LENGTH = 0xffff;
    public static final int CHANNEL_PROTOBUF = 1;
    public static final int CHANNEL_ACTIVITY = 5;
    public static final int OPCODE_PLAINTEXT = 1;
    public static final int OPCODE_ENCRYPTED = 2;

    private SppV2Codec() {}

    public static final class Frame {
        private final int type;
        private final int sequence;
        private final byte[] payload;

        public Frame(int type, int sequence, byte[] payload) {
            this(type, sequence, payload, 0, payload.length);
        }

        private Frame(int type, int sequence, byte[] source, int offset, int length) {
            this(type, sequence, length);
            System.arraycopy(source, offset, payload, 0, length);
        }

        private Frame(int type, int sequence, int length) {
            requireType(type);
            requireByte(sequence, "Sequence must be an unsigned byte");
            if (length > MAX_PAYLOAD_LENGTH || (type == TYPE_ACK && length != 0)) {
                throw new IllegalArgumentException("Invalid SPP V2 payload length");
            }
            this.type = type;
            this.sequence = sequence;
            this.payload = new byte[length];
        }

        public int type() { return type; }
        public int sequence() { return sequence; }
        public byte[] payload() { return payload.clone(); }
    }

    public static byte[] encode(Frame frame) {
        byte[] bytes = new byte[HEADER_LENGTH + frame.payload.length];
        bytes[0] = bytes[1] = (byte) 0xa5;
        bytes[2] = (byte) frame.type;
        bytes[3] = (byte) frame.sequence;
        putU16(bytes, 4, frame.payload.length);
        putU16(bytes, 6, crc16Arc(frame.payload, 0, frame.payload.length));
        System.arraycopy(frame.payload, 0, bytes, HEADER_LENGTH, frame.payload.length);
        return bytes;
    }

    /** A malformed frame poisons this stream until reset; it is never skipped or resynchronized. */
    public static final class Decoder {
        private final int maxPayloadLength;
        private byte[] pending = new byte[HEADER_LENGTH];
        private int used;
        private int expected = HEADER_LENGTH;
        private boolean failed;

        public Decoder() { this(MAX_PAYLOAD_LENGTH); }

        public Decoder(int maxPayloadLength) {
            if (maxPayloadLength < 0 || maxPayloadLength > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException("Invalid SPP V2 payload limit");
            }
            this.maxPayloadLength = maxPayloadLength;
        }

        public List<Frame> feed(byte[] chunk, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, chunk.length);
            if (failed) throw new IllegalStateException("SPP V2 decoder requires reset");
            List<Frame> frames = new ArrayList<>();
            int end = offset + length;
            while (offset < end) {
                int count = Math.min(end - offset, expected - used);
                System.arraycopy(chunk, offset, pending, used, count);
                used += count;
                offset += count;
                if (used < expected) continue;
                if (expected == HEADER_LENGTH) {
                    if (pending[0] != (byte) 0xa5 || pending[1] != (byte) 0xa5) {
                        throw reject("Invalid SPP V2 preamble");
                    }
                    int type = pending[2] & 0xff;
                    if (type < TYPE_ACK || type > TYPE_DATA) {
                        throw reject("Unsupported SPP V2 type or header flags");
                    }
                    int payloadLength = u16(pending, 4);
                    if (payloadLength > maxPayloadLength || (type == TYPE_ACK && payloadLength != 0)) {
                        throw reject("Invalid SPP V2 payload length");
                    }
                    expected = HEADER_LENGTH + payloadLength;
                    if (pending.length < expected) pending = Arrays.copyOf(pending, expected);
                    if (used < expected) continue;
                }
                if (u16(pending, 6) != crc16Arc(pending, HEADER_LENGTH, expected - HEADER_LENGTH)) {
                    throw reject("SPP V2 payload checksum mismatch");
                }
                frames.add(new Frame(pending[2] & 0xff, pending[3] & 0xff,
                        pending, HEADER_LENGTH, expected - HEADER_LENGTH));
                Arrays.fill(pending, 0, used, (byte) 0);
                used = 0;
                expected = HEADER_LENGTH;
            }
            return frames;
        }

        public void reset() {
            Arrays.fill(pending, (byte) 0);
            used = 0;
            expected = HEADER_LENGTH;
            failed = false;
        }

        private IllegalArgumentException reject(String reason) {
            Arrays.fill(pending, 0, used, (byte) 0);
            failed = true;
            return new IllegalArgumentException(reason);
        }
    }

    public static final class Data {
        private final int flags;
        private final int channel;
        private final int opcode;
        private final byte[] payload;

        private Data(byte[] bytes) {
            flags = (bytes[0] & 0xff) >>> 4;
            channel = bytes[0] & 0x0f;
            opcode = bytes[1] & 0xff;
            payload = Arrays.copyOfRange(bytes, 2, bytes.length);
        }

        public int flags() { return flags; }
        public int channel() { return channel; }
        public int opcode() { return opcode; }
        public byte[] payload() { return payload.clone(); }
    }

    /** Preserves unknown inner flags, channels and opcodes for the caller to reject explicitly. */
    public static Data parseData(Frame frame) {
        if (frame.type != TYPE_DATA || frame.payload.length < 2) {
            throw new IllegalArgumentException("Invalid SPP V2 data envelope");
        }
        return new Data(frame.payload);
    }

    /** The payload must already be encrypted for opcode 2. */
    public static Frame dataFrame(int sequence, int channel, int opcode, byte[] payload) {
        if (channel < 0 || channel > 0x0f || payload.length > MAX_PAYLOAD_LENGTH - 2) {
            throw new IllegalArgumentException("Invalid SPP V2 data envelope");
        }
        requireByte(opcode, "Opcode must be an unsigned byte");
        Frame frame = new Frame(TYPE_DATA, sequence, 2 + payload.length);
        frame.payload[0] = (byte) channel;
        frame.payload[1] = (byte) opcode;
        System.arraycopy(payload, 0, frame.payload, 2, payload.length);
        return frame;
    }

    public record Parameter(int key, byte[] value) {
        public Parameter { value = value.clone(); }
        @Override public byte[] value() { return value.clone(); }
    }

    /** Missing TLVs remain null; unknown TLVs remain available without assigning them a meaning. */
    public record SessionConfig(int opcode, byte[] version, Integer maxPacketSize,
            Integer txWindow, Integer sendTimeoutMillis, List<Parameter> unknownParameters) {
        public SessionConfig {
            version = version == null ? null : version.clone();
            unknownParameters = List.copyOf(unknownParameters);
        }
        @Override public byte[] version() { return version == null ? null : version.clone(); }
    }

    public static SessionConfig parseSessionConfig(Frame frame) {
        if (frame.type != TYPE_SESSION_CONFIG || frame.payload.length == 0) {
            throw new IllegalArgumentException("Invalid SPP V2 session envelope");
        }
        byte[] payload = frame.payload;
        int opcode = payload[0] & 0xff;
        if (opcode < 1 || opcode > 4 || (opcode >= 3 && payload.length != 1)) {
            throw new IllegalArgumentException("Unsupported SPP V2 session opcode or payload");
        }
        byte[] version = null;
        Integer maxPacketSize = null;
        Integer txWindow = null;
        Integer sendTimeoutMillis = null;
        boolean[] seen = new boolean[256];
        List<Parameter> unknown = new ArrayList<>();
        int offset = 1;
        while (offset < payload.length) {
            if (payload.length - offset < 3) {
                throw new IllegalArgumentException("Truncated SPP V2 session TLV header");
            }
            int key = payload[offset] & 0xff;
            int size = u16(payload, offset + 1);
            offset += 3;
            if (seen[key] || size > payload.length - offset) {
                throw new IllegalArgumentException("Duplicate or truncated SPP V2 session TLV");
            }
            seen[key] = true;
            if ((key == 1 && size != 3) || (key >= 2 && key <= 4 && size != 2)) {
                throw new IllegalArgumentException("Invalid SPP V2 session TLV size");
            }
            switch (key) {
                case 1 -> version = Arrays.copyOfRange(payload, offset, offset + size);
                case 2 -> maxPacketSize = u16(payload, offset);
                case 3 -> txWindow = u16(payload, offset);
                case 4 -> sendTimeoutMillis = u16(payload, offset);
                default -> unknown.add(new Parameter(key, Arrays.copyOfRange(payload, offset, offset + size)));
            }
            offset += size;
        }
        return new SessionConfig(opcode, version, maxPacketSize, txWindow, sendTimeoutMillis, unknown);
    }

    private static int crc16Arc(byte[] bytes, int offset, int length) {
        int crc = 0;
        for (int end = offset + length; offset < end; offset++) {
            crc ^= bytes[offset] & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc >>> 1) ^ ((crc & 1) != 0 ? 0xa001 : 0);
            }
        }
        return crc;
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static void putU16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void requireType(int type) {
        if (type < TYPE_ACK || type > TYPE_DATA) {
            throw new IllegalArgumentException("Unsupported SPP V2 packet type");
        }
    }

    private static void requireByte(int value, String reason) {
        if (value < 0 || value > 0xff) throw new IllegalArgumentException(reason);
    }
}
