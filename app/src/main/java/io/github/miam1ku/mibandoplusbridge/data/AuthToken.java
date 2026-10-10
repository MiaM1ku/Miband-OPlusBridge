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

    /** Hex, spaced hex, or a 16-byte Base64 key. Empty when none of those match. */
    public static String normalize(String value) {
        if (hex32(value)) return value;
        if (value == null) return "";
        String compact = value.trim().replace(" ", "").replace(":", "");
        if (compact.regionMatches(true, 0, "0x", 0, 2)) compact = compact.substring(2);
        if (hex32(compact)) return compact.toLowerCase(java.util.Locale.ROOT);
        String decoded = fromKeyBytes(decodeBase64(value.trim()));
        return hex32(decoded) ? decoded : "";
    }

    public static String firstKey(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String key = normalize(value);
            if (hex32(key)) return key;
        }
        return "";
    }

    private static byte[] decodeBase64(String value) {
        for (java.util.Base64.Decoder decoder : new java.util.Base64.Decoder[]{
                java.util.Base64.getDecoder(), java.util.Base64.getUrlDecoder()}) {
            try {
                return decoder.decode(value);
            } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    public static String fromKeyBytes(byte[] key) {
        if (key == null || key.length != 16) return "";
        StringBuilder hex = new StringBuilder(32);
        for (byte b : key) hex.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return hex.toString();
    }
}
