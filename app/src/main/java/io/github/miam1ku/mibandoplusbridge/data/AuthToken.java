// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

public final class AuthToken {
    private AuthToken() {}

    public static boolean hex32(String value) {
        return value != null && value.matches("[0-9A-Fa-f]{32}");
    }

    public static String firstHex32(String... values) {
        if (values == null) return "";
        for (String value : values) if (hex32(value)) return value;
        return "";
    }

    public static String fromKeyBytes(byte[] key) {
        if (key == null || key.length != 16) return "";
        StringBuilder hex = new StringBuilder(32);
        for (byte b : key) hex.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return hex.toString();
    }
}
