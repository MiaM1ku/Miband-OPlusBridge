// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SessionLogTest {
    @Test public void onlyTheNewestThreeModuleStartsRemain() {
        String log = """
                old preamble
                --- module start 1 ---
                one
                --- module start 2 ---
                two
                --- module start 3 ---
                three
                --- module start 4 ---
                four
                """;
        String kept = SessionLog.keepLastStarts(log, SessionLog.KEPT_STARTS);
        assertFalse(kept.contains("module start 1 "));
        assertFalse(kept.contains("\none\n"));
        assertTrue(kept.contains("--- module start 2 ---"));
        assertTrue(kept.contains("two"));
        assertTrue(kept.contains("--- module start 4 ---"));
        assertTrue(kept.contains("four"));
        assertEquals(3, count(kept));
    }

    @Test public void redactStripsAMacBeforeItCanReachTheLog() {
        String clean = SessionLog.redact("read failed AA:bb:cc:dd:ee:ff\nstill");
        assertFalse(clean.toLowerCase().contains("aa:bb"));
        assertFalse(clean.contains("\n"));
        assertEquals("read failed mac still", clean);
        assertEquals("gone mac", SessionLog.redact("gone aa-bb-cc-dd-ee-ff"));
    }

    @Test public void captureBundleKeepsNewestFilesWithinBothLimits() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("captures");
        try {
            java.io.File older = write(dir, "capture-old.jsonl", 40, 1_000L);
            java.io.File newer = write(dir, "capture-new.jsonl", 40, 3_000L);
            java.io.File huge = write(dir, "capture-huge.jsonl", 400, 2_000L);
            java.io.File empty = write(dir, "capture-empty.jsonl", 0, 4_000L);
            // Newest first; a zero-byte capture and one that would overflow the budget are skipped.
            assertEquals(java.util.List.of(newer, older),
                    SessionLog.pickCaptures(new java.io.File[]{older, newer, huge, empty}, 3, 100));
            assertEquals(java.util.List.of(newer),
                    SessionLog.pickCaptures(new java.io.File[]{older, newer, huge, empty}, 3, 50));
            assertEquals(2, SessionLog.pickCaptures(new java.io.File[]{older, newer, huge}, 2, 1_000).size());
            assertTrue(SessionLog.pickCaptures(new java.io.File[]{huge}, 3, 100).isEmpty());
            assertTrue(SessionLog.pickCaptures(null, 3, 100).isEmpty());
        } finally {
            for (java.io.File file : dir.toFile().listFiles()) file.delete();
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    private static java.io.File write(java.nio.file.Path dir, String name, int bytes, long modified)
            throws java.io.IOException {
        java.io.File file = dir.resolve(name).toFile();
        java.nio.file.Files.write(file.toPath(), new byte[bytes]);
        assertTrue(file.setLastModified(modified));
        return file;
    }

    @Test public void deviceLogEntryKeepsOnlyAttachableFiles() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("device-log").toFile();
        try {
            assertNull(SessionLog.deviceLogEntry(null));
            assertNull(SessionLog.deviceLogEntry(new java.io.File(dir, "missing.zip")));
            java.io.File empty = new java.io.File(dir, "empty.zip");
            assertTrue(empty.createNewFile());
            assertNull(SessionLog.deviceLogEntry(empty));
            java.io.File log = new java.io.File(dir, "log 2026-11.tar.gz");
            java.nio.file.Files.write(log.toPath(), new byte[] {1, 2, 3});
            assertEquals("device-log/log_2026-11.tar.gz", SessionLog.deviceLogEntry(log));
        } finally {
            for (java.io.File file : dir.listFiles()) file.delete();
            dir.delete();
        }
    }

    @Test public void readmeNamesTheDeviceLogOrWhyItIsEmpty() {
        String attached = SessionLog.readme(false, "device-log/a.zip", "OK");
        assertTrue(attached.contains("device-log/a.zip"));
        String skipped = SessionLog.readme(false, null, "LIVE_SESSION_REQUIRED");
        assertTrue(skipped.contains("LIVE_SESSION_REQUIRED"));
        assertTrue(SessionLog.readme(true, null, null).contains("SKIPPED"));
    }

    @Test public void fewerThanThreeStartsAreLeftIntact() {
        String log = "--- module start 1 ---\nonly\n";
        assertEquals(log, SessionLog.keepLastStarts(log, 3));
    }

    private static int count(String text) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(SessionLog.START_MARK, from)) >= 0) {
            count++;
            from += SessionLog.START_MARK.length();
        }
        return count;
    }
}
