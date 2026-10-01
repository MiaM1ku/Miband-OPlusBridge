// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;


import java.util.List;

/** Verified host builds. A different installed version is a hint, not a block. */
public final class HostVersionNotice {
    public record Expectation(String label, String packageName, String verified) {}

    public static final List<Expectation> EXPECTATIONS = List.of(
            new Expectation("OPPO 健康", HostIdentity.HEALTH_PACKAGE, "6.1.18、6.9.37 或 6.9.40"),
            new Expectation("设备空间", HostIdentity.DEVICES_PACKAGE, "17.4.10 或 17.25"),
            new Expectation("小米运动健康", HostIdentity.MI_PACKAGE, "3.59.1"));

    public record Installed(String packageName, String versionName, long versionCode, boolean present) {}

    private HostVersionNotice() {}

    public static boolean accepted(String packageName, String versionName, long versionCode) {
        String name = versionName == null ? "" : versionName;
        return switch (packageName) {
            case HostIdentity.HEALTH_PACKAGE -> versionCode == 6_011_800L || versionCode == 6_093_700L
                    || versionCode == 6_094_000L
                    || name.startsWith("6.1.18") || name.startsWith("6.9.37") || name.startsWith("6.9.40");
            case HostIdentity.DEVICES_PACKAGE -> versionCode == 1_704_010L
                    || name.startsWith("17.4.") || name.startsWith("17.25");
            case HostIdentity.MI_PACKAGE -> versionCode == 359_001L || name.startsWith("3.59.1");
            default -> true;
        };
    }

    /** Empty when every installed host matches a verified build. Missing packages are not nags. */
    public static String fingerprint(List<Installed> installed) {
        StringBuilder text = new StringBuilder();
        for (Installed app : installed) {
            if (!app.present() || accepted(app.packageName(), app.versionName(), app.versionCode())) continue;
            if (text.length() > 0) text.append('\n');
            text.append(app.packageName()).append('=').append(app.versionCode())
                    .append(':').append(app.versionName() == null ? "" : app.versionName());
        }
        return text.toString();
    }

    public static String message(List<Installed> installed) {
        StringBuilder text = new StringBuilder(
                "这些应用和已验证版本不一致，通知、设备卡片或健康同步可能不完整。不强制更换，可以继续使用。\n");
        for (Expectation expect : EXPECTATIONS) {
            Installed app = find(installed, expect.packageName());
            if (app == null || !app.present()
                    || accepted(app.packageName(), app.versionName(), app.versionCode())) continue;
            String current = app.versionName() == null || app.versionName().isBlank()
                    ? Long.toString(app.versionCode()) : app.versionName();
            text.append('\n').append(expect.label()).append(" 当前 ").append(current)
                    .append("，已验证 ").append(expect.verified());
        }
        text.append("\n\n建议升级到已验证版本。");
        return text.toString();
    }

    private static Installed find(List<Installed> installed, String packageName) {
        for (Installed app : installed) if (packageName.equals(app.packageName())) return app;
        return null;
    }

}
