// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONObject;

/** Writes band sleep into OHealth tables 1010 and 1011. Another device's day is left alone. */
final class OHealthSleepWriter {
    static final int TABLE_SLEEP = 1010;
    static final int TABLE_STAT = 1011;
    static final int TABLE_BREATH_RATE = 1048;
    private static final int INSERT_CHUNK = 200;
    private static final String[] COLUMNS = {"recordId", "revision", "record"};
    private final OHealthHealthImportHook.HostContract host;
    private final OHealthSleepDerivedWriter derived;
    private final Class<?> sleepClass;
    private final Class<?> statClass;
    private final Class<?> breathClass;
    private final Constructor<?> sleepNew;
    private final Constructor<?> statNew;
    private final Constructor<?> breathNew;
    private final Method sleepAccount, sleepDevice, sleepStart, sleepEnd, sleepState, sleepDisplay, sleepVersion;
    private final Method sleepGetDevice, sleepGetStart, sleepGetEnd, sleepGetState, sleepGetVersion;
    private final Method statAccount, statDevice, statDate, statFall, statWake, statSleep, statDeep, statLight,
            statRem, statAwake;
    private final Method statGetDevice, statGetDate, statGetFall, statGetWake, statGetSleep,
            statGetDeep, statGetLight, statGetRem, statGetAwake;
    private final Method breathAccount, breathDevice, breathDeviceType, breathTime, breathValue, breathConfig;

    OHealthSleepWriter(OHealthHealthImportHook.HostContract host) throws ReflectiveOperationException {
        this.host = host;
        OHealthSleepDerivedWriter derivedWriter;
        try {
            derivedWriter = new OHealthSleepDerivedWriter(host);
        } catch (ReflectiveOperationException unavailable) {
            Log.i("OplusBandBridge", "OHEALTH_SLEEP_DERIVED_CONTRACT " + unavailable);
            derivedWriter = null;
        }
        this.derived = derivedWriter;
        ClassLoader loader = host.loader;
        sleepClass = Class.forName("com.heytap.databaseengine.model.Sleep", false, loader);
        statClass = Class.forName("com.heytap.databaseengine.model.SleepDataStat", false, loader);
        sleepNew = sleepClass.getConstructor();
        statNew = statClass.getConstructor();
        sleepAccount = sleepClass.getMethod("setSsoid", String.class);
        sleepDevice = sleepClass.getMethod("setDeviceUniqueId", String.class);
        sleepStart = sleepClass.getMethod("setStartTimestamp", long.class);
        sleepEnd = sleepClass.getMethod("setEndTimestamp", long.class);
        sleepState = sleepClass.getMethod("setSleepState", int.class);
        sleepDisplay = sleepClass.getMethod("setDisplay", int.class);
        sleepVersion = optional(sleepClass, "setDataVersion", int.class);
        sleepGetDevice = getter(sleepClass, "getDeviceUniqueId", String.class);
        sleepGetStart = getter(sleepClass, "getStartTimestamp", long.class);
        sleepGetEnd = getter(sleepClass, "getEndTimestamp", long.class);
        sleepGetState = getter(sleepClass, "getSleepState", int.class);
        sleepGetVersion = optional(sleepClass, "getDataVersion");
        statAccount = statClass.getMethod("setSsoid", String.class);
        statDevice = statClass.getMethod("setDeviceUniqueId", String.class);
        statDate = statClass.getMethod("setDate", int.class);
        statFall = statClass.getMethod("setFallAsleep", long.class);
        statWake = statClass.getMethod("setSleepOut", long.class);
        statSleep = statClass.getMethod("setTotalSleepTime", long.class);
        statDeep = statClass.getMethod("setTotalDeepSleepTime", long.class);
        statLight = statClass.getMethod("setTotalLightlySleepTime", long.class);
        statRem = statClass.getMethod("setTotalRemTime", long.class);
        statAwake = statClass.getMethod("setTotalWakeUpTime", long.class);
        statGetDevice = getter(statClass, "getDeviceUniqueId", String.class);
        statGetDate = getter(statClass, "getDate", int.class);
        statGetFall = getter(statClass, "getFallAsleep", long.class);
        statGetWake = getter(statClass, "getSleepOut", long.class);
        statGetSleep = getter(statClass, "getTotalSleepTime", long.class);
        statGetDeep = getter(statClass, "getTotalDeepSleepTime", long.class);
        statGetLight = getter(statClass, "getTotalLightlySleepTime", long.class);
        statGetRem = getter(statClass, "getTotalRemTime", long.class);
        statGetAwake = getter(statClass, "getTotalWakeUpTime", long.class);
        Class<?> bClass = null;
        Constructor<?> bNew = null;
        Method bAccount = null, bDevice = null, bDeviceType = null, bTime = null, bValue = null, bConfig = null;
        try {
            bClass = Class.forName("com.heytap.databaseengine.model.BreathRate", false, loader);
            bNew = bClass.getConstructor();
            bAccount = bClass.getMethod("setSsoid", String.class);
            bDevice = bClass.getMethod("setDeviceUniqueId", String.class);
            bDeviceType = optional(bClass, "setDeviceType", String.class);
            bTime = bClass.getMethod("setDataCreatedTimestamp", long.class);
            bValue = bClass.getMethod("setValue", Integer.class);
            bConfig = bClass.getMethod("setConfig", Integer.class);
        } catch (ReflectiveOperationException unavailable) {
            Log.i("OplusBandBridge", "OHEALTH_BREATH_RATE_CONTRACT " + unavailable);
            bClass = null;
            bNew = null;
            bAccount = null;
            bDevice = null;
            bDeviceType = null;
            bTime = null;
            bValue = null;
            bConfig = null;
        }
        breathClass = bClass;
        breathNew = bNew;
        breathAccount = bAccount;
        breathDevice = bDevice;
        breathDeviceType = bDeviceType;
        breathTime = bTime;
        breathValue = bValue;
        breathConfig = bConfig;
    }

    void write(Context context) throws Exception {
        String account = host.account();
        if (account == null || account.isBlank()) return;
        Object api = host.api();
        if (api == null) throw new IllegalStateException("SLEEP_IMPORT_NOT_READY");
        Bundle band = OHealthDeviceHook.registeredSnapshot();
        if (band == null || band.getString("deviceId", "").isBlank()) {
            throw new IllegalStateException("SLEEP_DEVICE_NOT_READY");
        }
        String queued = band.getString("deviceId", "");
        String device = OHealthDeviceHook.healthId(queued);
        List<HealthRecord> records = history(context, account, queued);
        List<OHealthSleepPlan.Night> nights = OHealthSleepPlan.nights(records);
        if (nights.isEmpty()) return;
        List<OHealthSleepPlan.Day> days = OHealthSleepPlan.days(nights);
        if (days.isEmpty()) return;
        Log.i("OplusBandBridge", "OHEALTH_SLEEP_BEGIN days=" + days.size() + " nights=" + nights.size());
        int inserted = 0;
        int skipped = 0;
        int held = 0;
        int failed = 0;
        for (OHealthSleepPlan.Day day : days) {
            if (!account.equals(host.account())) throw new SecurityException("IMPORT_ACCOUNT_CHANGED");
            try {
                String skip = writeDay(context, api, account, device, queued, day);
                if (skip == null) inserted++;
                else {
                    skipped++;
                    String line = "OHEALTH_SLEEP_DAY_SKIPPED date=" + day.date() + " reason=" + skip;
                    Log.i("OplusBandBridge", line);
                    OHealthDeviceHook.traceLine(context, line);
                }
            } catch (SecurityException paused) {
                throw paused;
            } catch (IllegalStateException heldDay) {
                String reason = heldDay.getMessage();
                if (reason != null && reason.startsWith("SLEEP_SEGMENT_UNCONFIRMED")) {
                    held++;
                    Log.i("OplusBandBridge", "OHEALTH_SLEEP_DAY_HELD date=" + day.date() + " " + reason);
                } else {
                    failed++;
                    String line = "OHEALTH_SLEEP_DAY_FAILED date=" + day.date() + " reason=" + reason;
                    Log.i("OplusBandBridge", line);
                    OHealthDeviceHook.traceLine(context, line);
                }
            } catch (Exception | LinkageError failedDay) {
                failed++;
                String line = "OHEALTH_SLEEP_DAY_FAILED date=" + day.date() + " "
                        + failedDay.getClass().getSimpleName()
                        + (failedDay.getMessage() == null ? "" : " " + failedDay.getMessage());
                Log.i("OplusBandBridge", line);
                OHealthDeviceHook.traceLine(context, line);
            }
        }
        Log.i("OplusBandBridge", "OHEALTH_SLEEP_IMPORT days=" + days.size()
                + " inserted=" + inserted + " skipped=" + skipped + " held=" + held + " failed=" + failed);
    }

    /** @return null when the day was written; otherwise {@code other-device} or {@code already-ours}. */
    private String writeDay(Context context, Object api, String account, String device,
            String queued, OHealthSleepPlan.Day day) throws Exception {
        long statStart = day.dayStartMs();
        long statEnd = day.dayEndMs() + 1;
        String previous = previousDevice();
        List<?> stats = host.readRows(api, account, TABLE_STAT, null, statStart, statEnd, 4, false);
        if (ownedByOther(stats, day.date(), device, previous)) return "other-device";
        List<?> dayStats = null;
        if (derived != null) {
            dayStats = host.readRows(api, account, OHealthSleepDerivedWriter.TABLE_DAY_STAT, null,
                    statStart, statEnd, 0, false);
            if (derived.dayOwnedByOther(dayStats, day.date(), device, previous)) return "other-device";
        }

        if (!queued.equals(device)) {
            for (OHealthSleepPlan.Night night : day.nights()) {
                try {
                    host.deleteRows(api, TABLE_SLEEP, account, queued, night.fallAsleepMs(), night.wakeMs());
                } catch (Exception ignored) {
                    Log.i("OplusBandBridge", "OHEALTH_SLEEP_PREVIOUS_DEVICE_KEPT date=" + day.date());
                }
                if (breathClass != null) {
                    try {
                        host.deleteRows(api, TABLE_BREATH_RATE, account, queued, night.fallAsleepMs(), night.wakeMs());
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        OHealthSleepPlan.Night hrSession = day.mainSession() != null ? day.mainSession()
                : day.nights().stream().max(Comparator.comparingLong(OHealthSleepPlan.Night::sleepMinutes)).orElse(null);

        boolean anyUpdated = false;
        for (OHealthSleepPlan.Night night : day.nights()) {
            boolean segmentsUpdated = writeSessionSegments(api, account, device, night);
            if (segmentsUpdated) anyUpdated = true;

            if (derived != null || breathClass != null) {
                try {
                    boolean hasBreath = breathClass != null && hasBreathRecords(context, account, queued, night);
                    boolean needBreath = hasBreath && !breathRatePresent(api, account, device, night);
                    boolean needIndex = derived != null && !indexPresent(api, account, device, night, hasBreath);
                    boolean writeHrStat = (night == hrSession);
                    boolean needHr = writeHrStat && derived != null && !hrStatPresent(api, account, day.date(), statStart, statEnd);
                    if (segmentsUpdated || needBreath || needIndex || needHr) {
                        writeDerived(api, account, device, night, context, queued, writeHrStat);
                        anyUpdated = true;
                    }
                } catch (Exception derivedFailure) {
                    Log.i("OplusBandBridge", "OHEALTH_SLEEP_DERIVED_FAILED date=" + day.date()
                            + " " + derivedFailure);
                }
            }
        }

        boolean sameStat = statMatches(stats, device, previous, day);
        if (!sameStat) {
            host.insertRows(api, TABLE_STAT, List.of(statRow(account, device, day)));
            List<?> written = host.readRows(api, account, TABLE_STAT, null, statStart, statEnd, 4, false);
            if (!statMatches(written, device, previous, day)) {
                throw new IllegalStateException("SLEEP_STAT_UNCONFIRMED_" + written.size());
            }
            anyUpdated = true;
        }

        if (derived != null) {
            try {
                boolean sameDayStat = derived.dayStatMatches(dayStats, device, previous, day);
                if (!sameDayStat) {
                    derived.writeDayStat(api, account, device, day);
                    List<?> writtenDay = host.readRows(api, account, OHealthSleepDerivedWriter.TABLE_DAY_STAT,
                            null, statStart, statEnd, 0, false);
                    if (!derived.dayStatMatches(writtenDay, device, previous, day)) {
                        Log.w("OplusBandBridge", "OHEALTH_SLEEP_DAY_STAT_UNCONFIRMED date=" + day.date()
                                + " rows=" + (writtenDay == null ? 0 : writtenDay.size()));
                    }
                    anyUpdated = true;
                }
            } catch (Exception derivedFailure) {
                Log.i("OplusBandBridge", "OHEALTH_SLEEP_DERIVED_FAILED date=" + day.date()
                        + " " + derivedFailure);
            }
        }

        if (!anyUpdated) return "already-ours";
        return null;
    }

    private boolean writeSessionSegments(Object api, String account, String device,
            OHealthSleepPlan.Night night) throws Exception {
        List<?> existing = host.readRows(api, account, TABLE_SLEEP, device, night.dayStartMs(),
                night.dayEndMs(), 0, true);
        List<OHealthSleepPlan.Segment> segments = minuteRows(night.segments());
        if (segmentsMatch(existing, device, segments, night)) return false;
        // Delete only this session. A nap later the same day stays in the table.
        host.deleteRows(api, TABLE_SLEEP, account, device, night.fallAsleepMs(), night.wakeMs());
        if (breathClass != null) {
            try {
                host.deleteRows(api, TABLE_BREATH_RATE, account, device, night.fallAsleepMs(), night.wakeMs());
            } catch (Exception ignored) {
            }
        }
        insertSegments(api, account, device, segments);
        existing = host.readRows(api, account, TABLE_SLEEP, device, night.dayStartMs(),
                night.dayEndMs(), 0, true);
        if (!segmentsMatch(existing, device, segments, night)) {
            boolean[] kept = new boolean[segments.size()];
            for (Object row : existing) {
                if (!overlapsSession(row, device, night)) continue;
                int match = unusedSegment(row, device, segments, kept);
                if (match >= 0) {
                    kept[match] = true;
                    continue;
                }
                long rowStart = (Long) sleepGetStart.invoke(row);
                host.deleteRows(api, TABLE_SLEEP, account, device, rowStart, rowStart + 1);
            }
            List<OHealthSleepPlan.Segment> missing = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                if (!kept[i]) missing.add(segments.get(i));
            }
            insertSegments(api, account, device, missing);
            existing = host.readRows(api, account, TABLE_SLEEP, device, night.dayStartMs(),
                    night.dayEndMs(), 0, true);
            if (!segmentsMatch(existing, device, segments, night)) {
                throw new IllegalStateException("SLEEP_SEGMENT_UNCONFIRMED rows=" + existing.size()
                        + " want=" + segments.size());
            }
        }
        return true;
    }

    private List<HealthRecord> history(Context context, String account, String device) throws Exception {
        List<HealthRecord> records = new ArrayList<>();
        for (String kind : new String[] {"sleep_interval", "sleep_stage"}) {
            String after = null;
            for (;;) {
                Uri uri = after == null ? HealthQueueProvider.RECORDS_URI
                        : HealthQueueProvider.RECORDS_URI.buildUpon().appendQueryParameter("after", after).build();
                int count = 0;
                try (Cursor rows = context.getContentResolver().query(uri, COLUMNS,
                        "account=? AND deviceId=? AND kind=? AND startMs<? AND endMs>?",
                        new String[] {account, device, kind, Long.toString(Long.MAX_VALUE), "0"}, null)) {
                    if (rows == null) throw new IllegalStateException("SLEEP_HISTORY_UNAVAILABLE");
                    while (rows.moveToNext()) {
                        records.add(HealthRecord.fromJson(new JSONObject(rows.getString(2))));
                        after = rows.getString(0);
                        count++;
                    }
                }
                if (count < 200) break;
            }
        }
        return records;
    }

    /** Band measurements of one kind inside [start, end). */
    private List<HealthRecord> window(Context context, String account, String device, String kind,
            long start, long end) throws Exception {
        List<HealthRecord> records = new ArrayList<>();
        String after = null;
        for (;;) {
            Uri uri = after == null ? HealthQueueProvider.RECORDS_URI
                    : HealthQueueProvider.RECORDS_URI.buildUpon().appendQueryParameter("after", after).build();
            int count = 0;
            try (Cursor rows = context.getContentResolver().query(uri, COLUMNS,
                    "account=? AND deviceId=? AND kind=? AND startMs<? AND endMs>?",
                    new String[] {account, device, kind, Long.toString(end), Long.toString(start)}, null)) {
                if (rows == null) return records;
                while (rows.moveToNext()) {
                    records.add(HealthRecord.fromJson(new JSONObject(rows.getString(2))));
                    after = rows.getString(0);
                    count++;
                }
            }
            if (count < 200) break;
        }
        return records;
    }

    private void writeDerived(Object api, String account, String device, OHealthSleepPlan.Night night,
            Context context, String queued, boolean writeHrStat) throws Exception {
        long start = night.fallAsleepMs();
        long end = night.wakeMs();
        OHealthSleepDerivedWriter.Summary heart = summarize(context, account, queued, "heart_rate",
                start, end, 40, 220);
        OHealthSleepDerivedWriter.Summary spo2 = summarize(context, account, queued, "spo2",
                start, end, 60, 100);
        List<HealthRecord> breathRecords = window(context, account, queued, "sleep_breath", start, end);
        OHealthSleepDerivedWriter.Summary breath = summarizeRecords(breathRecords, 60, 500);
        if (!breathRatePresent(api, account, device, night)) {
            writeBreathRate(api, account, device, breathRecords);
        }
        if (derived != null) {
            derived.write(api, account, device, night, heart, spo2, breath, writeHrStat);
        }
    }

    private void writeBreathRate(Object api, String account, String device,
            List<HealthRecord> records) throws Exception {
        if (breathClass == null || records.isEmpty()) return;
        List<Object> rows = new ArrayList<>();
        for (HealthRecord record : records) {
            if (record.value == null) continue;
            int val = record.value.intValue();
            if (val < 60 || val > 500) continue;
            Object row = breathNew.newInstance();
            breathAccount.invoke(row, account);
            if (breathDeviceType != null) {
                breathDeviceType.invoke(row, "Band");
            }
            breathDevice.invoke(row, device);
            breathTime.invoke(row, record.startMs);
            breathValue.invoke(row, Integer.valueOf(val));
            breathConfig.invoke(row, Integer.valueOf(0));
            rows.add(row);
        }
        for (int from = 0; from < rows.size(); from += INSERT_CHUNK) {
            host.insertRows(api, TABLE_BREATH_RATE, rows.subList(from, Math.min(rows.size(), from + INSERT_CHUNK)));
        }
    }

    private boolean breathRatePresent(Object api, String account, String device,
            OHealthSleepPlan.Night night) {
        if (breathClass == null) return true;
        try {
            return !host.readRows(api, account, TABLE_BREATH_RATE, device,
                    night.fallAsleepMs(), night.wakeMs(), 0, false, 1).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasBreathRecords(Context context, String account, String device,
            OHealthSleepPlan.Night night) {
        try (Cursor rows = context.getContentResolver().query(HealthQueueProvider.RECORDS_URI, COLUMNS,
                "account=? AND deviceId=? AND kind=? AND startMs<? AND endMs>?",
                new String[] {account, device, "sleep_breath",
                        Long.toString(night.wakeMs()), Long.toString(night.fallAsleepMs())}, null)) {
            return rows != null && rows.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean indexPresent(Object api, String account, String device,
            OHealthSleepPlan.Night night, boolean expectBreath) throws Exception {
        if (derived == null) return true;
        List<?> rows = host.readRows(api, account, OHealthSleepDerivedWriter.TABLE_INDEX, device,
                night.fallAsleepMs(), night.wakeMs(), 0, false);
        if (rows.isEmpty()) return false;
        if (expectBreath && !derived.indexHasBreath(rows)) return false;
        return true;
    }

    private boolean hrStatPresent(Object api, String account, int date, long start, long end)
            throws Exception {
        if (derived == null) return true;
        List<?> rows = host.readRows(api, account, OHealthSleepDerivedWriter.TABLE_HR_STAT, null,
                start, end, 0, false);
        return derived.hrStatMatches(rows, date);
    }

    private OHealthSleepDerivedWriter.Summary summarize(Context context, String account, String device,
            String kind, long start, long end, int low, int high) throws Exception {
        List<HealthRecord> records = window(context, account, device, kind, start, end);
        return summarizeRecords(records, low, high);
    }

    private OHealthSleepDerivedWriter.Summary summarizeRecords(List<HealthRecord> records,
            int low, int high) {
        int[] values = new int[records.size()];
        int used = 0;
        for (HealthRecord record : records) {
            if (record.value == null) continue;
            int value = record.value.intValue();
            if (value < low || value > high) continue;
            values[used++] = value;
        }
        return OHealthSleepDerivedWriter.summarize(values, used);
    }

    private Object segmentRow(String account, String device, OHealthSleepPlan.Segment segment)
            throws ReflectiveOperationException {
        Object row = sleepNew.newInstance();
        sleepAccount.invoke(row, account);
        sleepDevice.invoke(row, device);
        sleepStart.invoke(row, segment.startMs());
        sleepEnd.invoke(row, segment.endMs());
        sleepState.invoke(row, segment.sleepState());
        sleepDisplay.invoke(row, 1);
        // Version 11 is the watch rule: a gap over 20 minutes stays a separate nap.
        if (sleepVersion != null) sleepVersion.invoke(row, 11);
        return row;
    }

    private void insertSegments(Object api, String account, String device,
            List<OHealthSleepPlan.Segment> segments) throws ReflectiveOperationException {
        if (segments.isEmpty()) return;
        List<Object> rows = new ArrayList<>();
        for (OHealthSleepPlan.Segment segment : segments) rows.add(segmentRow(account, device, segment));
        host.insertRows(api, TABLE_SLEEP, rows);
    }

    private int unusedSegment(Object row, String device, List<OHealthSleepPlan.Segment> segments, boolean[] used)
            throws ReflectiveOperationException {
        for (int i = 0; i < segments.size(); i++) {
            if (used[i] || !sameSegment(row, device, segments.get(i))) continue;
            return i;
        }
        return -1;
    }


    private Object statRow(String account, String device, OHealthSleepPlan.Day day)
            throws ReflectiveOperationException {
        Object row = statNew.newInstance();
        statAccount.invoke(row, account);
        statDevice.invoke(row, device);
        statDate.invoke(row, day.date());
        // The host stores the summary clock as sleep-day minutes, not the raw timestamps.
        statFall.invoke(row, OHealthSleepPlan.sleepDayMinutes(day.fallAsleepMs(), day.zone()));
        statWake.invoke(row, OHealthSleepPlan.sleepDayMinutes(day.wakeMs(), day.zone()));
        statSleep.invoke(row, day.sleepMinutes());
        statDeep.invoke(row, day.deepMinutes());
        statLight.invoke(row, day.lightMinutes());
        statRem.invoke(row, day.remMinutes());
        statAwake.invoke(row, day.wakeMinutes());
        return row;
    }

    /**
     * OHealth stores one {@code DBSleep} row per minute. Its device writer sets
     * {@code endTimestamp = start + TIME_ONE_MINUTE} and the sleep-score pass indexes the stage
     * array by each row's start minute, so a bar that spans several minutes must be split into
     * one-minute rows. Writing a whole stage bar as a single row leaves the neighbouring minutes at
     * the default wake state, the score pass sees almost no sleep, and the night scores zero.
     */
    static List<OHealthSleepPlan.Segment> minuteRows(List<OHealthSleepPlan.Segment> segments) {
        List<OHealthSleepPlan.Segment> rows = new ArrayList<>();
        for (OHealthSleepPlan.Segment segment : segments) {
            long start = segment.startMs();
            long end = segment.endMs();
            if (end <= start) continue;
            while (start < end) {
                long next = Math.min(start + 60_000L, end);
                rows.add(new OHealthSleepPlan.Segment(start, next, segment.sleepState()));
                start = next;
            }
        }
        return rows;
    }

    private boolean segmentsMatch(List<?> rows, String device, List<OHealthSleepPlan.Segment> segments,
            OHealthSleepPlan.Night night) throws ReflectiveOperationException {
        boolean[] used = new boolean[rows.size()];
        for (OHealthSleepPlan.Segment segment : segments) {
            boolean found = false;
            for (int i = 0; i < rows.size(); i++) {
                if (used[i] || !sameSegment(rows.get(i), device, segment)) continue;
                used[i] = true;
                found = true;
                break;
            }
            if (!found) return false;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (used[i] || !overlapsSession(rows.get(i), device, night)) continue;
            return false;
        }
        return true;
    }

    private boolean sameSegment(Object row, String device, OHealthSleepPlan.Segment segment)
            throws ReflectiveOperationException {
        if (!sleepClass.isInstance(row) || !device.equals(sleepGetDevice.invoke(row))) return false;
        if (segment.startMs() != (Long) sleepGetStart.invoke(row)
                || segment.endMs() != (Long) sleepGetEnd.invoke(row)
                || segment.sleepState() != (Integer) sleepGetState.invoke(row)) return false;
        if (sleepGetVersion == null) return true;
        return Integer.valueOf(11).equals(sleepGetVersion.invoke(row));
    }

    /** A row that meets this session. Another session the same day is left in place. */
    private boolean overlapsSession(Object row, String device, OHealthSleepPlan.Night night)
            throws ReflectiveOperationException {
        if (!sleepClass.isInstance(row) || !device.equals(sleepGetDevice.invoke(row))) return false;
        long start = (Long) sleepGetStart.invoke(row);
        long end = (Long) sleepGetEnd.invoke(row);
        return start < night.wakeMs() && end > night.fallAsleepMs();
    }

    private boolean statMatches(List<?> rows, String device, String previous, OHealthSleepPlan.Day day)
            throws ReflectiveOperationException {
        for (Object row : rows) {
            if (!statClass.isInstance(row) || day.date() != (Integer) statGetDate.invoke(row)) continue;
            String owner = (String) statGetDevice.invoke(row);
            // Rows written under the old queue id still belong to this band.
            if (owner != null && !owner.isBlank() && !device.equals(owner) && !owner.equals(previous)) continue;
            return OHealthSleepPlan.sleepDayMinutes(day.fallAsleepMs(), day.zone())
                            == (Long) statGetFall.invoke(row)
                    && OHealthSleepPlan.sleepDayMinutes(day.wakeMs(), day.zone())
                            == (Long) statGetWake.invoke(row)
                    && day.sleepMinutes() == (Long) statGetSleep.invoke(row)
                    && day.deepMinutes() == (Long) statGetDeep.invoke(row)
                    && day.lightMinutes() == (Long) statGetLight.invoke(row)
                    && day.remMinutes() == (Long) statGetRem.invoke(row)
                    && day.wakeMinutes() == (Long) statGetAwake.invoke(row);
        }
        return false;
    }

    /** The queue id used before rows were stored under the Bluetooth MAC. */
    private static String previousDevice() {
        android.os.Bundle band = OHealthDeviceHook.registeredSnapshot();
        if (band == null) return "";
        String id = band.getString("deviceId", "");
        String mac = band.getString("mac", "");
        return id.equals(mac) ? "" : id;
    }

    private boolean ownedByOther(List<?> rows, int date, String device, String previous)
            throws ReflectiveOperationException {
        for (Object row : rows) {
            if (!statClass.isInstance(row) || date != (Integer) statGetDate.invoke(row)) continue;
            String owner = (String) statGetDevice.invoke(row);
            if (owner == null || owner.isBlank() || device.equals(owner) || owner.equals(previous)) continue;
            return true;
        }
        return false;
    }

    private static Method optional(Class<?> type, String name, Class<?>... parameters) {
        try { return type.getMethod(name, parameters); }
        catch (NoSuchMethodException missing) { return null; }
    }

    private static Method getter(Class<?> type, String name, Class<?> returnType) throws NoSuchMethodException {
        Method method = type.getMethod(name);
        if (method.getReturnType() != returnType) throw new NoSuchMethodException("SLEEP_MODEL_CONTRACT");
        return method;
    }
}
