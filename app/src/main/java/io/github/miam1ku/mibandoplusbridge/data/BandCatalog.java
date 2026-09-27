// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Display names for Xiaomi Wear band models the official app speaks.
 * Not an allow-list: live sessions still depend on the captured SPP profile.
 */
public final class BandCatalog {
    private static final Pattern CHINESE = Pattern.compile(
            "小米手环\\s*(8|9|10|11)\\s*(Pro|NFC|活力版)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADVERTISED = Pattern.compile(
            "(?i)(?:xiaomi\\s+)?(?:smart\\s+)?band\\s+(8|9|10|11)(?:\\s+(pro|active|nfc))?");
    private static final Pattern REDMI = Pattern.compile(
            "(?i)redmi\\s+(?:smart\\s+)?band\\s*(pro|2|3)?");

    private BandCatalog() {}

    public static boolean looksLikeBand(String advertisedName) {
        return fromAdvertisedName(advertisedName) != null;
    }

    public static String displayName(String model, String rawName) {
        String fromModel = fromModelId(model);
        if (fromModel != null) return fromModel;
        String fromAdvertised = fromAdvertisedName(rawName);
        if (fromAdvertised != null) return fromAdvertised;
        String clean = sanitize(rawName);
        if (clean != null) return clean;
        String leftover = sanitize(model);
        return leftover != null ? leftover : "小米手环";
    }

    static String fromModelId(String model) {
        if (model == null || model.isBlank()) return null;
        String id = model.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("miwear.watch.q66")) return nfc(id, "小米手环11");
        if (id.startsWith("miwear.watch.p67")) return "小米手环10 Pro";
        if (id.startsWith("miwear.watch.o66")) return nfc(id, "小米手环10");
        if (id.startsWith("miwear.watch.n67") || id.startsWith("lchz.watch.n67")) return "小米手环9 Pro";
        if (id.startsWith("miwear.watch.n66")) return nfc(id, "小米手环9");
        if (id.startsWith("miwear.watch.n69") || id.startsWith("mijia.watch.n69")) return "小米手环9 活力版";
        if (id.startsWith("lchz.watch.m67")) return "小米手环8 Pro";
        if (id.startsWith("miwear.watch.m66") || id.startsWith("lchz.watch.m66")) return nfc(id, "小米手环8");
        if (id.startsWith("mijia.watch.m69b")) return "小米手环8 活力版";
        return null;
    }

    static String fromAdvertisedName(String raw) {
        String clean = sanitize(raw);
        if (clean == null) return null;
        Matcher chinese = CHINESE.matcher(clean);
        if (chinese.find()) return labeled(chinese.group(1), chinese.group(2));
        Matcher advertised = ADVERTISED.matcher(clean);
        if (advertised.find()) return labeled(advertised.group(1), advertised.group(2));
        Matcher redmi = REDMI.matcher(clean);
        if (redmi.find()) {
            String variant = redmi.group(1);
            if (variant == null || variant.isBlank()) return "红米手环";
            if (variant.equalsIgnoreCase("pro")) return "红米手环 Pro";
            return "红米手环" + variant;
        }
        return null;
    }

    private static String nfc(String id, String base) {
        if (id.contains("nfc") || id.endsWith("gln")) return base + " NFC";
        return base;
    }

    private static String labeled(String generation, String variant) {
        String base = "小米手环" + generation;
        if (variant == null || variant.isBlank()) return base;
        String kind = variant.trim().toLowerCase(Locale.ROOT);
        if (kind.equals("pro")) return base + " Pro";
        if (kind.equals("nfc")) return base + " NFC";
        if (kind.equals("active") || kind.contains("活力")) return base + " 活力版";
        return base;
    }

    private static String sanitize(String value) {
        if (value == null) return null;
        String clean = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
        if (clean.isEmpty() || clean.length() > 80) return null;
        return clean;
    }
}
