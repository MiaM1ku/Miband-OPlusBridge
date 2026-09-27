// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.util.Locale;
import java.util.UUID;

/** All calls are serialized by CredentialProvider. Process restart closes the window. */
public final class ImportWindow {
    private String address;
    private String nonce;
    private long deadline;

    public void open(String selected, long nowMs) {
        String normalized = selected.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) {
            throw new IllegalArgumentException("INVALID_DEVICE_ADDRESS");
        }
        address = normalized;
        nonce = UUID.randomUUID().toString();
        deadline = Math.addExact(nowMs, 120_000);
    }

    public boolean isOpen(long nowMs) {
        if (nonce != null && nowMs >= deadline) close();
        return nonce != null;
    }

    public void authorize(String suppliedNonce, String suppliedAddress, long nowMs) {
        if (!isOpen(nowMs) || !nonce.equals(suppliedNonce)) {
            throw new SecurityException("IMPORT_WINDOW_CLOSED");
        }
        if (!address.equals(suppliedAddress)) {
            throw new SecurityException("DEVICE_SELECTION_MISMATCH");
        }
    }

    public String address() { return address; }
    public String nonce() { return nonce; }

    public void close() {
        address = null;
        nonce = null;
        deadline = 0;
    }
}
