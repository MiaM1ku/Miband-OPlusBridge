// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd;
import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;
import java.time.Instant;
import java.time.ZoneId;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BandLiveServiceDndTest {
    private static XiaomiProto.Command ordinary() {
        return BandNotificationCommand.post("pkg", "App", "key", 1, "t", "b", Instant.EPOCH, ZoneId.of("UTC"));
    }

    @Test public void suppressForDndFollowsCommandKind() {
        assertTrue(BandLiveService.suppressForDnd(ordinary(), PhoneDnd.NONE));
        var call = BandNotificationCommand.incomingCall("a", Instant.EPOCH, ZoneId.of("UTC"));
        var dismiss = BandNotificationCommand.dismiss("pkg", "key", 1);
        assertFalse(BandLiveService.suppressForDnd(call, PhoneDnd.NONE));
        assertFalse(BandLiveService.suppressForDnd(dismiss, PhoneDnd.NONE));
        assertFalse(BandLiveService.suppressForDnd(ordinary(), PhoneDnd.ALL));
    }
}
