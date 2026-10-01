/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo
 *
 *  This file is adapted from Gadgetbridge XiaomiCharacteristicV1.
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** BLE V1 characteristic chunking used by Band 8 and other fe95 protobuf devices. */
public final class BleV1Codec {
    public static final int TYPE_CHUNK_START = 0;
    public static final int TYPE_CHUNK_ACK = 1;
    public static final int TYPE_SINGLE = 2;
    public static final int TYPE_PAYLOAD_ACK = 3;
    public static final byte[] PAYLOAD_ACK = {0, 0, 3, 0};
    public static final byte[] CHUNK_START_ACK = {0, 0, 1, 1};
    public static final byte[] CHUNK_END_ACK = {0, 0, 1, 0};
    public static final int DEFAULT_MTU_PAYLOAD = 244;

    private BleV1Codec() {}

    public static List<byte[]> encodeOutgoing(byte[] payload, int maxWrite, boolean encrypted) {
        return encodeOutgoing(payload, maxWrite, encrypted, encrypted ? 1 : 0);
    }

    /**
     * Band 8 fe95 plaintext is a single frame only when it fits: chunk 0, type 2, encryption
     * flag 2, then the protobuf. Flag 2 is what an encrypted characteristic expects before
     * auth; omitting it makes the band drop the nonce. Encrypted single frames carry the
     * counter in the header. A chunked frame prepends that counter to the ciphertext.
     */
    public static List<byte[]> encodeOutgoing(byte[] payload, int maxWrite, boolean encrypted, int counter) {
        if (payload == null) throw new IllegalArgumentException("BLE payload required");
        if (maxWrite < 8) throw new IllegalArgumentException("BLE write size too small");
        if (encrypted && (counter < 1 || counter > 0xffff)) {
            throw new IllegalArgumentException("BLE V1 counter out of range");
        }
        int chunkPayload = maxWrite - 2;
        List<byte[]> frames = new ArrayList<>();
        if (encrypted && 6 + payload.length <= maxWrite) {
            byte[] single = new byte[6 + payload.length];
            single[2] = TYPE_SINGLE;
            single[3] = 1;
            single[4] = (byte) counter;
            single[5] = (byte) (counter >> 8);
            System.arraycopy(payload, 0, single, 6, payload.length);
            frames.add(single);
            return frames;
        }
        byte[] body = payload;
        if (encrypted) {
            body = new byte[2 + payload.length];
            body[0] = (byte) counter;
            body[1] = (byte) (counter >> 8);
            System.arraycopy(payload, 0, body, 2, payload.length);
        }
        if (!encrypted && 4 + body.length <= maxWrite) {
            byte[] single = new byte[4 + body.length];
            single[2] = TYPE_SINGLE;
            single[3] = 2;
            System.arraycopy(body, 0, single, 4, body.length);
            frames.add(single);
            return frames;
        }
        int chunks = (body.length + chunkPayload - 1) / chunkPayload;
        byte[] start = new byte[6];
        start[2] = TYPE_CHUNK_START;
        start[3] = (byte) (encrypted ? 1 : 0);
        start[4] = (byte) chunks;
        start[5] = (byte) (chunks >> 8);
        frames.add(start);
        for (int index = 0; index < chunks; index++) {
            int from = index * chunkPayload;
            int to = Math.min(from + chunkPayload, body.length);
            byte[] chunk = new byte[2 + (to - from)];
            int id = index + 1;
            chunk[0] = (byte) id;
            chunk[1] = (byte) (id >> 8);
            System.arraycopy(body, from, chunk, 2, to - from);
            frames.add(chunk);
        }
        return frames;
    }

    public static boolean control(byte[] value, int type) {
        return value != null && value.length >= 3 && value[0] == 0 && value[1] == 0
                && (value[2] & 0xff) == type;
    }

    public static boolean chunkAck(byte[] value, int subtype) {
        return value != null && value.length >= 4 && control(value, TYPE_CHUNK_ACK)
                && (value[3] & 0xff) == subtype;
    }

    public static final class Reassembler {
        private int expectedChunks;
        private final Map<Integer, byte[]> chunks = new HashMap<>();

        public byte[] accept(byte[] value) {
            if (value == null || value.length < 3) throw new IllegalArgumentException("Invalid BLE V1 frame");
            int chunk = (value[0] & 0xff) | ((value[1] & 0xff) << 8);
            if (chunk != 0) {
                if (expectedChunks <= 0 || chunk > expectedChunks) {
                    throw new IllegalArgumentException("Unexpected BLE V1 chunk");
                }
                chunks.put(chunk, Arrays.copyOfRange(value, 2, value.length));
                if (chunks.size() < expectedChunks) return null;
                byte[] payload = join();
                chunks.clear();
                expectedChunks = 0;
                return payload;
            }
            int type = value[2] & 0xff;
            if (type == TYPE_SINGLE) {
                if (value.length < 4) throw new IllegalArgumentException("Invalid BLE V1 single");
                int flag = value[3] & 0xff;
                // Flag 1 stays so decrypt can tell ciphertext from plaintext. Flag 2 is the
                // Band 8 plaintext marker and is not part of the protobuf.
                if (flag == 1) return Arrays.copyOfRange(value, 3, value.length);
                return Arrays.copyOfRange(value, 4, value.length);
            }
            if (type == TYPE_CHUNK_START) {
                if (value.length < 6) throw new IllegalArgumentException("Invalid BLE V1 start");
                expectedChunks = (value[4] & 0xff) | ((value[5] & 0xff) << 8);
                chunks.clear();
                return null;
            }
            if (type == TYPE_CHUNK_ACK || type == TYPE_PAYLOAD_ACK) return null;
            throw new IllegalArgumentException("Unsupported BLE V1 type");
        }

        public boolean awaitingAck(byte[] value) {
            return value != null && value.length >= 3 && value[0] == 0 && value[1] == 0
                    && ((value[2] & 0xff) == TYPE_CHUNK_ACK || (value[2] & 0xff) == TYPE_PAYLOAD_ACK);
        }

        private byte[] join() {
            int size = 0;
            for (int i = 1; i <= expectedChunks; i++) {
                byte[] part = chunks.get(i);
                if (part == null) throw new IllegalArgumentException("Missing BLE V1 chunk");
                size += part.length;
            }
            byte[] payload = new byte[size];
            int offset = 0;
            for (int i = 1; i <= expectedChunks; i++) {
                byte[] part = chunks.get(i);
                System.arraycopy(part, 0, payload, offset, part.length);
                offset += part.length;
            }
            return payload;
        }
    }
}
