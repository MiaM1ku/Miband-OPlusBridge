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
    private static final String[] COLUMNS = {"recordId", "revision", "record"};
    private final OHealthHealthImportHook.HostContract host;
    private final Class<?> sleepClass;
    private final Class<?> statClass;
    private final Constructor<?> sleepNew;
    private final Constructor<?> statNew;
    private final Method sleepAccount, sleepDevice, sleepStart, sleepEnd, sleepState, sleepDisplay, sleepVersion;
    private final Method sleepGetDevice, sleepGetStart, sleepGetEnd, sleepGetState, sleepGetVersion;
    private final Method statAccount, statDevice, statDate, statFall, statWake, statSleep, statDeep, statLight,
            statRem, statAwake;
    private final Method statGetDevice, statGetDate, statGetFall, statGetWake, statGetSleep,
            statGetDeep, statGetLight, statGetRem, statGetAwake;

    OHealthSleepWriter(OHealthHealthImportHook.HostContract host) throws ReflectiveOperationException {
        this.host = host;
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
        nights.sort(Comparator.comparingInt(OHealthSleepPlan.Night::date)
                .thenComparing(Comparator.comparingLong(OHealthSleepPlan.Night::sleepMinutes).reversed())
                .thenComparingLong(OHealthSleepPlan.Night::fallAsleepMs));
        Log.i("OplusBandBridge", "OHEALTH_SLEEP_BEGIN nights=" + nights.size());
        int inserted = 0;
        int skipped = 0;
        int held = 0;
        for (OHealthSleepPlan.Night night : nights) {
            if (!account.equals(host.account())) throw new SecurityException("IMPORT_ACCOUNT_CHANGED");
            if (!queued.equals(device)) {
                try {
                    host.deleteRows(api, TABLE_SLEEP, account, queued, night.fallAsleepMs(), night.wakeMs());
                } catch (RuntimeException ignored) {
                    Log.i("OplusBandBridge", "OHEALTH_SLEEP_PREVIOUS_DEVICE_KEPT date=" + night.date());
                }
            }
            try {
                String skip = writeNight(api, account, device, night,
                        OHealthSleepPlan.summary(nights, night));
                if (skip == null) inserted++;
                else {
                    skipped++;
                    String line = "OHEALTH_SLEEP_NIGHT_SKIPPED date=" + night.date() + " reason=" + skip;
                    Log.i("OplusBandBridge", line);
                    OHealthDeviceHook.traceLine(context, line);
                }
            } catch (IllegalStateException heldNight) {
                String reason = heldNight.getMessage();
                if (reason == null || !reason.startsWith("SLEEP_SEGMENT_UNCONFIRMED")) throw heldNight;
                held++;
                Log.i("OplusBandBridge", "OHEALTH_SLEEP_NIGHT_HELD date=" + night.date() + " " + reason);
            }
        }
        Log.i("OplusBandBridge", "OHEALTH_SLEEP_IMPORT nights=" + nights.size()
                + " inserted=" + inserted + " skipped=" + skipped + " held=" + held);
    }

    /** @return null when the night was written; otherwise {@code other-device} or {@code already-ours}. */
    private String writeNight(Object api, String account, String device, OHealthSleepPlan.Night night,
            boolean summary) throws Exception {
        long statStart = night.dayStartMs();
        long statEnd = night.dayEndMs() + 1;
        List<?> stats = host.readRows(api, account, TABLE_STAT, null, statStart, statEnd, 4, false);
        if (ownedByOther(stats, night.date(), device, previousDevice())) return "other-device";
        List<?> existing = host.readRows(api, account, TABLE_SLEEP, device, night.dayStartMs(),
                night.dayEndMs(), 0, true);
        boolean sameSegments = segmentsMatch(existing, device, night);
        boolean sameStat = !summary || statMatches(stats, device, night);
        if (sameSegments && sameStat) return "already-ours";
        if (!sameSegments) {
            // Delete only this session. A nap later the same day stays in the table.
            host.deleteRows(api, TABLE_SLEEP, account, device, night.fallAsleepMs(), night.wakeMs());
            insertSegments(api, account, device, night.segments());
            existing = host.readRows(api, account, TABLE_SLEEP, device, night.dayStartMs(),
                    night.dayEndMs(), 0, true);
            if (!segmentsMatch(existing, device, night)) {
                boolean[] kept = new boolean[night.segments().size()];
                List<OHealthSleepPlan.Segment> segments = night.segments();
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
                if (!segmentsMatch(existing, device, night)) {
                    throw new IllegalStateException("SLEEP_SEGMENT_UNCONFIRMED rows=" + existing.size()
                            + " want=" + segments.size());
                }
            }
        }
        if (summary && !statMatches(stats, device, night)) {
            // SleepDataStat is keyed by account and date, so a later insert replaces the frozen summary.
            host.insertRows(api, TABLE_STAT, List.of(statRow(account, device, night)));
            List<?> written = host.readRows(api, account, TABLE_STAT, device, statStart, statEnd, 4, false);
            if (!statMatches(written, device, night)) {
                throw new IllegalStateException("SLEEP_STAT_UNCONFIRMED_" + written.size());
            }
        }
        return null;
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


    private Object statRow(String account, String device, OHealthSleepPlan.Night night)
            throws ReflectiveOperationException {
        Object row = statNew.newInstance();
        statAccount.invoke(row, account);
        statDevice.invoke(row, device);
        statDate.invoke(row, night.date());
        statFall.invoke(row, night.fallAsleepMs());
        statWake.invoke(row, night.wakeMs());
        statSleep.invoke(row, night.sleepMinutes());
        statDeep.invoke(row, night.deepMinutes());
        statLight.invoke(row, night.lightMinutes());
        statRem.invoke(row, night.remMinutes());
        statAwake.invoke(row, night.wakeMinutes());
        return row;
    }

    private boolean segmentsMatch(List<?> rows, String device, OHealthSleepPlan.Night night)
            throws ReflectiveOperationException {
        boolean[] used = new boolean[rows.size()];
        for (OHealthSleepPlan.Segment segment : night.segments()) {
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

    private boolean statMatches(List<?> rows, String device, OHealthSleepPlan.Night night)
            throws ReflectiveOperationException {
        for (Object row : rows) {
            if (!statClass.isInstance(row) || night.date() != (Integer) statGetDate.invoke(row)) continue;
            String owner = (String) statGetDevice.invoke(row);
            if (owner != null && !owner.isBlank() && !device.equals(owner)) continue;
            return night.fallAsleepMs() == (Long) statGetFall.invoke(row)
                    && night.wakeMs() == (Long) statGetWake.invoke(row)
                    && night.sleepMinutes() == (Long) statGetSleep.invoke(row)
                    && night.deepMinutes() == (Long) statGetDeep.invoke(row)
                    && night.lightMinutes() == (Long) statGetLight.invoke(row)
                    && night.remMinutes() == (Long) statGetRem.invoke(row)
                    && night.wakeMinutes() == (Long) statGetAwake.invoke(row);
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
