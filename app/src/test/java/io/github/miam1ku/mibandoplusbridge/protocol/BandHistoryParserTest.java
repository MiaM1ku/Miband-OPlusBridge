// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class BandHistoryParserTest {
    private static final int START_SECONDS = 1_700_000_000;
    private static final String DID = "synthetic-confirmed-did";

    @Test public void stepsAreMinuteDeltasAndSummaryIsAnIndependentDailyTotal() throws Exception {
        BandHistoryParser parser = parser();
        byte[] record = file(4, 0, recordBody(9, 5, 72, 0));
        byte[] packet = fragment(1, 1, record);
        var result = parser.acceptFragment(packet);
        List<BandHistoryParser.Measurement> entries = result.measurements;
        assertEquals(3, entries.size());
        assertEquals("steps_interval", entries.get(0).kind);
        assertEquals(9, entries.get(0).value.intValue());
        assertEquals(5, entries.get(2).value.intValue());
        assertEquals("heart_rate", entries.get(1).kind);
        assertEquals(72, entries.get(1).value.intValue());
        assertEquals(entries.get(0).endMs, entries.get(2).startMs);
        assertEquals("+05:30", entries.get(0).timezone);
        assertNull(entries.get(0).stage);

        var daily = parser.acceptFragment(fragment(1, 1, file(5, 1, reportBody(20))))
                .measurements.get(0);
        assertEquals("steps_day", daily.kind);
        assertEquals(20, daily.value.intValue());
        assertEquals(86_400_000L, daily.endMs - daily.startMs);
        assertNotEquals(entries.get(0).recordId, daily.recordId);
        assertEquals(20, parser.acceptFragment(fragment(1, 1, file(5, 1, reportBody(20))))
                .measurements.get(0).value.intValue());
        var changedHeartRate = parser.acceptFragment(fragment(1, 1,
                file(4, 0, recordBody(9, 5, 75, 0)))).measurements;
        assertEquals(entries.get(0).sourceFingerprint,
                changedHeartRate.get(0).sourceFingerprint);
        assertNotEquals(entries.get(1).sourceFingerprint,
                changedHeartRate.get(1).sourceFingerprint);
    }

    @Test public void versionTwoMinuteRecordsStillExposeHeartRate() throws Exception {
        byte[] body = new byte[8];
        int base = 31;
        for (int bit : new int[] {base, base - 3, base - 16, base - 17}) {
            body[1 + (base - bit) / 8] |= (byte) (1 << (bit % 8));
        }
        put16(body, 5, 6);
        body[7] = 80;
        var records = parser().parseFile(file(2, 0, body)).measurements;
        assertEquals(2, records.size());
        assertEquals("steps_interval", records.get(0).kind);
        assertEquals(6, records.get(0).value.intValue());
        assertEquals("heart_rate", records.get(1).kind);
        assertEquals(80, records.get(1).value.intValue());
    }

    @Test public void todayStepsPreferTheDailyReportAndIgnoreOtherDays() throws Exception {
        long day = 1_000_000L;
        var minutes = List.of(
                step("steps_interval", day - 60_000L, day, 9),
                step("steps_interval", day, day + 60_000L, 4),
                step("steps_interval", day + 60_000L, day + 120_000L, 6));
        var summed = BandHistoryParser.todaySteps(minutes, day, day + 86_400_000L);
        assertEquals(10, summed.steps);
        assertEquals(day + 60_000L, summed.measuredAtMs);
        assertFalse(summed.authoritative);
        var withReport = new java.util.ArrayList<>(minutes);
        withReport.add(step("steps_day", day, day + 86_400_000L, 20));
        var daily = BandHistoryParser.todaySteps(withReport, day, day + 86_400_000L);
        assertEquals(20, daily.steps);
        assertEquals(day, daily.measuredAtMs);
        assertTrue(daily.authoritative);
        assertNull(BandHistoryParser.todaySteps(List.of(step("steps_interval", day - 60_000L, day, 9)),
                day, day + 86_400_000L));
    }

    private static BandHistoryParser.Measurement step(String kind, long start, long end, int value) throws Exception {
        return BandHistoryParser.Measurement.fromJson(new org.json.JSONObject()
                .put("recordId", kind + "-" + start)
                .put("deviceId", "device")
                .put("kind", kind)
                .put("startMs", start)
                .put("endMs", end)
                .put("value", value)
                .put("stage", org.json.JSONObject.NULL)
                .put("timezone", "Asia/Shanghai")
                .put("measurementMode", "continuous")
                .put("complete", false)
                .put("sourceFingerprint", "a".repeat(64)));
    }

    @Test public void stepDaysUseTheDailyReportAndKeepEachTimezone() {
        long day = java.time.LocalDate.of(2026, 9, 24).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli();
        var days = BandHistoryParser.stepDays(java.util.List.of(
                stepRecord("steps_interval", day, day + 60_000, 4),
                stepRecord("steps_interval", day + 60_000, day + 120_000, 6),
                stepRecord("steps_day", day, day + 86_400_000L, 20),
                stepRecord("steps_interval", day + 3_600_000, day + 3_660_000, 9, "+05:30")));
        assertEquals(2, days.size());
        assertEquals(20260924, days.get(0).date);
        assertEquals(20, days.get(0).steps);
        assertEquals("Asia/Shanghai", days.get(0).timezone);
        assertEquals(9, days.get(1).steps);
        assertEquals("+05:30", days.get(1).timezone);
    }

    private static io.github.miam1ku.mibandoplusbridge.data.HealthRecord stepRecord(
            String kind, long start, long end, int value) {
        return stepRecord(kind, start, end, value, "Asia/Shanghai");
    }

    private static io.github.miam1ku.mibandoplusbridge.data.HealthRecord stepRecord(
            String kind, long start, long end, int value, String zone) {
        return new io.github.miam1ku.mibandoplusbridge.data.HealthRecord("rec-" + kind + "-" + start + zone, "device",
                kind, start, end, value, null, 1, zone, "continuous", false);
    }

    @Test public void liveStepTotalRaisesTodayAndDoesNotShrinkIt() {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        long day = java.time.LocalDate.of(2026, 9, 24).atStartOfDay(zone)
                .toInstant().toEpochMilli();
        var saved = BandHistoryParser.stepDays(java.util.List.of(
                stepRecord("steps_day", day, day + 86_400_000L, 20)));
        var raised = BandHistoryParser.preferLiveTotal(saved, 30, day + 3_600_000);
        var kept = BandHistoryParser.preferLiveTotal(saved, 10, day + 3_600_000);
        assertEquals(1, raised.size());
        assertEquals(1, kept.size());
        assertEquals(30, raised.get(0).steps);
        assertEquals(20, kept.get(0).steps);
    }

    @Test public void fragmentedFilesRetainMeasurementIdentityAcrossRevisions() throws Exception {
        BandHistoryParser parser = parser();
        byte[] first = file(5, 1, reportBody(20));
        byte[] second = file(5, 1, reportBody(21));
        byte[] firstHalf = Arrays.copyOfRange(first, 0, 17);
        byte[] secondHalf = Arrays.copyOfRange(first, 17, first.length);
        assertNull(parser.acceptFragment(fragment(2, 1, firstHalf)));
        var accepted = parser.acceptFragment(fragment(2, 2, secondHalf));
        var repeated = parser.acceptFragment(fragment(1, 1, first)).measurements.get(0);
        var revised = parser.acceptFragment(fragment(1, 1, second)).measurements.get(0);
        assertEquals(accepted.measurements.get(0).recordId, repeated.recordId);
        assertEquals(repeated.sourceFingerprint, accepted.measurements.get(0).sourceFingerprint);
        assertEquals(repeated.recordId, revised.recordId);
        assertNotEquals(repeated.sourceFingerprint, revised.sourceFingerprint);
    }

    @Test public void invalidFragmentOrCrcCannotProducePartialMeasurements() throws Exception {
        BandHistoryParser parser = parser();
        byte[] good = file(4, 0, recordBody(2, 3, 64, 77));
        assertNull(parser.acceptFragment(fragment(2, 1, Arrays.copyOfRange(good, 0, 16))));
        assertThrows(IllegalArgumentException.class,
                () -> parser.acceptFragment(fragment(2, 2, new byte[] {1, 2})));
        assertThrows(IllegalArgumentException.class,
                () -> parser.acceptFragment(fragment(2, 2, Arrays.copyOfRange(good, 16, good.length))));
        byte[] corrupt = good.clone();
        corrupt[16] ^= 1;
        assertThrows(IllegalArgumentException.class,
                () -> parser.acceptFragment(fragment(1, 1, corrupt)));
        assertFalse(parser.acceptFragment(fragment(1, 1, good)).measurements.isEmpty());
    }

    @Test public void unsupportedFilesRemainArchivableButBadFramingFails() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new BandHistoryParser("", stableId(), DID));
        assertThrows(IllegalArgumentException.class,
                () -> new BandHistoryParser("4.100.139", stableId(), "different-did"));
        new BandHistoryParser("1.0.50", stableId(), DID);
        BandHistoryParser parser = parser();
        for (int type : new int[] {0, 2 << 2, 3 << 2, 7 << 2}) {
            byte[] raw = file(99, type, new byte[] {0});
            var result = parser.parseFile(raw);
            assertTrue(result.parseStatus.startsWith("UNSUPPORTED_HISTORY_"));
            assertTrue(result.measurements.isEmpty());
            assertArrayEquals(raw, result.copyRawFile());
        }
        byte[] extraLayout = recordBody(1, 1, 0, 0);
        extraLayout[8] |= 0x40; // Announced anomaly byte absent: the final record is truncated.
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseFile(file(4, 0, extraLayout)));
    }

    @Test public void variableFieldsAndAnomalyByteKeepFollowingMinutesAligned() throws Exception {
        byte[] body = activity(new int[] {47, 44, 31, 30, 19, 18, 15, 14, 11, 7},
                new byte[] {3, 0x40, 72, 98, 0, 111, 4, 5, 6, 7,
                        4, 0, 73, 99, 45, 8, 9, 10, 11});
        var records = parser().parseFile(file(4, 0, body)).measurements;
        assertEquals(8, records.size());
        assertEquals(0, records.get(3).value.intValue());
        assertEquals(4, records.get(4).value.intValue());
        assertEquals(73, records.get(5).value.intValue());
        assertEquals(99, records.get(6).value.intValue());
        assertEquals(45, records.get(7).value.intValue());
        assertEquals(records.get(0).startMs + 60_000L, records.get(4).startMs);
        assertEquals("continuous", records.get(1).measurementMode);
    }

    @Test public void presenceAndValidityAreIndependentAndOutOfRangeValuesAreAbsent() throws Exception {
        byte[] body = activity(new int[] {31, 19, 15}, new byte[] {72, 98, 0});
        assertTrue(parser().parseFile(file(4, 0, body)).measurements.isEmpty());
        body = activity(new int[] {31, 30, 19, 18, 15, 14},
                new byte[] {0, 0, 0, (byte) 251, 101, 101, (byte) 250, 100, 100});
        var records = parser().parseFile(file(4, 0, body)).measurements;
        assertEquals(4, records.size());
        assertEquals("stress", records.get(0).kind);
        assertEquals(0, records.get(0).value.intValue());
        assertEquals(250, records.get(1).value.intValue());
        assertEquals(100, records.get(2).value.intValue());
        assertEquals(100, records.get(3).value.intValue());
    }

    @Test public void manualRecordsUseDeclaredPayloadAndKeepRealPointTimes() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0);
        manual(body, START_SECONDS, 0x37, new byte[] {8, 9, 10});
        manual(body, START_SECONDS, 0x11, new byte[] {81});
        manual(body, START_SECONDS + 1, 0x12, new byte[] {98});
        manual(body, START_SECONDS + 2, 0x13, new byte[] {0});
        var records = parser().parseFile(file(2, 6 << 2, body.toByteArray())).measurements;
        assertEquals(3, records.size());
        for (int i = 0; i < records.size(); i++) {
            assertEquals((START_SECONDS + (long) i) * 1000, records.get(i).startMs);
            assertEquals(records.get(i).startMs + 1, records.get(i).endMs);
            assertEquals("manual", records.get(i).measurementMode);
        }
        assertEquals("heart_rate", records.get(0).kind);
        assertEquals("spo2", records.get(1).kind);
        assertEquals("stress", records.get(2).kind);
        assertEquals(0, records.get(2).value.intValue());
        body.reset(); body.write(0);
        manual(body, START_SECONDS, 0x21, new byte[] {81, 99});
        assertEquals("UNSUPPORTED_MANUAL_PAYLOAD_LENGTH",
                parser().parseFile(file(2, 6 << 2, body.toByteArray())).parseStatus);
        byte[] truncated = {0, 1, 2, 3, 4, 0x31, 81};
        assertThrows(IllegalArgumentException.class,
                () -> parser().parseFile(file(2, 6 << 2, truncated)));
    }

    @Test public void recordIdentityPreservesLegacyBaseAndSeparatesModes() throws Exception {
        int aligned = START_SECONDS / 60 * 60;
        var continuous = parser().parseFile(file(4, 0,
                activity(new int[] {31, 30}, new byte[] {81}))).measurements.get(0);
        ByteArrayOutputStream body = new ByteArrayOutputStream(); body.write(0);
        manual(body, aligned, 0x11, new byte[] {81});
        var manual = parser().parseFile(file(2, 6 << 2, body.toByteArray())).measurements.get(0);
        String base = "xiaomi_ff917cf1ba2363ca168d5483b327936ba68c3b6cd60f13cb49cbd81e4fa971cf";
        assertEquals(base + ":continuous", continuous.recordId);
        assertEquals(base + ":manual", manual.recordId);
        assertNotEquals(continuous.sourceFingerprint, manual.sourceFingerprint);
        String mac = "AA:BB:CC:DD:EE:FF";
        String id = "miband11_" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(mac.getBytes(StandardCharsets.UTF_8)));
        assertEquals(id, new BandHistoryParser("4.100.139", id, mac).parseFile(file(4, 0,
                activity(new int[] {31, 30}, new byte[] {81}))).measurements.get(0).deviceId);
    }

    @Test public void sleepReportsRetainUnknownDurationAndRealAncillaryValuesAcrossMidnight() throws Exception {
        for (int version : new int[] {5, 6}) {
            byte[] body = sleepBody(version, true);
            var records = parser().parseFile(file(version, (8 << 2) | 1, body)).measurements;
            assertEquals(4, records.size());
            var sleep = records.get(0);
            assertEquals("sleep_interval", sleep.kind);
            assertNull(sleep.value);
            assertNull(sleep.stage);
            assertTrue(sleep.complete);
            assertEquals("sleep", sleep.measurementMode);
            assertEquals("+05:30", sleep.timezone);
            assertEquals((START_SECONDS + 18 * 3600L) * 1000, sleep.startMs);
            assertEquals((START_SECONDS + 26 * 3600L) * 1000, sleep.endMs);
            var zone = java.time.ZoneId.of(sleep.timezone);
            assertEquals(java.time.Instant.ofEpochMilli(sleep.startMs).atZone(zone).toLocalDate().plusDays(1),
                    java.time.Instant.ofEpochMilli(sleep.endMs).atZone(zone).toLocalDate());
            assertEquals(70, records.get(1).value.intValue());
            assertEquals(71, records.get(2).value.intValue());
            assertEquals(records.get(1).startMs + 60_000, records.get(2).startMs);
            assertEquals(98, records.get(3).value.intValue());
            assertEquals(records.get(3).startMs + 1, records.get(3).endMs);
            assertFalse(records.get(1).complete);
            var incomplete = parser().parseFile(file(version, (8 << 2) | 1,
                    sleepBody(version, false))).measurements.get(0);
            assertFalse(incomplete.complete);
            assertEquals(sleep.recordId, incomplete.recordId);
            assertNotEquals(sleep.sourceFingerprint, incomplete.sourceFingerprint);
            byte[] truncated = Arrays.copyOf(body, version == 5 ? 12 : 40);
            assertThrows(IllegalArgumentException.class,
                    () -> parser().parseFile(file(version, (8 << 2) | 1, truncated)));
        }
    }

    private static byte[] sleepBody(int version, boolean complete) {
        int sleepStart = START_SECONDS + 18 * 3600;
        int flags = version == 5 ? 2 : 3;
        int fixed = version == 5 ? 27 : 51;
        byte[] body = new byte[1 + flags + fixed + 10 + 9 + (version == 6 ? 10 : 0) + 12 + 3];
        long valid = version == 5 ? 0xc0e0L : 0xc0001eL;
        for (int i = 0; i < flags; i++) body[1 + i] = (byte) (valid >>> ((flags - i - 1) * 8));
        int p = 1 + flags;
        body[p] = (byte) (complete ? 1 : 0);
        put32(body, p + 1, sleepStart);
        put32(body, p + 5, sleepStart + 8 * 3600);
        put32(body, p + 11, 600); // Latency, not sleep duration.
        put32(body, p + 15, 32000); // Bed duration, not sleep duration.
        p += fixed;
        put16(body, p, 60); put16(body, p + 2, 2); put32(body, p + 4, sleepStart);
        body[p + 8] = 70; body[p + 9] = 71; p += 10;
        put16(body, p, 60); put16(body, p + 2, 1); put32(body, p + 4, sleepStart + 30);
        body[p + 8] = 98; p += 9;
        if (version == 6) {
            put16(body, p, 60); put16(body, p + 2, 1); put32(body, p + 4, sleepStart);
            put16(body, p + 8, 42); p += 10;
        }
        put16(body, p, 60); put16(body, p + 2, 1); put32(body, p + 4, sleepStart);
        put32(body, p + 8, 30); p += 12;
        body[p] = 2; body[p + 1] = 3; body[p + 2] = 4; // Opaque features never become stages.
        return body;
    }

    private static byte[] activity(int[] flags, byte[] payload) {
        byte[] body = new byte[7 + payload.length];
        for (int bit : flags) body[1 + (47 - bit) / 8] |= (byte) (1 << (bit % 8));
        System.arraycopy(payload, 0, body, 7, payload.length);
        return body;
    }

    private static void manual(ByteArrayOutputStream body, int seconds, int descriptor, byte[] payload) {
        byte[] header = new byte[5]; put32(header, 0, seconds); header[4] = (byte) descriptor;
        body.writeBytes(header); body.writeBytes(payload);
    }

    @Test public void sleepWithoutValidBoundsNeverCreatesAnIntervalOrGuessedStages() throws Exception {
        for (int version : new int[] {5, 6}) {
            byte[] body = sleepBody(version, true);
            body[1] &= 0x3f;
            var result = parser().parseFile(file(version, (8 << 2) | 1, body));
            assertEquals(3, result.measurements.size());
            for (var measurement : result.measurements) {
                assertTrue(measurement.kind.equals("heart_rate") || measurement.kind.equals("spo2"));
                assertNull(measurement.stage);
            }
            body = sleepBody(version, true);
            int fixedStart = version == 5 ? 3 : 4;
            put32(body, fixedStart + 5, START_SECONDS - 1);
            result = parser().parseFile(file(version, (8 << 2) | 1, body));
            assertEquals("INVALID_SLEEP_INTERVAL", result.parseStatus);
            assertTrue(result.measurements.isEmpty());
            body = sleepBody(version, true);
            body[fixedStart] = 2;
            assertEquals("INVALID_SLEEP_COMPLETE_FLAG",
                    parser().parseFile(file(version, (8 << 2) | 1, body)).parseStatus);
        }
    }

    @Test public void sleepStagePacketsFillTheIntervalWithoutInventingACoveringBar() throws Exception {
        int sleepStart = START_SECONDS + 18 * 3600;
        byte[] body = sleepBody(6, true);
        byte[] packet = new byte[21];
        packet[0] = (byte) 0xfb; packet[1] = (byte) 0xfa;
        packet[2] = (byte) 0xfc; packet[3] = (byte) 0xff;
        packet[4] = 17;
        put32(packet, 5, sleepStart);
        packet[14] = 17;
        packet[16] = 4;
        int deep = (2 << 12) | 120;
        int light = (1 << 12) | 360;
        packet[17] = (byte) (deep >>> 8); packet[18] = (byte) deep;
        packet[19] = (byte) (light >>> 8); packet[20] = (byte) light;
        byte[] withStages = Arrays.copyOf(body, body.length + packet.length);
        System.arraycopy(packet, 0, withStages, body.length, packet.length);
        var records = parser().parseFile(file(6, (8 << 2) | 1, withStages)).measurements;
        var stages = records.stream().filter(item -> "sleep_stage".equals(item.kind)).toList();
        assertEquals(2, stages.size());
        assertEquals(2, stages.get(0).stage.intValue());
        assertEquals((sleepStart) * 1000L, stages.get(0).startMs);
        assertEquals((sleepStart + 120 * 60) * 1000L, stages.get(0).endMs);
        assertEquals(3, stages.get(1).stage.intValue());
        assertEquals(stages.get(0).endMs, stages.get(1).startMs);
        assertEquals((sleepStart + 480 * 60) * 1000L, stages.get(1).endMs);
        assertEquals("sleep", stages.get(0).measurementMode);
        assertFalse(stages.get(0).complete);
    }


    @Test public void manualInvalidValuesAreSkippedWithoutLosingFollowingRecords() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream(); body.write(0);
        manual(body, START_SECONDS, 0x11, new byte[] {0});
        manual(body, START_SECONDS + 1, 0x11, new byte[] {(byte) 251});
        manual(body, START_SECONDS + 2, 0x12, new byte[] {0});
        manual(body, START_SECONDS + 3, 0x12, new byte[] {101});
        manual(body, START_SECONDS + 4, 0x13, new byte[] {101});
        manual(body, START_SECONDS + 5, 0x13, new byte[] {100});
        var result = parser().parseFile(file(2, 6 << 2, body.toByteArray()));
        assertEquals(1, result.measurements.size());
        assertEquals("stress", result.measurements.get(0).kind);
        assertEquals(100, result.measurements.get(0).value.intValue());
        assertEquals((START_SECONDS + 5L) * 1000, result.measurements.get(0).startMs);
    }

    @Test public void zeroSleepSampleCountDoesNotConsumeTheNextSeriesTimestamp() throws Exception {
        byte[] body = Arrays.copyOf(sleepBody(5, true), 43);
        body[2] = (byte) 0xc0; // HR and oxygen, no snore.
        put16(body, 30, 60); put16(body, 32, 0); // No firstTime when count is zero.
        put16(body, 34, 60); put16(body, 36, 1);
        put32(body, 38, START_SECONDS + 18 * 3600 + 30);
        body[42] = 97;
        var records = parser().parseFile(file(5, (8 << 2) | 1, body)).measurements;
        assertEquals(2, records.size());
        assertEquals("sleep_interval", records.get(0).kind);
        assertEquals("spo2", records.get(1).kind);
        assertEquals(97, records.get(1).value.intValue());
        assertEquals((START_SECONDS + 18 * 3600L + 30) * 1000, records.get(1).startMs);
    }

    @Test public void replayMeasurementRetainsCompletenessAndRejectsLegacyPayloads() throws Exception {
        var source = parser().parseFile(file(6, (8 << 2) | 1, sleepBody(6, true))).measurements.get(0);
        var decoded = BandHistoryParser.Measurement.fromJson(source.toJson());
        assertTrue(decoded.toRecord(3).complete);
        assertEquals("sleep", decoded.toRecord(3).measurementMode);
        assertEquals(3, decoded.toRecord(3).revision);
        assertNull(decoded.value);
        var legacy = source.toJson(); legacy.remove("measurementMode");
        assertThrows(org.json.JSONException.class, () -> BandHistoryParser.Measurement.fromJson(legacy));
        var missing = source.toJson(); missing.remove("complete");
        assertThrows(org.json.JSONException.class, () -> BandHistoryParser.Measurement.fromJson(missing));
        var wrongType = source.toJson(); wrongType.put("complete", "true");
        assertThrows(IllegalArgumentException.class, () -> BandHistoryParser.Measurement.fromJson(wrongType));
    }

    @Test public void semanticFailureDoesNotHideLaterTruncation() throws Exception {
        byte[] complete = activity(new int[] {47}, new byte[1441 * 2]);
        assertEquals("INVALID_ACTIVITY_RECORD_COUNT", parser().parseFile(file(4, 0, complete)).parseStatus);
        byte[] truncated = activity(new int[] {47}, new byte[1441 * 2 + 1]);
        assertThrows(IllegalArgumentException.class, () -> parser().parseFile(file(4, 0, truncated)));
        byte[] sleep = sleepBody(5, true);
        put16(sleep, 30, 0); // Two HR values with a semantically invalid zero interval.
        assertEquals("INVALID_SLEEP_SAMPLE_INTERVAL",
                parser().parseFile(file(5, (8 << 2) | 1, sleep)).parseStatus);
        byte[] missingOxygen = Arrays.copyOf(sleep, 42);
        assertThrows(IllegalArgumentException.class,
                () -> parser().parseFile(file(5, (8 << 2) | 1, missingOxygen)));
    }

    private static BandHistoryParser parser() throws Exception {
        return new BandHistoryParser("4.100.139", stableId(), DID);
    }

    private static String stableId() throws Exception {
        return "miband11_" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(DID.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] recordBody(int firstSteps, int secondSteps, int firstHr, int secondHr) {
        byte[] data = new byte[7 + 2 * 16];
        // Present fields: v4 fixed 16-byte layout. Validity includes both steps and HR.
        for (int bit : new int[] {47, 44, 43, 39, 35, 31, 30, 27, 23, 19, 15, 11, 7}) {
            data[1 + (47 - bit) / 8] |= (byte) (1 << (bit % 8));
        }
        put16(data, 7, firstSteps);
        put16(data, 23, secondSteps);
        data[13] = (byte) firstHr;
        data[29] = (byte) secondHr;
        return data;
    }

    private static byte[] reportBody(int steps) {
        byte[] data = new byte[5 + 53];
        data[1] = (byte) 0x80; // totalStepsValid, not "all values valid".
        put32(data, 5, steps);
        return data;
    }

    private static byte[] file(int version, int fileType, byte[] data) {
        byte[] bytes = new byte[7 + data.length + 4];
        put32(bytes, 0, START_SECONDS);
        bytes[4] = 22; // +05:30, synthetic fixed offset
        bytes[5] = (byte) version;
        bytes[6] = (byte) fileType;
        System.arraycopy(data, 0, bytes, 7, data.length);
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 4);
        put32(bytes, bytes.length - 4, (int) crc.getValue());
        return bytes;
    }

    private static byte[] fragment(int total, int sequence, byte[] part) {
        byte[] packet = new byte[part.length + 4];
        put16(packet, 0, total);
        put16(packet, 2, sequence);
        System.arraycopy(part, 0, packet, 4, part.length);
        return packet;
    }

    private static void put16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    private static void put32(byte[] data, int offset, int value) {
        for (int i = 0; i < 4; i++) data[offset + i] = (byte) (value >>> (i * 8));
    }
}
