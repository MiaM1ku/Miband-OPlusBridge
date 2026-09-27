/*  Copyright (C) 2023-2024 José Rebelo, Yoran Vulker

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

/** Only the observed version exchange uses the legacy envelope; all subsequent frames use V2. */
public final class SppNegotiation {
    public static final int VERSION_RESPONSE_LENGTH = 14;

    private SppNegotiation() {}

    /** The caller owns the serial counter; a response does not have to echo this value. */
    public static byte[] versionRequest(int opcodeSerial) {
        if (opcodeSerial < 0 || opcodeSerial > 0xff) {
            throw new IllegalArgumentException("Startup opcode serial must be an unsigned byte");
        }
        return new byte[] {
                (byte) 0xba, (byte) 0xdc, (byte) 0xfe, 0, (byte) 0xc0,
                3, 0, 0, (byte) opcodeSerial, 0, (byte) 0xef
        };
    }

    public record VersionResponse(int opcodeSerial, int major, int minor, int patch) {
        public int apiVersion() { return (major << 16) | (minor << 8) | patch; }
        public String versionName() { return major + "." + minor + "." + patch; }
    }

    /** Read exactly VERSION_RESPONSE_LENGTH bytes, retaining later socket bytes for the V2 decoder. */
    public static VersionResponse decodeVersionResponse(byte[] bytes) {
        if (bytes.length != VERSION_RESPONSE_LENGTH
                || bytes[0] != (byte) 0xba || bytes[1] != (byte) 0xdc || bytes[2] != (byte) 0xfe
                || bytes[3] != 0 || bytes[4] != 0 || bytes[5] != 6 || bytes[6] != 0
                || bytes[7] != 1 || bytes[9] != 0 || bytes[13] != (byte) 0xef) {
            throw new IllegalArgumentException("Unsupported SPP startup version envelope");
        }
        return new VersionResponse(bytes[8] & 0xff, bytes[10] & 0xff, bytes[11] & 0xff, bytes[12] & 0xff);
    }

    /** Matches the phone's observed request, including its independent session sequence zero. */
    public static SppV2Codec.Frame startSessionRequest() {
        return new SppV2Codec.Frame(SppV2Codec.TYPE_SESSION_CONFIG, 0, new byte[] {
                1,
                1, 3, 0, 1, 0, 0,
                2, 2, 0, 0, (byte) 0xfc,
                3, 2, 0, 0x20, 0,
                4, 2, 0, 0x10, 0x27
        });
    }
}
