// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BleNotifyQueueTest {
    @Test public void secondSubscribeWaitsForDescriptorCallback() {
        BleNotifyQueue queue = new BleNotifyQueue(2);
        assertEquals(0, queue.start());
        assertEquals(-1, queue.start());
        assertFalse(queue.complete());
        assertTrue(queue.confirmed());
        assertEquals(1, queue.start());
        assertEquals(-1, queue.start());
        assertTrue(queue.confirmed());
        assertTrue(queue.complete());
        assertEquals(-1, queue.start());
    }

    @Test public void singleCharacteristicCompletesAfterItsCallback() {
        BleNotifyQueue queue = new BleNotifyQueue(1);
        assertEquals(0, queue.start());
        assertFalse(queue.complete());
        assertTrue(queue.confirmed());
        assertTrue(queue.complete());
        assertFalse(queue.confirmed());
    }
}
