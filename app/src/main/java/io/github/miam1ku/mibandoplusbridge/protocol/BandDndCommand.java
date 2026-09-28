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

    /** Subtype 110. The band's own manual rule is named watch_manual. state 1 is on, 0 is off.
     * activatedAtSeconds is unix time, matching the rule the band returns. */
    public static XiaomiProto.Command phoneRules(boolean enabled, int activatedAtSeconds) {
        return XiaomiProto.Command.newBuilder().setType(2).setSubtype(110)
                .setSystem(XiaomiProto.System.newBuilder().setPhoneZenRules(
                        XiaomiProto.PhoneZenRuleList.newBuilder().addRule(
                                XiaomiProto.PhoneZenRule.newBuilder()
                                        .setManual(true)
                                        .setName("watch_manual")
                                        .setState(enabled ? 1 : 0)
                                        .setConditionOverride(0)
                                        .setLastActivation(activatedAtSeconds))))
                .build();
    }

    /** Subtype 109. Asks the band for the zen rules it currently stores. */
    public static XiaomiProto.Command queryRules() {
        return XiaomiProto.Command.newBuilder().setType(2).setSubtype(109).build();
    }

    /** Null when this packet is not the band's manual rule. */
    public static Boolean manualState(XiaomiProto.Command command) {
        if (command == null || !command.hasSystem() || !command.getSystem().hasPhoneZenRules()) return null;
        for (var rule : command.getSystem().getPhoneZenRules().getRuleList()) {
            if (rule.getManual() && "watch_manual".equals(rule.getName())) return rule.getState() == 1;
        }
        return null;
    }

    /** Sync switch, the old quiet flag, then the phone rules. */
    public static List<XiaomiProto.Command> mirror(int filter) {
        boolean on = PhoneDnd.blocksNotifications(filter);
        int now = (int) (System.currentTimeMillis() / 1000L);
        return List.of(syncWithPhone(), state(on), phoneRules(on, now));
    }
 }
