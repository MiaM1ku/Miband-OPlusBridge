// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Writes one daily step total per day into OHealth table 1002, and hourly bars into table 1001. */
final class OHealthStepWriter {
    static final int TABLE_STAT = 1002;
    static final int TABLE_DETAIL = 1001;
    /** Phone step import uses this mode for a day total. The device id keeps it off the phone. */
    static final int DAY_STEP_MODE = -2;
    private static final String[] COLUMNS = {"recordId", "revision", "record"};
    private final OHealthHealthImportHook.HostContract host;
    private final Class<?> statClass;
    private final Constructor<?> statNew;
    private final Method setAccount, setDevice, setDate, setMode, setSteps, setTimezone;
    private final Method setCalories, setDistance, setMoveAbout;
    private final Method getDevice, getDate, getMode, getSteps, getCalories, getMoveAbout;
    private boolean detailResolved;
    private Class<?> detailClass;
    private Constructor<?> detailNew;
    private Method detailAccount, detailDevice, detailStart, detailEnd, detailSteps, detailCalories;
    private Method detailDistance, detailMode, detailDisplay, detailType, detailZone;
    private Method detailGetStart, detailGetSteps, detailGetDevice;

    OHealthStepWriter(OHealthHealthImportHook.HostContract host) throws ReflectiveOperationException {
        this.host = host;
        statClass = Class.forName("com.heytap.databaseengine.model.SportDataStat", false, host.loader);
        statNew = statClass.getConstructor();
        setAccount = statClass.getMethod("setSsoid", String.class);
        setDevice = statClass.getMethod("setDeviceUniqueId", String.class);
        setDate = statClass.getMethod("setDate", int.class);
        setMode = statClass.getMethod("setSportMode", int.class);
        setSteps = statClass.getMethod("setTotalSteps", int.class);
        setTimezone = statClass.getMethod("setTimezone", String.class);
        setCalories = statClass.getMethod("setTotalCalories", long.class);
        setDistance = statClass.getMethod("setTotalDistance", int.class);
        setMoveAbout = statClass.getMethod("setTotalMoveAboutTimes", int.class);
        getDevice = getter(statClass, "getDeviceUniqueId", String.class);
        getDate = getter(statClass, "getDate", int.class);
        getMode = getter(statClass, "getSportMode", int.class);
        getSteps = getter(statClass, "getTotalSteps", int.class);
        getCalories = getter(statClass, "getTotalCalories", long.class);
        getMoveAbout = getter(statClass, "getTotalMoveAboutTimes", int.class);
    }

    void write(Context context) throws Exception {
        String account = host.account();
        if (account == null || account.isBlank()) return;
        Object api = host.api();
        if (api == null) throw new IllegalStateException("STEP_IMPORT_NOT_READY");
        Bundle band = OHealthDeviceHook.registeredSnapshot();
        if (band == null || band.getString("deviceId", "").isBlank()) return;
        String device = band.getString("deviceId");
        List<HealthRecord> records = history(context, account, device);
        List<BandHistoryParser.StepDay> days = new ArrayList<>(BandHistoryParser.preferLiveTotal(
                BandHistoryParser.stepDays(records), band.getLong("stepsToday", -1), band.getLong("stepsAtMs", 0)));
        if (days.isEmpty()) return;
        SharedPreferences written = context.getSharedPreferences("oplusband-step-import", Context.MODE_PRIVATE);
        int inserted = 0;
        int skipped = 0;
        int chart = 0;
        for (BandHistoryParser.StepDay day : days) {
            String memory = "metric:" + device + ":" + day.date;
            String key = day.steps + ":" + day.calories + ":" + day.moveAbout + ":" + day.distance;
            if (!key.equals(written.getString(memory, ""))) {
                long end = day.startMs + 86_400_000L;
                List<?> existing = host.readStepDays(api, account, day.startMs, end);
                if (!covers(existing, device, day)) {
                    Object row = statNew.newInstance();
                    setAccount.invoke(row, account);
                    setDevice.invoke(row, device);
                    setDate.invoke(row, day.date);
                    setMode.invoke(row, DAY_STEP_MODE);
                    setSteps.invoke(row, (int) day.steps);
                    if (day.timezone != null) setTimezone.invoke(row, day.timezone);
                    if (day.calories >= 0) setCalories.invoke(row, day.calories);
                    if (day.distance >= 0 && day.distance <= Integer.MAX_VALUE) {
                        setDistance.invoke(row, (int) day.distance);
                    }
                    if (day.moveAbout >= 0) setMoveAbout.invoke(row, (int) day.moveAbout);
                    host.insertRows(api, TABLE_STAT, List.of(row));
                    List<?> confirmed = host.readStepDays(api, account, day.startMs, end);
                    if (!covers(confirmed, device, day)) {
                        throw new IllegalStateException("STEP_STAT_UNCONFIRMED");
                    }
                    inserted++;
                } else {
                    skipped++;
                }
                if (!written.edit().putString(memory, key).commit()) {
                    throw new IllegalStateException("STEP_IMPORT_MEMORY_FAILED");
                }
            } else {
                skipped++;
            }
            chart += writeChart(api, account, device, day, records, written);
        }
        Log.i("OplusBandBridge", "OHEALTH_STEP_IMPORT days=" + days.size()
                + " inserted=" + inserted + " skipped=" + skipped + " chart=" + chart);
    }

    private boolean covers(List<?> rows, String device, BandHistoryParser.StepDay day)
            throws ReflectiveOperationException {
        boolean steps = false;
        boolean calories = day.calories < 0;
        boolean moveAbout = day.moveAbout < 0;
        for (Object row : rows) {
            if (!statClass.isInstance(row)) continue;
            if (day.date != (Integer) getDate.invoke(row)) continue;
            if (!device.equals(getDevice.invoke(row))) continue;
            if ((Integer) getMode.invoke(row) != DAY_STEP_MODE) continue;
            if ((Integer) getSteps.invoke(row) >= day.steps) steps = true;
            if (day.calories >= 0 && (Long) getCalories.invoke(row) >= day.calories) calories = true;
            if (day.moveAbout >= 0 && (Integer) getMoveAbout.invoke(row) >= day.moveAbout) moveAbout = true;
        }
        return steps && calories && moveAbout;
    }

    /** Inserts only the growth since the last chart write, so a later total does not double an hour. */
    private int writeChart(Object api, String account, String device, BandHistoryParser.StepDay day,
            List<HealthRecord> records, SharedPreferences written) throws Exception {
        if (!prepareDetail()) return 0;
        long[] steps = new long[24];
        long[] calories = new long[24];
        long[] distance = new long[24];
        boolean[] seen = new boolean[24];
        long dayEnd = day.startMs + 86_400_000L;
        for (HealthRecord record : records) {
            if (record == null || !"steps_interval".equals(record.kind) || record.value == null) continue;
            if (record.startMs < day.startMs || record.startMs >= dayEnd) continue;
            if (day.timezone != null && !day.timezone.equals(record.timezone)) continue;
            int hour = (int) ((record.startMs - day.startMs) / 3_600_000L);
            if (hour < 0 || hour > 23) continue;
            steps[hour] += record.value.longValue();
            if (record.calories != null) calories[hour] += record.calories;
            if (record.distance != null) distance[hour] += record.distance;
            seen[hour] = true;
        }
        List<Object> rows = new ArrayList<>();
        List<Integer> hours = new ArrayList<>();
        long[] nextSteps = new long[24];
        long[] nextCalories = new long[24];
        long[] nextDistance = new long[24];
        for (int hour = 0; hour < 24; hour++) {
            if (!seen[hour] || steps[hour] <= 0) continue;
            long[] saved = savedHour(written, device, day.date, hour);
            long deltaSteps = steps[hour] - saved[0];
            long deltaCalories = calories[hour] - saved[1];
            long deltaDistance = distance[hour] - saved[2];
            if (deltaSteps <= 0 && deltaCalories <= 0 && deltaDistance <= 0) continue;
            rows.add(detail(account, device, day, hour,
                    (int) Math.max(0, deltaSteps),
                    Math.max(0, deltaCalories),
                    (int) Math.max(0, Math.min(deltaDistance, Integer.MAX_VALUE))));
            hours.add(hour);
            nextSteps[hour] = steps[hour];
            nextCalories[hour] = calories[hour];
            nextDistance[hour] = distance[hour];
        }
        if (rows.isEmpty()) return 0;
        host.insertRows(api, TABLE_DETAIL, rows);
        List<?> confirmed = host.readRows(api, account, TABLE_DETAIL, device, day.startMs, dayEnd, 0, false);
        long[] got = new long[24];
        for (Object row : confirmed) {
            if (!detailClass.isInstance(row)) continue;
            if (!device.equals(detailGetDevice.invoke(row))) continue;
            long start = (Long) detailGetStart.invoke(row);
            int hour = (int) ((start - day.startMs) / 3_600_000L);
            if (hour < 0 || hour > 23) continue;
            got[hour] += (Integer) detailGetSteps.invoke(row);
        }
        SharedPreferences.Editor editor = written.edit();
        for (int hour : hours) {
            if (got[hour] < nextSteps[hour]) throw new IllegalStateException("STEP_CHART_UNCONFIRMED");
            editor.putString(chartKey(device, day.date, hour),
                    nextSteps[hour] + ":" + nextCalories[hour] + ":" + nextDistance[hour]);
        }
        if (!editor.commit()) throw new IllegalStateException("STEP_IMPORT_MEMORY_FAILED");
        return hours.size();
    }

    private Object detail(String account, String device, BandHistoryParser.StepDay day, int hour,
            int steps, long calories, int distance) throws ReflectiveOperationException {
        Object row = detailNew.newInstance();
        detailAccount.invoke(row, account);
        detailDevice.invoke(row, device);
        long start = day.startMs + hour * 3_600_000L;
        detailStart.invoke(row, start);
        detailEnd.invoke(row, start + 3_600_000L);
        detailSteps.invoke(row, steps);
        detailCalories.invoke(row, calories);
        detailDistance.invoke(row, distance);
        detailMode.invoke(row, DAY_STEP_MODE);
        detailDisplay.invoke(row, 1);
        detailType.invoke(row, "band");
        if (day.timezone != null) detailZone.invoke(row, day.timezone);
        return row;
    }

    private boolean prepareDetail() {
        if (detailResolved) return detailClass != null;
        detailResolved = true;
        try {
            detailClass = Class.forName("com.heytap.databaseengine.model.SportDataDetail", false, host.loader);
            detailNew = detailClass.getConstructor();
            detailAccount = detailClass.getMethod("setSsoid", String.class);
            detailDevice = detailClass.getMethod("setDeviceUniqueId", String.class);
            detailStart = detailClass.getMethod("setStartTimestamp", long.class);
            detailEnd = detailClass.getMethod("setEndTimestamp", long.class);
            detailSteps = detailClass.getMethod("setSteps", int.class);
            detailCalories = detailClass.getMethod("setCalories", long.class);
            detailDistance = detailClass.getMethod("setDistance", int.class);
            detailMode = detailClass.getMethod("setSportMode", int.class);
            detailDisplay = detailClass.getMethod("setDisplay", int.class);
            detailType = detailClass.getMethod("setDeviceType", String.class);
            detailZone = detailClass.getMethod("setTimezone", String.class);
            detailGetStart = getter(detailClass, "getStartTimestamp", long.class);
            detailGetSteps = getter(detailClass, "getSteps", int.class);
            detailGetDevice = getter(detailClass, "getDeviceUniqueId", String.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            detailClass = null;
            Log.i("OplusBandBridge", "OHEALTH_STEP_CHART_UNAVAILABLE");
            return false;
        }
    }

    private static long[] savedHour(SharedPreferences written, String device, int date, int hour) {
        String saved = written.getString(chartKey(device, date, hour), "");
        long[] parts = new long[3];
        String[] fields = saved.split(":");
        if (fields.length != 3) return parts;
        try {
            parts[0] = Long.parseLong(fields[0]);
            parts[1] = Long.parseLong(fields[1]);
            parts[2] = Long.parseLong(fields[2]);
        } catch (NumberFormatException invalid) {
            return new long[3];
        }
        return parts;
    }

    private static String chartKey(String device, int date, int hour) {
        return device + ":" + date + ":h" + hour;
    }

    private List<HealthRecord> history(Context context, String account, String device) throws Exception {
        List<HealthRecord> records = new ArrayList<>();
        for (String kind : new String[] {"steps_day", "steps_interval"}) {
            String after = null;
            for (;;) {
                Uri uri = after == null ? HealthQueueProvider.RECORDS_URI
                        : HealthQueueProvider.RECORDS_URI.buildUpon().appendQueryParameter("after", after).build();
                int count = 0;
                try (Cursor rows = context.getContentResolver().query(uri, COLUMNS,
                        "account=? AND deviceId=? AND kind=? AND startMs<? AND endMs>?",
                        new String[] {account, device, kind, Long.toString(Long.MAX_VALUE), "0"}, null)) {
                    if (rows == null) throw new IllegalStateException("STEP_HISTORY_UNAVAILABLE");
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

    private static Method getter(Class<?> type, String name, Class<?> returnType) throws NoSuchMethodException {
        Method method = type.getMethod(name);
        if (method.getReturnType() != returnType) throw new NoSuchMethodException("STEP_MODEL_CONTRACT");
        return method;
    }
}
