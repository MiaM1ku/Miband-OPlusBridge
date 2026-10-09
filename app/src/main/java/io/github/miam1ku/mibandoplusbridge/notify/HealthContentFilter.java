// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

/** Content rules Health applies in {@code BaseInterceptWorker.filterAdaptation}. */
public final class HealthContentFilter {
    private static final int ONGOING_OR_NO_CLEAR = 0x22;
    private static final int GROUP_SUMMARY = 0x200;
    private static final int FOREGROUND_SERVICE = 0x40;
    private static final String MEDIA = "android.app.Notification$MediaStyle";

    private HealthContentFilter() {}

    /** @return null when Health would keep the notification, otherwise {@code content-*} */
    public static String blockReason(boolean extrasMissing, String template, int flags, String group,
            String category, int progressMax, String tag, String key, String title, String text,
            String channelId, String packageName, boolean mms) {
        if (extrasMissing) return "content-empty";
        if (MEDIA.equals(template)) return "content-media";
        if ((flags & ONGOING_OR_NO_CLEAR) != 0) return "content-ongoing";
        if ((flags & FOREGROUND_SERVICE) != 0) return "content-foreground";
        if ("progress".equals(category) || progressMax > 0) return "content-progress";
        if (contains(tag, "ranker_group") || contains(key, "ranker_group")) return "content-ranker";
        if (group != null && (flags & GROUP_SUMMARY) != 0) return "content-summary";
        if (blank(title) && blank(text)) return "content-blank";
        if (mms && blank(text)) return "content-mms";
        if (filteredChannel(packageName, channelId)) return "content-channel";
        return null;
    }

    private static boolean filteredChannel(String packageName, String channelId) {
        if (channelId == null || channelId.isBlank()) return false;
        if ("com.tencent.mm".equals(packageName) && "reminder_channel_id".equals(channelId)) return true;
        if (!"com.teamtalk.im".equals(packageName)) return false;
        return channelId.startsWith("yzj_notification_channel_update_app")
                || channelId.startsWith("yzj_notification_channel_others")
                || channelId.startsWith("com.oppo.im_client");
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.contains(needle);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
