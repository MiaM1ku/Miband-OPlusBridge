// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/** In-memory, ACK-aware notification lifecycle. No offline replay or persisted content. */
public final class NotificationRelay {
    public interface Sender { CompletionStage<Void> send(XiaomiProto.Command command); }
    public record Event(String packageName, String appName, String key, String title, String body,
                        String publicTitle, String publicBody, long postedAtMs, int visibility,
                        boolean locked, boolean foreground, boolean summary, int importance) {}
    public static final int CAPACITY = 256;
    private final Sender sender;
    private final IntSupplier payloadLimit;
    private final Executor callbacks;
    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String, Long> baseline = new HashMap<>();
    private Set<String> packages = Set.of();
    private boolean enabled, showBody = true, connected;
    private int nextId = 1;
    private String failure;

    private static final class Entry {
        final String key, packageName;
        final int id;
        XiaomiProto.Command desired, delivered;
        boolean busy, removed;
        Entry(String key, String packageName, int id) {
            this.key = key; this.packageName = packageName; this.id = id;
        }
    }

    public NotificationRelay(Sender sender, IntSupplier payloadLimit, Executor callbacks) {
        this.sender = sender; this.payloadLimit = payloadLimit; this.callbacks = callbacks;
    }

    public synchronized void configure(boolean enabled, Set<String> packages, boolean showBody) {
        Set<String> copy = Set.copyOf(packages);
        if (this.enabled != enabled || !this.packages.equals(copy) || this.showBody != showBody) {
            entries.clear(); baseline.clear();
        }
        this.enabled = enabled; this.packages = copy; this.showBody = showBody;
    }

    public synchronized void connected(Map<String, Long> active) {
        entries.clear(); baseline.clear(); failure = null; connected = true;
        if (active.size() > CAPACITY) {
            connected = false; failure = "NOTIFICATION_BASELINE_CAPACITY"; return;
        }
        for (var item : active.entrySet()) {
            if (baseline.size() == CAPACITY) break;
            baseline.put(item.getKey(), item.getValue());
        }
    }

    public synchronized void disconnected() {
        connected = false; entries.clear(); baseline.clear();
    }

    public synchronized void cancelPending() {
        for (var iterator = entries.values().iterator(); iterator.hasNext();) {
            Entry entry = iterator.next();
            entry.desired = null;
            if (!entry.busy && entry.delivered == null) iterator.remove();
        }
    }

    public synchronized String lastFailureCode() { return failure; }

    public synchronized void posted(Event event) {
        if (!connected || !enabled) return;
        if (!packages.contains(event.packageName()) || event.foreground() || event.summary()
                || event.importance() <= 2 || event.visibility() == -1) {
            removed(event.key());
            return;
        }
        Long oldTime = baseline.remove(event.key());
        if (oldTime != null && oldTime == event.postedAtMs()) {
            baseline.put(event.key(), oldTime);
            return;
        }
        String title = event.title();
        String body = showBody ? event.body() : "";
        if (event.locked() && event.visibility() == 0) {
            if (event.publicTitle() != null || event.publicBody() != null) {
                title = event.publicTitle(); body = showBody ? event.publicBody() : "";
            } else body = "";
        }
        title = sanitize(title); body = sanitize(body);
        if (title.isBlank() && body.isBlank()) { removed(event.key()); return; }
        Entry entry = entries.get(event.key());
        if (entry == null) {
            if (entries.size() >= CAPACITY) { failure = "NOTIFICATION_CAPACITY"; return; }
            // Do not reuse an ID during this process lifetime.
            if (nextId == Integer.MAX_VALUE) { failure = "NOTIFICATION_ID_EXHAUSTED"; return; }
            entry = new Entry(event.key(), event.packageName(), nextId++);
        }
        XiaomiProto.Command command;
        try {
            command = BandNotificationCommand.fitToPayload(BandNotificationCommand.post(
                    event.packageName(), event.appName(), event.key(), entry.id, title, body,
                    Instant.ofEpochMilli(event.postedAtMs()), ZoneId.systemDefault()), payloadLimit.getAsInt());
        } catch (IllegalArgumentException rejected) {
            failure = "NOTIFICATION_PAYLOAD_REJECTED"; return;
        }
        if (sameContent(command, entry.desired) || (!entry.busy && sameContent(command, entry.delivered))) return;
        entries.put(event.key(), entry);
        entry.desired = command; entry.removed = false;
        pump(entry);
    }

    public synchronized void removed(String key) {
        baseline.remove(key);
        Entry entry = entries.get(key);
        if (entry == null) return;
        entry.desired = null; entry.removed = true;
        pump(entry);
    }

    private void pump(Entry entry) {
        if (!connected || !enabled || entry.busy || entries.get(entry.key) != entry) return;
        boolean clear = entry.removed;
        if (clear && entry.delivered == null) { entries.remove(entry.key); return; }
        if (!clear && (entry.desired == null || sameContent(entry.desired, entry.delivered))) return;
        XiaomiProto.Command command = clear
                ? BandNotificationCommand.dismiss(entry.packageName, entry.key, entry.id) : entry.desired;
        entry.busy = true;
        try {
            sender.send(command).whenCompleteAsync((ignored, error) -> completed(entry, command, clear, error), callbacks);
        } catch (RuntimeException error) { completed(entry, command, clear, error); }
    }

    private synchronized void completed(Entry entry, XiaomiProto.Command command, boolean clear, Throwable error) {
        if (entries.get(entry.key) != entry) return; // Old session or revoked settings.
        entry.busy = false;
        if (error != null) {
            failure = "NOTIFICATION_TRANSPORT_FAILED";
            if (clear) { entries.remove(entry.key); return; }
            if (entry.desired == command) entry.desired = null;
        } else if (clear) {
            entry.delivered = null;
            if (entry.removed) { entries.remove(entry.key); return; }
        } else entry.delivered = command;
        pump(entry);
        if (!entry.busy && entry.desired == null && entry.delivered == null) entries.remove(entry.key);
    }

    private static boolean sameContent(XiaomiProto.Command a, XiaomiProto.Command b) {
        if (a == null || b == null) return false;
        var x = a.getNotification().getNotification2().getNotification3();
        var y = b.getNotification().getNotification2().getNotification3();
        return x.getPackage().equals(y.getPackage()) && x.getAppName().equals(y.getAppName())
                && x.getTitle().equals(y.getTitle()) && x.getBody().equals(y.getBody());
    }

    /** Bounded plain text, excluding control, bidi-format and malformed surrogate code points. */
    public static String sanitize(CharSequence value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(Math.min(value.length(), 2048));
        int count = 0;
        for (int offset = 0; offset < value.length() && count < 2048;) {
            int cp = Character.codePointAt(value, offset);
            offset += Character.charCount(cp);
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT
                    || cp >= 0xd800 && cp <= 0xdfff) continue;
            result.appendCodePoint(cp); count++;
        }
        return result.toString().strip();
    }
}
