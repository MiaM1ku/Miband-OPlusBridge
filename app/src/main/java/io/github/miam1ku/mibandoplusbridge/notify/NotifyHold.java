// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Notifications that arrived before the band session was up. Not a replay of the shade. */
public final class NotifyHold {
    public static final int CAPACITY = 32;
    private final LinkedHashMap<String, NotificationRelay.Event> pending = new LinkedHashMap<>();

    public synchronized void put(NotificationRelay.Event event) {
        if (event == null || event.key() == null || event.key().isBlank()) return;
        pending.remove(event.key());
        while (pending.size() >= CAPACITY) {
            var oldest = pending.keySet().iterator();
            if (!oldest.hasNext()) break;
            oldest.next();
            oldest.remove();
        }
        pending.put(event.key(), event);
    }

    public synchronized void remove(String key) { pending.remove(key); }

    public synchronized boolean contains(String key) { return key != null && pending.containsKey(key); }

    public synchronized void clear() { pending.clear(); }

    public synchronized List<NotificationRelay.Event> drain() {
        List<NotificationRelay.Event> copy = new ArrayList<>(pending.values());
        pending.clear();
        return copy;
    }
}
