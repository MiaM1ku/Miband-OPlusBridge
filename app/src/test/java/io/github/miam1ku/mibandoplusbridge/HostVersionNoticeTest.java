// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HostVersionNoticeTest {
    @Test public void verifiedBuildsStayQuiet() {
        var installed = List.of(
                app(HostIdentity.HEALTH_PACKAGE, "6.9.37", 6_093_700L),
                app(HostIdentity.DEVICES_PACKAGE, "17.25.0", 1_725_000L),
                app(HostIdentity.MI_PACKAGE, "3.59.1", 359_001L));
        assertEquals("", HostVersionNotice.fingerprint(installed));
        assertTrue(HostVersionNotice.accepted(HostIdentity.HEALTH_PACKAGE, "6.1.18_9fe4116_260429", 6_011_800L));
        assertTrue(HostVersionNotice.accepted(HostIdentity.DEVICES_PACKAGE, "17.4.10", 1_704_010L));
    }

    @Test public void aDifferentInstalledBuildIsNamedOnce() {
        var installed = List.of(
                app(HostIdentity.HEALTH_PACKAGE, "7.0.0", 7_000_000L),
                new HostVersionNotice.Installed(HostIdentity.DEVICES_PACKAGE, "", 0, false),
                app(HostIdentity.MI_PACKAGE, "3.40.0", 340_000L));
        String finger = HostVersionNotice.fingerprint(installed);
        assertEquals(finger, HostVersionNotice.fingerprint(installed));
        assertTrue(finger.contains(HostIdentity.HEALTH_PACKAGE));
        assertTrue(finger.contains(HostIdentity.MI_PACKAGE));
        assertFalse(finger.contains(HostIdentity.DEVICES_PACKAGE));
        String message = HostVersionNotice.message(installed);
        assertTrue(message.contains("OPPO 健康 当前 7.0.0"));
        assertTrue(message.contains("小米运动健康 当前 3.40.0"));
        assertFalse(message.contains("设备空间 当前"));
    }

    private static HostVersionNotice.Installed app(String packageName, String versionName, long versionCode) {
        return new HostVersionNotice.Installed(packageName, versionName, versionCode, true);
    }
}
