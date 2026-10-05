// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd;
import java.util.List;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

 /** Phone DND mirrored onto the band.
  * Subtype 15 keeps sync on, 23 is the old quiet flag, 110 is the phone rule list the band actually applies. */
 public final class BandDndCommand {
     private BandDndCommand() {}

     /** Subtype 15. enabled stays 1 so the band keeps the phone rule instead of deleting it. */
     public static XiaomiProto.Command syncWithPhone() {
         return XiaomiProto.Command.newBuilder().setType(2).setSubtype(15)
                 .setSystem(XiaomiProto.System.newBuilder().setMiscSettingSet(
                         XiaomiProto.MiscSettingSet.newBuilder().setDndSync(
                                 XiaomiProto.DndSync.newBuilder().setEnabled(1))))
                 .build();
     }

     public static XiaomiProto.Command state(boolean enabled) {
         return XiaomiProto.Command.newBuilder().setType(2).setSubtype(23)
                 .setSystem(XiaomiProto.System.newBuilder().setDndStatus(
                         XiaomiProto.DoNotDisturb.newBuilder().setStatus(enabled ? 0 : 2)))
                 .build();
     }

    /** Subtype 110. Mi Fitness names the phone's manual rule manual_zen_rule.
     * watch_manual is the band's own rule and does not drive phone sync.
     * activatedAt is unix seconds. The band stores the same unit on watch_manual;
     * the low 32 bits of epoch millis are about ten times smaller and lose the comparison. */
    public static XiaomiProto.Command phoneRules(boolean enabled, int activatedAt) {
        return XiaomiProto.Command.newBuilder().setType(2).setSubtype(110)
                .setSystem(XiaomiProto.System.newBuilder().setPhoneZenRules(
                        XiaomiProto.PhoneZenRuleList.newBuilder().addRule(
                                XiaomiProto.PhoneZenRule.newBuilder()
                                        .setManual(true)
                                        .setName("manual_zen_rule")
                                        .setState(enabled ? 1 : 0)
                                        .setConditionOverride(0)
                                        .setLastActivation(activatedAt))))
                .build();
    }

    /** Subtype 44. Answers the band's silent-mode query and mirrors phone DND onto that flag. */
    public static XiaomiProto.Command phoneSilent(boolean silent) {
        return XiaomiProto.Command.newBuilder().setType(2).setSubtype(44)
                .setSystem(XiaomiProto.System.newBuilder().setPhoneSilentModeSet(
                        XiaomiProto.PhoneSilentModeSet.newBuilder().setPhoneSilentMode(
                                XiaomiProto.PhoneSilentMode.newBuilder().setSilent(silent))))
                .build();
    }

    /** Subtype 109. Asks the band for the zen rules it currently stores. */
    public static XiaomiProto.Command queryRules() {
        return XiaomiProto.Command.newBuilder().setType(2).setSubtype(109).build();
    }

    /** Null when this packet has neither the phone rule nor the band's own manual rule. */
    public static Boolean manualState(XiaomiProto.Command command) {
        if (command == null || !command.hasSystem() || !command.getSystem().hasPhoneZenRules()) return null;
        for (var rule : command.getSystem().getPhoneZenRules().getRuleList()) {
            if (!rule.getManual()) continue;
            String name = rule.getName();
            if ("manual_zen_rule".equals(name) || "watch_manual".equals(name)) return rule.getState() == 1;
        }
        return null;
    }

    /** Unix seconds. Values past 2038 do not fit this uint32. */
    public static int activatedAt(long epochMillis) {
        long seconds = epochMillis / 1000L;
        if (seconds <= 0 || seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("DND_ACTIVATION_TIME_INVALID");
        }
        return (int) seconds;
    }

    /** Subtype 110 is a band toggle. Subtype 109 is the stored list: watch_manual stays off while the phone rule is on. */
    public static Boolean pushedManualState(XiaomiProto.Command command) {
        if (command == null || command.getSubtype() != 110) return null;
        return manualState(command);
    }

    /** Sync switch, the old quiet flag, then the phone rules. */
    public static List<XiaomiProto.Command> mirror(int filter) {
        boolean on = PhoneDnd.blocksNotifications(filter);
        int now = activatedAt(System.currentTimeMillis());
        return List.of(syncWithPhone(), state(on), phoneRules(on, now), phoneSilent(on));
    }
 }
