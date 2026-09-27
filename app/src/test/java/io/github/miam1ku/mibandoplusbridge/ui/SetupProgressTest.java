// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.ui;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SetupProgressTest {
    @Test public void missingRootIsFirstEvenWithBinding() {
        SetupProgress progress = new SetupProgress(false, true, true, false, false, false);
        assertEquals(SetupProgress.Step.ROOT, progress.current());
        assertTrue(progress.showChecklist());
        assertEquals("检查 Root", progress.primaryLabel());
    }

    @Test public void missingBindingIsImportEvenWhenUnregistered() {
        SetupProgress progress = new SetupProgress(true, false, false, false, false, false);
        assertEquals(SetupProgress.Step.IMPORT, progress.current());
        assertTrue(progress.showChecklist());
        assertEquals("导入绑定", progress.primaryLabel());
    }

    @Test public void importedWithoutProfileIsCapture() {
        SetupProgress progress = new SetupProgress(true, true, false, false, false, false);
        assertEquals(SetupProgress.Step.PROFILE, progress.current());
        assertTrue(progress.showChecklist());
        assertEquals("采集连接参数", progress.primaryLabel());
    }

    @Test public void registeredWithoutProfileIsCapture() {
        SetupProgress progress = new SetupProgress(true, true, false, true, true, true);
        assertEquals(SetupProgress.Step.PROFILE, progress.current());
        assertTrue(progress.showChecklist());
        assertEquals("采集连接参数", progress.primaryLabel());
    }

    @Test public void importedUnregisteredIsAdd() {
        SetupProgress progress = new SetupProgress(true, true, true, false, false, false);
        assertEquals(SetupProgress.Step.ADD, progress.current());
        assertTrue(progress.showChecklist());
        assertEquals("添加到健康", progress.primaryLabel());
    }

    @Test public void profileWithoutNativeIsAdd() {
        SetupProgress progress = new SetupProgress(true, true, true, true, false, true);
        assertEquals(SetupProgress.Step.ADD, progress.current());
        assertEquals("添加到健康", progress.primaryLabel());
    }

    @Test public void registeredWithoutAccountKeepsChecklist() {
        SetupProgress progress = new SetupProgress(true, true, true, true, true, false);
        assertEquals(SetupProgress.Step.ACCOUNT, progress.current());
        assertTrue(progress.showChecklist());
        assertEquals("确认健康账号", progress.primaryLabel());
    }

    @Test public void confirmedAccountHidesChecklist() {
        SetupProgress progress = new SetupProgress(true, true, true, true, true, true);
        assertEquals(SetupProgress.Step.DONE, progress.current());
        assertFalse(progress.showChecklist());
        assertEquals("立即同步", progress.primaryLabel());
    }

    @Test public void missingBindingOutranksRegister() {
        SetupProgress progress = new SetupProgress(true, false, true, true, true, false);
        assertEquals(SetupProgress.Step.IMPORT, progress.current());
        assertTrue(progress.showChecklist());
        assertEquals("导入绑定", progress.primaryLabel());
    }
}
