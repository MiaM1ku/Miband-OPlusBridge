// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class DeviceLogPublisherTest {
    @Test public void absoluteHandsetLogPathsAreMadeRelativeWithoutEscapingDownloads() {
        assertEquals("data/log/tmp.log", DeviceLogPublisher.safeEntryName("/data/log/tmp.log"));
        assertEquals("data/log/log1.gz", DeviceLogPublisher.safeEntryName("\\data\\log\\log1.gz"));
        assertNull(DeviceLogPublisher.safeEntryName("/data/log/../../secret"));
        assertNull(DeviceLogPublisher.safeEntryName("../../secret"));
    }
}
