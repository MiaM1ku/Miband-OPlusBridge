// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BandDndCommandTest {
    @Test public void enabledAndDisabledUseTheWatchStatusCodes() {
        var on = BandDndCommand.state(true);
        var off = BandDndCommand.state(false);
        assertEquals(2, on.getType());
        assertEquals(23, on.getSubtype());
        assertEquals(0, on.getSystem().getDndStatus().getStatus());
        assertEquals(2, off.getSystem().getDndStatus().getStatus());
    }

    @Test public void syncWithPhoneEnablesTheSwitchAndDoesNotSetStatus() {
        var command = BandDndCommand.syncWithPhone();
        assertEquals(2, command.getType());
        assertEquals(15, command.getSubtype());
        assertEquals(1, command.getSystem().getMiscSettingSet().getDndSync().getEnabled());
        assertFalse(command.getSystem().hasDndStatus());
    }

    @Test public void phoneRulesCarryTheManualZenRule() {
        var on = BandDndCommand.phoneRules(true, 1_700_000_000);
        var off = BandDndCommand.phoneRules(false, 1_700_000_100);
        assertEquals(2, on.getType());
        assertEquals(110, on.getSubtype());
        assertEquals(1, on.getSystem().getPhoneZenRules().getRuleCount());
        var rule = on.getSystem().getPhoneZenRules().getRule(0);
        assertTrue(rule.getManual());
        assertEquals("manual_zen_rule", rule.getName());
        assertEquals(1, rule.getState());
        assertEquals(0, rule.getConditionOverride());
        assertEquals(1_700_000_000, rule.getLastActivation());
        assertFalse(rule.hasSchedule());
        assertEquals(0, off.getSystem().getPhoneZenRules().getRule(0).getState());
    }

    @Test public void manualStateReadsOnlyTheWatchManualRule() {
        assertEquals(Boolean.TRUE, BandDndCommand.manualState(BandDndCommand.phoneRules(true, 1)));
        assertEquals(Boolean.FALSE, BandDndCommand.manualState(BandDndCommand.phoneRules(false, 1)));
        assertNull(BandDndCommand.manualState(BandDndCommand.state(true)));
        assertNull(BandDndCommand.manualState(null));
    }

    @Test public void mirrorAlwaysSendsTheSwitchThenTheLiveStatus() {
        int before = (int) System.currentTimeMillis();
        for (int filter : new int[] {PhoneDnd.NONE, PhoneDnd.ALL, PhoneDnd.UNKNOWN}) {
            var packets = BandDndCommand.mirror(filter);
            assertEquals(4, packets.size());
            var sync = packets.get(0);
            var state = packets.get(1);
            var rules = packets.get(2);
            var silent = packets.get(3);
            assertEquals(15, sync.getSubtype());
            assertEquals(1, sync.getSystem().getMiscSettingSet().getDndSync().getEnabled());
            assertEquals(23, state.getSubtype());
            boolean on = filter == PhoneDnd.NONE;
            assertEquals(on ? 0 : 2, state.getSystem().getDndStatus().getStatus());
            assertEquals(110, rules.getSubtype());
            var rule = rules.getSystem().getPhoneZenRules().getRule(0);
            assertEquals("manual_zen_rule", rule.getName());
            assertEquals(on ? 1 : 0, rule.getState());
            assertTrue(rule.getManual());
            assertTrue(rule.getLastActivation() - before >= 0);
            assertTrue(rule.getLastActivation() - before < 5_000);
            assertEquals(1, rules.getSystem().getPhoneZenRules().getRuleCount());
            assertEquals(44, silent.getSubtype());
            assertEquals(on, silent.getSystem().getPhoneSilentModeSet().getPhoneSilentMode().getSilent());
        }
    }
 }
