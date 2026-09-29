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
    private final Method sleepAccount, sleepDevice, sleepStart, sleepEnd, sleepState, sleepType, sleepDisplay;
    private final Method sleepGetDevice, sleepGetStart, sleepGetEnd, sleepGetState;
    private final Method statAccount, statDevice, statDate, statFall, statWake, statSleep, statDeep, statLight,
            statRem, statAwake;
    private final Method statGetDevice, statGetDate, statGetFall, statGetWake, statGetSleep;

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
        sleepType = sleepClass.getMethod("setSleepType", int.class);
        sleepDisplay = sleepClass.getMethod("setDisplay", int.class);
        sleepGetDevice = getter(sleepClass, "getDeviceUniqueId", String.class);
        sleepGetStart = getter(sleepClass, "getStartTimestamp", long.class);
        sleepGetEnd = getter(sleepClass, "getEndTimestamp", long.class);
        sleepGetState = getter(sleepClass, "getSleepState", int.class);
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
        String device = band.getString("deviceId", "");
        List<HealthRecord> records = history(context, account, device);
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
            try {
                if (writeNight(api, account, device, night)) inserted++;
                else skipped++;
            } catch (IllegalStateException heldNight) {
                String reason = heldNight.getMessage();
                if (reason == null || !reason.startsWith("SLEEP_SEGMENT_UNCONFIRMED")) throw heldNight;
                held++;
                Log.i("OplusBandBridge", "OHEALTH_SLEEP_NIGHT_HELD date=" + night.date());
            }
        }
        Log.i("OplusBandBridge", "OHEALTH_SLEEP_IMPORT nights=" + nights.size()
                + " inserted=" + inserted + " skipped=" + skipped + " held=" + held);
    }

    /** @return false when this date already belongs to another device or already has our stat. */
    private boolean writeNight(Object api, String account, String device, OHealthSleepPlan.Night night)
            throws Exception {
        long statStart = night.fallAsleepMs() - 86_400_000L;
        long statEnd = night.wakeMs() + 3_600_000L;
        List<?> stats = host.readRows(api, account, TABLE_STAT, null, statStart, statEnd, 4, false);
        if (ownedByOther(stats, night.date(), device)) return false;
        List<?> existing = host.readRows(api, account, TABLE_SLEEP, device, night.fallAsleepMs() - 1,
                night.wakeMs() + 1, 0, true);
        List<Object> missing = new ArrayList<>();
        for (OHealthSleepPlan.Segment segment : night.segments()) {
            if (!hasSegment(existing, device, segment)) missing.add(segmentRow(account, device, segment));
        }
        if (!missing.isEmpty()) {
            host.insertRows(api, TABLE_SLEEP, missing);
            existing = host.readRows(api, account, TABLE_SLEEP, device, night.fallAsleepMs() - 1,
                    night.wakeMs() + 1, 0, true);
            for (OHealthSleepPlan.Segment segment : night.segments()) {
                if (!hasSegment(existing, device, segment)) {
                    throw new IllegalStateException("SLEEP_SEGMENT_UNCONFIRMED");
                }
            }
        }
        if (hasStat(stats, device, night)) return false;
        host.insertRows(api, TABLE_STAT, List.of(statRow(account, device, night)));
        List<?> written = host.readRows(api, account, TABLE_STAT, device, statStart, statEnd, 4, false);
        if (!hasStat(written, device, night)) {
            throw new IllegalStateException("SLEEP_STAT_UNCONFIRMED_" + written.size());
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

    private Object segmentRow(String account, String device, OHealthSleepPlan.Segment segment)
            throws ReflectiveOperationException {
        Object row = sleepNew.newInstance();
        sleepAccount.invoke(row, account);
        sleepDevice.invoke(row, device);
        sleepStart.invoke(row, segment.startMs());
        sleepEnd.invoke(row, segment.endMs());
        sleepState.invoke(row, segment.sleepState());
        sleepType.invoke(row, chartType(segment.sleepState()));
        sleepDisplay.invoke(row, 1);
        return row;
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

    private boolean hasSegment(List<?> rows, String device, OHealthSleepPlan.Segment segment)
            throws ReflectiveOperationException {
        for (Object row : rows) {
            if (!sleepClass.isInstance(row)) continue;
            if (!device.equals(sleepGetDevice.invoke(row))) continue;
            if (segment.startMs() != (Long) sleepGetStart.invoke(row)) continue;
            if (segment.sleepState() != (Integer) sleepGetState.invoke(row)) continue;
            // SleepMerge keeps the previous end when this start minute already exists.
            if ((Long) sleepGetEnd.invoke(row) > segment.startMs()) return true;
        }
        return false;
    }

    private boolean hasStat(List<?> rows, String device, OHealthSleepPlan.Night night)
            throws ReflectiveOperationException {
        for (Object row : rows) {
            if (!statClass.isInstance(row) || night.date() != (Integer) statGetDate.invoke(row)) continue;
            String owner = (String) statGetDevice.invoke(row);
            if (owner == null || owner.isBlank() || device.equals(owner)) return true;
        }
        return false;
    }

    private boolean ownedByOther(List<?> rows, int date, String device) throws ReflectiveOperationException {
        for (Object row : rows) {
            if (!statClass.isInstance(row) || date != (Integer) statGetDate.invoke(row)) continue;
            String owner = (String) statGetDevice.invoke(row);
            if (owner != null && !owner.isBlank() && !device.equals(owner)) return true;
        }
        return false;
    }

    private static int chartType(int sleepState) {
        return switch (sleepState) {
            case OHealthSleepPlan.DEEP -> 1;
            case OHealthSleepPlan.REM -> 3;
            case OHealthSleepPlan.LIGHT -> 2;
            case OHealthSleepPlan.AWAKE -> 4;
            default -> throw new IllegalArgumentException("SLEEP_STATE_UNMAPPED");
        };
    }

    private static Method getter(Class<?> type, String name, Class<?> returnType) throws NoSuchMethodException {
        Method method = type.getMethod(name);
        if (method.getReturnType() != returnType) throw new NoSuchMethodException("SLEEP_MODEL_CONTRACT");
        return method;
    }
}
