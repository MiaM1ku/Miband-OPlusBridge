// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import java.util.Locale;
import java.util.Map;

/** Decisions for OHealth notification forwarding that do not touch the host process. */
final class OHealthNotifyFilter {
    private OHealthNotifyFilter() {}

    /** @return null when a removal has no notification this process forwarded */
    static Integer id(Map<String, Integer> forwarded, String key, boolean removed) {
        if (removed) return forwarded.remove(key);
        return forwarded.computeIfAbsent(key, OHealthNotifyFilter::hash);
    }

    static int hash(String key) {
        int hash = key.hashCode() & 0x7fffffff;
        return hash == 0 ? 1 : hash;
    }

    /** First sight of a token wins. The map keeps insertion order and drops the oldest past {@code cap}. */
    static boolean claim(java.util.Map<String, Boolean> seen, String token, int cap) {
        if (seen == null || token == null || token.isBlank() || cap < 1) return false;
        synchronized (seen) {
            if (seen.containsKey(token)) {
                seen.remove(token);
                seen.put(token, Boolean.TRUE);
                return false;
            }
            seen.put(token, Boolean.TRUE);
            java.util.Iterator<String> iterator = seen.keySet().iterator();
            while (seen.size() > cap && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
            return true;
        }
    }

    /** {@code package absent} is a real deny. A database or thread failure is not a closed switch. */
    static String allowlistFailure(Throwable error) {
        Throwable cause = cause(error);
        if (packageAbsent(cause)) return "package absent";
        String name = cause.getClass().getSimpleName();
        String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("main thread")) return "thread";
        if (name.contains("SQLite") || name.contains("SQLException") || message.contains("database")) return "database";
        return "unavailable " + name;
    }

    static boolean blocks(String failure) {
        return "package absent".equals(failure);
    }

    static Throwable cause(Throwable error) {
        Throwable current = error == null ? new IllegalStateException("missing") : error;
        for (int i = 0; i < 4; i++) {
            Throwable next = current.getCause();
            if (next == null || next == current || !wrapper(current)) break;
            current = next;
        }
        return current;
    }

    private static boolean wrapper(Throwable error) {
        if (error instanceof java.lang.reflect.InvocationTargetException) return true;
        if (error instanceof java.lang.reflect.UndeclaredThrowableException) return true;
        return error.getClass().getName().endsWith("InvocationTargetError");
    }

    private static boolean packageAbsent(Throwable cause) {
        if ("NameNotFoundException".equals(cause.getClass().getSimpleName())) return true;
        String message = cause.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("package")
                && (lower.contains("not found") || lower.contains("does not exist") || lower.contains("not exist"));
    }
}
