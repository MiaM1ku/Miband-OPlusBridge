/*  Copyright (C) 2023-2024 Yoran Vulker
 *
 *  This file is adapted from Gadgetbridge XiaomiSppPacketV1 / XiaomiSppProtocolV1.
 *
 *  Gadgetbridge is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published
 *  by the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** SPP V1 envelope used by Band 8 Pro and other SppTaskQueueV1 devices. */
public final class SppV1Codec {
    public static final byte[] PREAMBLE = {(byte) 0xba, (byte) 0xdc, (byte) 0xfe};
    public static final byte EPILOGUE = (byte) 0xef;
    public static final int CHANNEL_VERSION = 0;
    public static final int CHANNEL_PROTO_RX = 1;
    public static final int CHANNEL_PROTO_TX = 2;
    public static final int CHANNEL_FITNESS = 3;
    public static final int CHANNEL_MASS = 5;
    public static final int DATA_PLAIN = 0;
    public static final int DATA_ENCRYPTED = 1;
    public static final int DATA_AUTH = 2;
    public static final int OPCODE_READ = 0;
    public static final int OPCODE_SEND = 2;
    public static final int HEADER = 11;
    public static final int MAX_PAYLOAD = 0xffff - 3;

    private SppV1Codec() {}

    public record Packet(int channel, boolean flag, boolean needsResponse, int opcode,
                         int serial, int dataType, byte[] payload) {}

    public static byte[] encode(Packet packet) {
        Objects.requireNonNull(packet.payload, "payload");
        if (packet.payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("SPP V1 payload too large");
        byte[] bytes = new byte[HEADER + packet.payload.length];
        bytes[0] = PREAMBLE[0];
        bytes[1] = PREAMBLE[1];
        bytes[2] = PREAMBLE[2];
        bytes[3] = (byte) packet.channel;
        int flags = 0;
        if (packet.flag) flags |= 0x80;
        if (packet.needsResponse) flags |= 0x40;
        bytes[4] = (byte) flags;
        int size = packet.payload.length + 3;
        bytes[5] = (byte) size;
        bytes[6] = (byte) (size >> 8);
        bytes[7] = (byte) packet.opcode;
        bytes[8] = (byte) packet.serial;
        bytes[9] = (byte) packet.dataType;
        System.arraycopy(packet.payload, 0, bytes, 10, packet.payload.length);
        bytes[bytes.length - 1] = EPILOGUE;
        return bytes;
    }

    public static Packet protobuf(int serial, int dataType, byte[] payload) {
        return new Packet(CHANNEL_PROTO_TX, true, false, OPCODE_SEND, serial, dataType, payload);
    }

    public static Packet decode(byte[] bytes) {
        if (bytes.length < HEADER
                || bytes[0] != PREAMBLE[0] || bytes[1] != PREAMBLE[1] || bytes[2] != PREAMBLE[2]
                || bytes[bytes.length - 1] != EPILOGUE) {
            throw new IllegalArgumentException("Invalid SPP V1 envelope");
        }
        int size = (bytes[5] & 0xff) | ((bytes[6] & 0xff) << 8);
        int payloadLength = size - 3;
        if (payloadLength < 0 || HEADER + payloadLength != bytes.length) {
            throw new IllegalArgumentException("Invalid SPP V1 payload length");
        }
        byte[] payload = Arrays.copyOfRange(bytes, 10, 10 + payloadLength);
        int flags = bytes[4] & 0xff;
        return new Packet(bytes[3] & 0x0f, (flags & 0x80) != 0, (flags & 0x40) != 0,
                bytes[7] & 0xff, bytes[8] & 0xff, bytes[9] & 0xff, payload);
    }

    public static final class Decoder {
        private byte[] pending = new byte[HEADER];
        private int used;
        private int expected = HEADER;
        private boolean failed;

        public List<Packet> feed(byte[] chunk, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, chunk.length);
            if (failed) throw new IllegalStateException("SPP V1 decoder requires reset");
            List<Packet> packets = new ArrayList<>();
            int end = offset + length;
            while (offset < end) {
                int count = Math.min(end - offset, expected - used);
                System.arraycopy(chunk, offset, pending, used, count);
                used += count;
                offset += count;
                if (used < expected) continue;
                if (expected == HEADER) {
                    if (pending[0] != PREAMBLE[0] || pending[1] != PREAMBLE[1] || pending[2] != PREAMBLE[2]) {
                        throw reject("Invalid SPP V1 preamble");
                    }
                    int size = (pending[5] & 0xff) | ((pending[6] & 0xff) << 8);
                    int payloadLength = size - 3;
                    if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
                        throw reject("Invalid SPP V1 payload length");
                    }
                    expected = HEADER + payloadLength;
                    if (pending.length < expected) pending = Arrays.copyOf(pending, expected);
                    if (used < expected) continue;
                }
                packets.add(decode(Arrays.copyOf(pending, expected)));
                Arrays.fill(pending, 0, used, (byte) 0);
                used = 0;
                expected = HEADER;
            }
            return packets;
        }

        public void reset() {
            Arrays.fill(pending, (byte) 0);
            used = 0;
            expected = HEADER;
            failed = false;
        }

        private IllegalArgumentException reject(String reason) {
            Arrays.fill(pending, 0, used, (byte) 0);
            failed = true;
            return new IllegalArgumentException(reason);
        }
    }
}
