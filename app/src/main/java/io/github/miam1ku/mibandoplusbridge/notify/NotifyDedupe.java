// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** Collapses the health hook and the local listener posting one notification twice. */
public final class NotifyDedupe {
    public static final long WINDOW_MS = 2_000;
    private final Map<String, Long> seen = new HashMap<>();

    public static String identity(XiaomiProto.Command command) {
        if (command == null || !command.hasNotification()) return "";
        var notification = command.getNotification();
        if (notification.hasNotification2() && notification.getNotification2().hasNotification3()) {
            var item = notification.getNotification2().getNotification3();
            return "post\n" + item.getPackage() + "\n" + item.getKey()
                    + "\n" + item.getTitle() + "\n" + item.getBody();
        }
        if (notification.hasNotificationDismiss() && notification.getNotificationDismiss().getNotificationIdCount() > 0) {
            var id = notification.getNotificationDismiss().getNotificationId(0);
            return "dismiss\n" + id.getPackage() + "\n" + id.getKey() + "\n" + id.getId();
        }
        return "";
    }

    /** A different title or body is a new notification, even on the same key. */
    public synchronized boolean first(String identity, long now) {
        if (identity == null || identity.isBlank()) return true;
        Iterator<Map.Entry<String, Long>> entries = seen.entrySet().iterator();
        while (entries.hasNext()) {
            if (now - entries.next().getValue() > WINDOW_MS) entries.remove();
        }
        Long previous = seen.get(identity);
        if (previous != null && now - previous <= WINDOW_MS) return false;
        seen.put(identity, now);
        return true;
    }
}
