// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OHealthNotifyFilterTest {
    @Test public void removalOfAnUnforwardedKeySendsNothing() {
        Map<String, Integer> forwarded = new HashMap<>();
        assertNull(OHealthNotifyFilter.id(forwarded, "missing", true));
        assertTrue(forwarded.isEmpty());
    }

    @Test public void removalUsesTheIdFromThePostAndOnlyOnce() {
        Map<String, Integer> forwarded = new HashMap<>();
        int posted = OHealthNotifyFilter.id(forwarded, "qq", false);
        assertEquals(posted, (int) OHealthNotifyFilter.id(forwarded, "qq", false));
        assertEquals(posted, (int) OHealthNotifyFilter.id(forwarded, "qq", true));
        assertNull(OHealthNotifyFilter.id(forwarded, "qq", true));
        assertEquals(OHealthNotifyFilter.hash("qq"), posted);
    }

    @Test public void theSamePostFromTwoHooksIsClaimedOnceAndTheOldestFallsOut() {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        assertTrue(OHealthNotifyFilter.claim(seen, "a@1", 2));
        assertFalse(OHealthNotifyFilter.claim(seen, "a@1", 2));
        assertTrue(OHealthNotifyFilter.claim(seen, "b@2", 2));
        assertTrue(OHealthNotifyFilter.claim(seen, "c@3", 2));
        assertFalse(seen.containsKey("a@1"));
        assertTrue(seen.containsKey("c@3"));
        assertFalse(OHealthNotifyFilter.claim(seen, " ", 2));
    }

    @Test public void roomMainThreadFailureIsNotAClosedSwitch() {
        Throwable wrapped = new java.lang.reflect.InvocationTargetException(new IllegalStateException(
                "Cannot access database on the main thread since it may potentially lock the UI"));
        String failure = OHealthNotifyFilter.allowlistFailure(wrapped);
        assertEquals("thread", failure);
        assertFalse(OHealthNotifyFilter.blocks(failure));
        assertEquals(IllegalStateException.class, OHealthNotifyFilter.cause(wrapped).getClass());
    }

    @Test public void databaseFailureIsNotAClosedSwitch() {
        String failure = OHealthNotifyFilter.allowlistFailure(new java.sql.SQLException("disk I/O"));
        assertEquals("database", failure);
        assertFalse(OHealthNotifyFilter.blocks(failure));
    }

    @Test public void missingPackageStillBlocks() {
        assertEquals("package absent", OHealthNotifyFilter.allowlistFailure(new NameNotFoundException()));
        assertTrue(OHealthNotifyFilter.blocks(OHealthNotifyFilter.allowlistFailure(
                new IllegalArgumentException("package com.example does not exist"))));
        AtomicReference<String> plain = new AtomicReference<>(
                OHealthNotifyFilter.allowlistFailure(new IllegalStateException("closed")));
        assertEquals("unavailable IllegalStateException", plain.get());
        assertFalse(OHealthNotifyFilter.blocks(plain.get()));
    }

    private static final class NameNotFoundException extends Exception {}
}
