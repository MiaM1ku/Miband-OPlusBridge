// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Decoder for complete Xiaomi fitness files observed from M2616B1 firmware 4.100.139. */
public final class BandHistoryParser {
    private static final int MAX_FRAGMENTS = 256;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int FILE_ID_BYTES = 7;
    private static final int REPORT_BYTES = 53;
    private static final long MINUTE_MS = 60_000L;
    private static final byte[] STEPS_INTERVAL = "steps_interval".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STEPS_DAY = "steps_day".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEART_RATE = "heart_rate".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SPO2 = "spo2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STRESS = "stress".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SLEEP_INTERVAL = "sleep_interval".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SLEEP_STAGE = "sleep_stage".getBytes(StandardCharsets.US_ASCII);
    private static final long DAY_MS = 86_400_000L;
    private static final int[] PRESENT_FIELDS = {47, 43, 39, 35, 31, 27, 23, 19, 15, 11, 7};
    private static final int[] FIELD_WIDTHS = {2, 1, 1, 2, 1, 1, 2, 1, 1, 2, 2};

    private final String deviceId;
    private final byte[] identityBytes;
    private final MessageDigest digest;
    private final ArrayList<byte[]> fragments = new ArrayList<>();
    private int totalFragments;
    private int receivedBytes;

    /** Identity material is the confirmed did or the same normalized MAC used for deviceId. */
    public BandHistoryParser(String firmware, String deviceId, String identityMaterial) {
        if (!"4.100.139".equals(firmware) || identityMaterial == null || identityMaterial.isBlank()) {
            throw new IllegalArgumentException("UNSUPPORTED_HISTORY_SOURCE");
        }
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", e);
        }
        String expected = "miband11_" + HexFormat.of().formatHex(
                digest.digest(identityMaterial.getBytes(StandardCharsets.UTF_8)));
        if (!expected.equals(deviceId)) throw new IllegalArgumentException("HISTORY_IDENTITY_MISMATCH");
        this.deviceId = deviceId;
        this.identityBytes = deviceId.getBytes(StandardCharsets.UTF_8);
    }

    /** Reset at every session boundary; no fragment from an earlier session is reusable. */
    public synchronized void reset() {
        fragments.clear();
        totalFragments = 0;
        receivedBytes = 0;
    }

    /**
     * Accepts the payload of Xiaomi raw channel 0x66, not a type-8 protobuf envelope.
     * Returns null until every fragment is present. A completed result retains the full
     * CRC-checked source file; its owner must durably save that file before any band confirm.
     */
    public synchronized FileResult acceptFragment(byte[] fragment) {
        if (fragment == null || fragment.length < 5 || fragment.length > MAX_FILE_BYTES) {
            reset();
            throw new IllegalArgumentException("INVALID_HISTORY_FRAGMENT");
        }
        int total = u16(fragment, 0);
        int sequence = u16(fragment, 2);
        if (total < 1 || total > MAX_FRAGMENTS || sequence < 1 || sequence > total
                || (totalFragments != 0 && totalFragments != total)
                || sequence != fragments.size() + 1
                || receivedBytes > MAX_FILE_BYTES - (fragment.length - 4)) {
            reset();
            throw new IllegalArgumentException("INVALID_HISTORY_SEQUENCE");
        }
        totalFragments = total;
        byte[] part = Arrays.copyOfRange(fragment, 4, fragment.length);
        fragments.add(part);
        receivedBytes += part.length;
        if (sequence != total) return null;

        byte[] fullFile = new byte[receivedBytes];
        int offset = 0;
        for (byte[] next : fragments) {
            System.arraycopy(next, 0, fullFile, offset, next.length);
            offset += next.length;
        }
        reset();
        return decodeFile(fullFile);
    }

    /** Archive replay uses exactly the same envelope and semantic validation as live receipt. */
    public synchronized FileResult parseFile(byte[] raw) {
        if (raw == null || raw.length < 12 || raw.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("INVALID_HISTORY_FILE_LENGTH");
        }
        return decodeFile(raw.clone());
    }

    private FileResult decodeFile(byte[] bytes) {
        if (bytes.length < 12 || bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("INVALID_HISTORY_FILE_LENGTH");
        }
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 4);
        if (crc.getValue() != u32(bytes, bytes.length - 4)) {
            throw new IllegalArgumentException("HISTORY_CRC_MISMATCH");
        }
        int descriptor = bytes[6] & 0xff;
        int fileType = descriptor & 3;
        int dailyType = (descriptor & 0x7f) >>> 2;
        int version = bytes[5] & 0xff;
        int quarterHours = bytes[4];
        int offsetSeconds = quarterHours * 15 * 60;
        try {
            if (offsetSeconds < -12 * 3600 || offsetSeconds > 14 * 3600) {
                throw new SemanticException("INVALID_HISTORY_TIMEZONE");
            }
            if (bytes[7] != 0) throw new SemanticException("UNSUPPORTED_HISTORY_ENCODING");
            if ((descriptor & 0x80) != 0) throw new SemanticException("UNSUPPORTED_SPORT_FILE");
            int absMinutes = Math.abs(quarterHours) * 15;
            String timezone = String.format(java.util.Locale.ROOT, "%c%02d:%02d",
                    quarterHours < 0 ? '-' : '+', absMinutes / 60, absMinutes % 60);
            List<Measurement> measurements;
            if (dailyType == 0 && fileType == 0 && version == 4) {
                measurements = decodeRecords(bytes, u32(bytes, 0), timezone);
            } else if (dailyType == 0 && fileType == 1 && version == 5) {
                measurements = decodeReport(bytes, u32(bytes, 0), offsetSeconds, timezone);
            } else if (dailyType == 6 && fileType == 0 && version == 2) {
                measurements = decodeManual(bytes, timezone);
            } else if (dailyType == 8 && fileType == 1 && (version == 5 || version == 6)) {
                measurements = decodeSleep(bytes, version, timezone);
            } else {
                throw new SemanticException("UNSUPPORTED_HISTORY_DT" + dailyType
                        + "_FT" + fileType + "_V" + version);
            }
            return new FileResult(bytes, measurements, "PARSED");
        } catch (SemanticException unsupported) {
            return new FileResult(bytes, List.of(), unsupported.getMessage());
        }
    }

    private List<Measurement> decodeRecords(byte[] bytes, long seconds, String timezone) {
        Cursor cursor = new Cursor(bytes, 8);
        long validity = cursor.flags(6);
        int width = 0;
        for (int i = 0; i < PRESENT_FIELDS.length; i++) {
            if (bit(validity, PRESENT_FIELDS[i])) width += FIELD_WIDTHS[i];
        }
        if (width == 0) {
            if (cursor.remaining() != 0) throw new SemanticException("UNSUPPORTED_EMPTY_ACTIVITY_LAYOUT");
            return List.of();
        }
        List<Measurement> records = new ArrayList<>();
        long firstMinute = seconds / 60 * MINUTE_MS;
        boolean tooManyRecords = false;
        for (int index = 0; cursor.remaining() > 0; index++) {
            long minute = firstMinute + index * MINUTE_MS;
            int steps = bit(validity, 47) ? cursor.uint(2) : 0;
            if (bit(validity, 43)) cursor.skip(1);
            if (bit(validity, 39)) cursor.skip(1);
            if (bit(validity, 35)) cursor.skip(2);
            int heart = bit(validity, 31) ? cursor.uint(1) : -1;
            if (bit(validity, 27)) cursor.skip(1);
            if (bit(validity, 23)) cursor.skip(2);
            int oxygen = bit(validity, 19) ? cursor.uint(1) : -1;
            int stress = bit(validity, 15) ? cursor.uint(1) : -1;
            // The rise flag changes framing even if its independent validity bit is clear.
            if ((steps & 0x4000) != 0) cursor.skip(1);
            if (bit(validity, 11)) cursor.skip(2);
            if (bit(validity, 7)) cursor.skip(2);
            if (index >= 1440) {
                tooManyRecords = true;
                continue;
            }
            if (bit(validity, 47) && bit(validity, 44)) {
                records.add(measurement("steps_interval", minute, minute + MINUTE_MS,
                        steps & 0x3fff, timezone, "continuous", false));
            }
            if (bit(validity, 30)) addMetric(records, "heart_rate", minute, heart, timezone, "continuous");
            if (bit(validity, 18)) addMetric(records, "spo2", minute, oxygen, timezone, "continuous");
            if (bit(validity, 14)) addMetric(records, "stress", minute, stress, timezone, "continuous");
        }
        if (tooManyRecords) throw new SemanticException("INVALID_ACTIVITY_RECORD_COUNT");
        return records;
    }

    private List<Measurement> decodeReport(byte[] bytes, long seconds, int offsetSeconds,
            String timezone) {
        Cursor cursor = new Cursor(bytes, 8);
        long validity = cursor.flags(4);
        cursor.require(REPORT_BYTES);
        if (cursor.remaining() != REPORT_BYTES) throw new SemanticException("UNSUPPORTED_ACTIVITY_REPORT_LAYOUT");
        if (!bit(validity, 31)) return List.of();
        long day = Math.floorDiv(seconds + offsetSeconds, 86_400L) * DAY_MS - offsetSeconds * 1000L;
        return List.of(measurement("steps_day", day, day + DAY_MS, cursor.unsigned32(),
                timezone, "continuous", false));
    }

    private List<Measurement> decodeManual(byte[] bytes, String timezone) {
        Cursor cursor = new Cursor(bytes, 8);
        List<Measurement> records = new ArrayList<>();
        boolean malformed = false;
        while (cursor.remaining() > 0) {
            long time = cursor.unsigned32() * 1000L;
            int descriptor = cursor.uint(1);
            int length = descriptor >>> 4;
            int kind = descriptor & 15;
            cursor.require(length);
            if (kind >= 1 && kind <= 3) {
                if (length != 1) {
                    malformed = true;
                } else {
                    addMetric(records, kind == 1 ? "heart_rate" : kind == 2 ? "spo2" : "stress",
                            time, bytes[cursor.position] & 0xff, timezone, "manual");
                }
            }
            cursor.skip(length);
        }
        if (malformed) throw new SemanticException("UNSUPPORTED_MANUAL_PAYLOAD_LENGTH");
        return records;
    }

    private List<Measurement> decodeSleep(byte[] bytes, int version, String timezone) {
        Cursor cursor = new Cursor(bytes, 8);
        long validity = cursor.flags(version == 5 ? 2 : 3);
        int full = cursor.uint(1);
        long start = cursor.unsigned32() * 1000L;
        long end = cursor.unsigned32() * 1000L;
        // Quality, efficiency, latency, bed duration and bed times are not total sleep.
        cursor.skip(18);
        if (version == 6) cursor.skip(24); // Ten uint16 HRV scalars and one uint32 timestamp.
        List<Measurement> records = new ArrayList<>();
        boolean validSamples = true;
        if (bit(validity, version == 5 ? 7 : 4)) validSamples &= decodeSleepSeries(cursor, records, "heart_rate", 1, timezone);
        if (bit(validity, version == 5 ? 6 : 3)) validSamples &= decodeSleepSeries(cursor, records, "spo2", 1, timezone);
        if (version == 6 && bit(validity, 2)) validSamples &= decodeSleepSeries(cursor, records, null, 2, timezone);
        if (bit(validity, version == 5 ? 5 : 1)) validSamples &= decodeSleepSeries(cursor, records, null, 4, timezone);
        decodeSleepStages(bytes, cursor, records, timezone, start, end);
        // Remaining unknown feature packets stay in the archived raw file.
        if (!validSamples) throw new SemanticException("INVALID_SLEEP_SAMPLE_INTERVAL");
        if (full > 1) throw new SemanticException("INVALID_SLEEP_COMPLETE_FLAG");
        if (bit(validity, version == 5 ? 15 : 23) && bit(validity, version == 5 ? 14 : 22)) {
            if (end <= start) throw new SemanticException("INVALID_SLEEP_INTERVAL");
            records.add(0, measurement("sleep_interval", start, end, null, timezone, "sleep", full == 1));
        }
        return records;
    }

    private boolean decodeSleepSeries(Cursor cursor, List<Measurement> records, String kind,
            int width, String timezone) {
        int interval = cursor.uint(2);
        int count = cursor.uint(2);
        if (count == 0) return true;
        // Current APK SleepAssistItemInfo computes firstRecordTime + sampleUnit * index
        // directly against Unix-second sleep bounds; both wire fields therefore use seconds.
        long first = cursor.unsigned32() * 1000L;
        cursor.require(count * width);
        if (count > 1 && interval == 0) {
            cursor.skip(count * width);
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (kind == null) cursor.skip(width);
            else addMetric(records, kind, first + i * (long) interval * 1000L,
                    cursor.uint(1), timezone, "sleep");
        }
        return true;
    }

    private void decodeSleepStages(byte[] bytes, Cursor cursor, List<Measurement> records,
            String timezone, long sleepStart, long sleepEnd) {
        int pos = cursor.position;
        int limit = cursor.end;
        List<int[]> runs = new ArrayList<>();
        int totalMinutes = 0;
        while (pos + 17 <= limit) {
            if (u32(bytes, pos) != 0xfffcfafbL) {
                pos++;
                continue;
            }
            if ((bytes[pos + 4] & 0xff) != 17) {
                pos++;
                continue;
            }
            int type = bytes[pos + 14] & 0xff;
            int dataLen = ((bytes[pos + 15] & 0xff) << 8) | (bytes[pos + 16] & 0xff);
            boolean noData = type == 2 || type == 3 || type == 9 || type == 0x0c
                    || type == 0x0d || type == 0x0e || type == 0x0f;
            int payload = noData ? 0 : dataLen;
            if (payload < 0 || pos + 17 + payload > limit) break;
            if (type == 17) {
                int off = pos + 17;
                for (int i = 0; i + 2 <= payload; i += 2) {
                    int val = ((bytes[off + i] & 0xff) << 8) | (bytes[off + i + 1] & 0xff);
                    int stage = xiaomiStage(val >>> 12);
                    int minutes = val & 0xfff;
                    if (stage >= 2 && stage <= 5 && minutes > 0) {
                        runs.add(new int[] {stage, minutes});
                        totalMinutes += minutes;
                    } else if (minutes > 0) {
                        totalMinutes += minutes;
                        runs.add(new int[] {0, minutes});
                    }
                }
            }
            pos += 17 + payload;
        }
        cursor.position = limit;
        if (runs.isEmpty()) return;
        long origin = sleepStart;
        long spanMinutes = (sleepEnd - sleepStart) / 60_000L;
        if (spanMinutes > 0 && Math.abs(totalMinutes - spanMinutes) > 2) return;
        long current = origin;
        for (int[] run : runs) {
            long next = current + run[1] * 60_000L;
            if (run[0] >= 2 && run[0] <= 5 && next > current) {
                long a = Math.max(current, sleepStart);
                long b = Math.min(next, sleepEnd);
                if (b > a) records.add(sleepStage(a, b, run[0], timezone));
            }
            current = next;
        }
    }

    private static int xiaomiStage(int raw) {
        return switch (raw) {
            case 0 -> 5;
            case 1 -> 3;
            case 2 -> 2;
            case 3 -> 4;
            default -> 0;
        };
    }

    private Measurement sleepStage(long start, long end, int stage, String timezone) {
        if (start < 0 || end <= start) throw new SemanticException("INVALID_HISTORY_TIMESTAMP");
        return new Measurement(recordId("sleep_stage", start) + ":sleep", deviceId, "sleep_stage",
                start, end, null, stage, timezone, "sleep", false,
                sourceFingerprint("sleep_stage", start, end, null, timezone, "sleep", false, stage));
    }


    private void addMetric(List<Measurement> records, String kind, long start, int value,
            String timezone, String mode) {
        int min = "stress".equals(kind) ? 0 : 1;
        int max = "heart_rate".equals(kind) ? 250 : 100;
        if (value >= min && value <= max) records.add(measurement(kind, start,
                start + ("continuous".equals(mode) ? MINUTE_MS : 1), value, timezone, mode, false));
    }

    private Measurement measurement(String kind, long start, long end, Number value,
            String timezone, String mode, boolean complete) {
        if (start < 0 || end <= start) throw new SemanticException("INVALID_HISTORY_TIMESTAMP");
        return new Measurement(recordId(kind, start) + ":" + mode, deviceId, kind, start, end,
                value, null, timezone, mode, complete,
                sourceFingerprint(kind, start, end, value, timezone, mode, complete));
    }

    private static boolean bit(long flags, int bit) { return (flags & 1L << bit) != 0; }

    private static final class SemanticException extends IllegalArgumentException {
        SemanticException(String status) { super(status); }
    }

    private static final class Cursor {
        final byte[] bytes;
        final int end;
        int position;
        Cursor(byte[] bytes, int position) {
            this.bytes = bytes;
            this.position = position;
            this.end = bytes.length - 4;
        }
        int remaining() { return end - position; }
        void require(int length) {
            if (length < 0 || length > remaining()) throw new IllegalArgumentException("TRUNCATED_HISTORY_BODY");
        }
        void skip(int length) { require(length); position += length; }
        int uint(int length) {
            require(length);
            int value = 0;
            for (int i = 0; i < length; i++) value |= (bytes[position++] & 0xff) << (8 * i);
            return value;
        }
        long unsigned32() { require(4); long value = u32(bytes, position); position += 4; return value; }
        long flags(int length) {
            require(length);
            long value = 0;
            for (int i = 0; i < length; i++) value = value << 8 | bytes[position++] & 0xffL;
            return value;
        }
    }

    private String recordId(String kind, long start) {
        digest.reset();
        digest.update(identityBytes);
        digest.update((byte) ':');
        digest.update(kindBytes(kind));
        for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (start >>> shift));
        return "xiaomi_" + HexFormat.of().formatHex(digest.digest());
    }

    private String sourceFingerprint(String kind, long start, long end, Number value,
            String timezone, String mode, boolean complete) {
        return sourceFingerprint(kind, start, end, value, timezone, mode, complete, null);
    }

    private String sourceFingerprint(String kind, long start, long end, Number value,
            String timezone, String mode, boolean complete, Integer stage) {
        digest.reset();
        digest.update(identityBytes);
        digest.update((byte) 0);
        digest.update(kindBytes(kind));
        digest.update((byte) 0);
        updateLong(start);
        updateLong(end);
        digest.update((byte) (value == null ? 0 : 1));
        if (value != null) updateLong(value.longValue());
        updateAscii(timezone);
        digest.update((byte) 0);
        updateAscii(mode);
        digest.update((byte) (complete ? 1 : 0));
        if (stage != null) {
            digest.update((byte) 1);
            digest.update(stage.byteValue());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void updateAscii(String text) {
        for (int i = 0; i < text.length(); i++) digest.update((byte) text.charAt(i));
    }

    private void updateLong(long number) {
        for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (number >>> shift));
    }

    private static byte[] kindBytes(String kind) {
        return switch (kind) {
            case "steps_interval" -> STEPS_INTERVAL;
            case "steps_day" -> STEPS_DAY;
            case "heart_rate" -> HEART_RATE;
            case "spo2" -> SPO2;
            case "stress" -> STRESS;
            case "sleep_interval" -> SLEEP_INTERVAL;
            case "sleep_stage" -> SLEEP_STAGE;
            default -> throw new IllegalArgumentException("UNSUPPORTED_HEALTH_KIND");
        };
    }

    private static int u16(byte[] bytes, int index) {
        return (bytes[index] & 0xff) | (bytes[index + 1] & 0xff) << 8;
    }

    private static long u32(byte[] bytes, int index) {
        return ((long) bytes[index] & 0xff) | (((long) bytes[index + 1] & 0xff) << 8)
                | (((long) bytes[index + 2] & 0xff) << 16)
                | (((long) bytes[index + 3] & 0xff) << 24);
    }

    public static final class FileResult {
        private final byte[] rawFile;
        public final List<Measurement> measurements;
        public final String parseStatus;

        private FileResult(byte[] rawFile, List<Measurement> measurements, String parseStatus) {
            this.rawFile = rawFile;
            this.measurements = List.copyOf(measurements);
            this.parseStatus = parseStatus;
        }

        /** Persist this entire CRC-checked file before acknowledging it to the band. */
        public byte[] copyRawFile() {
            return rawFile.clone();
        }

        /** Seven-byte Xiaomi file identifier for a later, separately gated confirm. */
        public byte[] copyFileId() {
            return Arrays.copyOf(rawFile, FILE_ID_BYTES);
        }
    }

    /** Daily report wins. Otherwise today's minute deltas are the total; other days are ignored. */
    public static DailySteps todaySteps(List<Measurement> measurements, long dayStart, long dayEnd) {
        if (measurements == null || dayStart < 0 || dayEnd <= dayStart) {
            throw new IllegalArgumentException("INVALID_STEP_WINDOW");
        }
        long daily = -1;
        long dailyAt = -1;
        long interval = 0;
        long intervalAt = -1;
        boolean intervals = false;
        for (Measurement measurement : measurements) {
            if (measurement == null || measurement.value == null || measurement.value.longValue() < 0) continue;
            long value = measurement.value.longValue();
            if ("steps_day".equals(measurement.kind)
                    && measurement.startMs < dayEnd && measurement.endMs > dayStart) {
                daily = value;
                dailyAt = measurement.startMs;
            } else if ("steps_interval".equals(measurement.kind)
                    && measurement.startMs >= dayStart && measurement.startMs < dayEnd) {
                if (value > 200_000L || interval > 200_000L - value) return null;
                intervals = true;
                interval += value;
                if (measurement.startMs > intervalAt) intervalAt = measurement.startMs;
            }
        }
        if (dailyAt >= 0) return daily > 200_000L ? null : new DailySteps(daily, dailyAt, true);
        return intervals ? new DailySteps(interval, intervalAt, false) : null;
    }

    public static final class DailySteps {
        public final long steps;
        public final long measuredAtMs;
        public final boolean authoritative;
        private DailySteps(long steps, long measuredAtMs, boolean authoritative) {
            this.steps = steps;
            this.measuredAtMs = measuredAtMs;
            this.authoritative = authoritative;
        }
    }

    /** One native day row. A daily report replaces minute sums for that timezone and date. */
    public static List<StepDay> stepDays(List<HealthRecord> records) {
        if (records == null) throw new IllegalArgumentException("INVALID_STEP_WINDOW");
        java.util.LinkedHashMap<String, long[]> days = new java.util.LinkedHashMap<>();
        for (HealthRecord record : records) {
            if (record == null || record.value == null || record.timezone == null) continue;
            long value = record.value.longValue();
            if (value < 0 || value > 200_000L) continue;
            boolean report = "steps_day".equals(record.kind);
            boolean interval = "steps_interval".equals(record.kind);
            if (!report && !interval) continue;
            java.time.ZoneId zone;
            try {
                zone = java.time.ZoneId.of(record.timezone);
            } catch (java.time.DateTimeException invalid) {
                continue;
            }
            java.time.LocalDate date = java.time.Instant.ofEpochMilli(record.startMs).atZone(zone).toLocalDate();
            int numeric = date.getYear() * 10_000 + date.getMonthValue() * 100 + date.getDayOfMonth();
            String key = record.timezone + "|" + numeric;
            long[] acc = days.computeIfAbsent(key, unused -> new long[] {numeric, -1, 0, 0,
                    date.atStartOfDay(zone).toInstant().toEpochMilli()});
            if (report) acc[1] = acc[1] < 0 ? value : Math.max(acc[1], value);
            else if (acc[2] <= 200_000L - value) {
                acc[2] += value;
                acc[3] = 1;
            } else acc[3] = -1;
        }
        List<StepDay> result = new ArrayList<>();
        for (var entry : days.entrySet()) {
            long[] acc = entry.getValue();
            long steps = acc[1] >= 0 ? acc[1] : acc[3] == 1 ? acc[2] : -1;
            if (steps < 0 || steps > 200_000L) continue;
            int split = entry.getKey().lastIndexOf('|');
            result.add(new StepDay((int) acc[0], steps, acc[4], entry.getKey().substring(0, split)));
        }
        return result;
    }

    public static final class StepDay {
        public final int date;
        public final long steps;
        public final long startMs;
        public final String timezone;
        private StepDay(int date, long steps, long startMs, String timezone) {
            this.date = date;
            this.steps = steps;
            this.startMs = startMs;
            this.timezone = timezone;
        }
    }

    /** The band's passive total is today's count. It never lowers a larger saved day. */
    public static List<StepDay> preferLiveTotal(List<StepDay> days, long steps, long atMs) {
        if (days == null) throw new IllegalArgumentException("INVALID_STEP_WINDOW");
        if (steps < 0 || steps > 200_000L || atMs <= 0) return List.copyOf(days);
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.ZonedDateTime local = java.time.Instant.ofEpochMilli(atMs).atZone(zone);
        int numeric = local.getYear() * 10_000 + local.getMonthValue() * 100 + local.getDayOfMonth();
        long start = local.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
        List<StepDay> result = new ArrayList<>();
        boolean seen = false;
        for (StepDay day : days) {
            if (day.date != numeric) {
                result.add(day);
                continue;
            }
            seen = true;
            result.add(new StepDay(day.date, Math.max(day.steps, steps), day.startMs, day.timezone));
        }
        if (!seen) result.add(new StepDay(numeric, steps, start, zone.getId()));
        return result;
    }



    /** A decoded fact without a revision; the durable queue assigns that monotonically. */
    public static final class Measurement {
        private static final Set<String> FIELDS = Set.of("recordId", "deviceId", "kind", "startMs",
                "endMs", "value", "stage", "timezone", "measurementMode", "complete", "sourceFingerprint");

        public final String recordId;
        public final String deviceId;
        public final String kind;
        public final long startMs;
        public final long endMs;
        public final Number value;
        public final Integer stage;
        public final String timezone;
        public final String sourceFingerprint;
        public final String measurementMode;
        public final boolean complete;

        private Measurement(String recordId, String deviceId, String kind, long startMs,
                long endMs, Number value, Integer stage, String timezone,
                String measurementMode, boolean complete, String fingerprint) {
            if (fingerprint == null || fingerprint.length() != 64) {
                throw new IllegalArgumentException("INVALID_SOURCE_FINGERPRINT");
            }
            for (int i = 0; i < fingerprint.length(); i++) {
                char digit = fingerprint.charAt(i);
                if (!(digit >= '0' && digit <= '9' || digit >= 'a' && digit <= 'f')) {
                    throw new IllegalArgumentException("INVALID_SOURCE_FINGERPRINT");
                }
            }
            this.recordId = recordId;
            this.deviceId = deviceId;
            this.kind = kind;
            this.startMs = startMs;
            this.endMs = endMs;
            this.value = value;
            this.stage = stage;
            this.timezone = timezone;
            this.sourceFingerprint = fingerprint;
            this.measurementMode = measurementMode;
            this.complete = complete;
        }

        public HealthRecord toRecord(int revision) {
            return new HealthRecord(recordId, deviceId, kind, startMs, endMs, value, stage,
                    revision, timezone, measurementMode, complete);
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("recordId", recordId);
                json.put("deviceId", deviceId);
                json.put("kind", kind);
                json.put("startMs", startMs);
                json.put("endMs", endMs);
                json.put("value", value == null ? JSONObject.NULL : value);
                json.put("stage", stage == null ? JSONObject.NULL : stage);
                if (timezone != null) json.put("timezone", timezone);
                json.put("sourceFingerprint", sourceFingerprint);
                json.put("measurementMode", measurementMode);
                json.put("complete", complete);
            } catch (JSONException e) {
                throw new IllegalStateException("MEASUREMENT_SERIALIZATION_FAILED", e);
            }
            return json;
        }

        public static Measurement fromJson(JSONObject json) throws JSONException {
            if (json == null) throw new IllegalArgumentException("INVALID_MEASUREMENT");
            for (Iterator<String> it = json.keys(); it.hasNext();) {
                if (!FIELDS.contains(it.next())) throw new IllegalArgumentException("UNKNOWN_MEASUREMENT_FIELD");
            }
            Object value = json.get("value");
            Object stage = json.get("stage");
            if (!(json.get("measurementMode") instanceof String)
                    || !(json.get("complete") instanceof Boolean)) {
                throw new IllegalArgumentException("INVALID_MEASUREMENT_VALUE");
            }
            if (value != JSONObject.NULL && !(value instanceof Number)
                    || stage != JSONObject.NULL && !(stage instanceof Integer)) {
                throw new IllegalArgumentException("INVALID_MEASUREMENT_VALUE");
            }
            Measurement result = new Measurement(json.getString("recordId"),
                    json.getString("deviceId"), json.getString("kind"), json.getLong("startMs"),
                    json.getLong("endMs"), value == JSONObject.NULL ? null : (Number) value,
                    stage == JSONObject.NULL ? null : (Integer) stage,
                    json.has("timezone") ? json.getString("timezone") : null,
                    json.getString("measurementMode"), json.getBoolean("complete"),
                    json.getString("sourceFingerprint"));
            result.toRecord(1); // Apply the same measurement validity rules as persisted records.
            return result;
        }
    }
}
