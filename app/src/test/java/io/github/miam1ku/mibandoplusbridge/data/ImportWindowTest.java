// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public final class ImportWindowTest {
    private static final String SELECTED = "AA:BB:CC:DD:EE:FF";

    @Test public void onlySelectedDeviceCanUseTheCurrentWindow() {
        ImportWindow window = new ImportWindow();
        window.open(SELECTED, 1000);
        String nonce = window.nonce();
        assertThrows(SecurityException.class, () -> window.authorize(nonce, "11:22:33:44:55:66", 1001));
        assertThrows(SecurityException.class, () -> window.authorize("wrong", SELECTED, 1001));
        window.authorize(nonce, SELECTED, 1001);
        window.close();
        assertThrows(SecurityException.class, () -> window.authorize(nonce, SELECTED, 1002));
        window.open(SELECTED, 1003);
        assertThrows(SecurityException.class, () -> window.authorize(nonce, SELECTED, 1004));
    }

    @Test public void exactDeadlineAndProcessRestartRejectImport() {
        ImportWindow window = new ImportWindow();
        window.open(SELECTED, 500);
        String nonce = window.nonce();
        window.authorize(nonce, SELECTED, 120499);
        assertThrows(SecurityException.class, () -> window.authorize(nonce, SELECTED, 120500));
        assertFalse(window.isOpen(120500));
        assertThrows(SecurityException.class, () -> new ImportWindow().authorize(nonce, SELECTED, 600));
    }
}
