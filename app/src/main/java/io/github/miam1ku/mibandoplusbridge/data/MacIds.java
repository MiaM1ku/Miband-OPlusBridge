// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

/** Identity comparison for the same 48-bit address in colon, dash, or plain form. */
public final class MacIds {
    private MacIds() {}

    public static String normalize(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(12);
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toLowerCase(value.charAt(i));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) out.append(c);
        }
        return out.length() == 12 ? out.toString() : "";
    }

    public static boolean same(String left, String right) {
        String normalized = normalize(left);
        return !normalized.isEmpty() && normalized.equals(normalize(right));
    }
}
