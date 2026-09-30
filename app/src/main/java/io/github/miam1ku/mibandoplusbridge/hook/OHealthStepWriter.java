// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import de.robv.android.xposed.XposedBridge;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Writes one daily step total per day into OHealth table 1002, and minute bars into table 1001. */
final class OHealthStepWriter {
    static final int TABLE_STAT = 1002;
    static final int TABLE_DETAIL = 1001;
    /** SportMode.ALL. The home total and the half-hour chart read this mode; it is not a stored walk. */
    static final int DAY_STEP_MODE = -2;
    /** SportMode.WALK. Minute rows use a real mode so the all-mode and band-only chart queries include them. */
    static final int MINUTE_MODE = 1;
    /** DeviceCategory.BAND. The lowercase value is not a wearable, so the source list keeps only the phone. */
    static final String DEVICE_BAND = "Band";
    /** Phone import stores calories times 1000. The daily label divides by 1000 to show kilocalories. */
    static final int CALORIE_SCALE = 1000;
    static final int MINUTES_PER_DAY = 1_440;
    private static final long MINUTE_MS = 60_000L;
    private static final int INSERT_CHUNK = 400;
    private static final String[] COLUMNS = {"recordId", "revision", "record"};
    private final OHealthHealthImportHook.HostContract host;
    private final Class<?> statClass;
    private final Constructor<?> statNew;
    private final Method setAccount, setDevice, setDate, setMode, setSteps, setTimezone;
    private final Method setCalories, setDistance, setMoveAbout, setWorkoutMinutes;
    private final Method getDevice, getDate, getMode, getSteps, getCalories, getMoveAbout;
    private boolean detailResolved;
    private Class<?> detailClass;
    private Constructor<?> detailNew;
    private Method detailAccount, detailDevice, detailStart, detailEnd, detailSteps, detailCalories;
    private Method detailDistance, detailMode, detailDisplay, detailType, detailZone, detailWorkout, detailMove;
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
        setWorkoutMinutes = statClass.getMethod("setTotalWorkoutMinutes", int.class);
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
        days.removeIf(day -> day.steps <= 0 && day.calories < 0 && day.moveAbout < 0 && day.distance < 0);
        if (days.isEmpty()) return;
        SharedPreferences written = context.getSharedPreferences("oplusband-step-import", Context.MODE_PRIVATE);
        int inserted = 0;
        int skipped = 0;
        for (BandHistoryParser.StepDay day : days) {
            try {
                if (writeStat(api, account, device, day, records, written)) inserted++;
                else skipped++;
            } catch (Exception failure) {
                skipped++;
                XposedBridge.log("OplusBandBridge OHEALTH_STEP_STAT_FAIL date=" + day.date + " " + failure);
            }
        }
        int chart = 0;
        for (BandHistoryParser.StepDay day : days) {
            try {
                chart += writeChart(api, account, device, day, records, written);
            } catch (Exception failure) {
                XposedBridge.log("OplusBandBridge OHEALTH_STEP_CHART_FAIL date=" + day.date + " " + failure);
            }
        }
        String summary = "OHEALTH_STEP_IMPORT days=" + days.size()
                + " inserted=" + inserted + " skipped=" + skipped + " chart=" + chart;
        Log.i("OplusBandBridge", summary);
        XposedBridge.log("OplusBandBridge " + summary);
    }

    private boolean writeStat(Object api, String account, String device, BandHistoryParser.StepDay day,
            List<HealthRecord> records, SharedPreferences written) throws Exception {
        String memory = "metric:" + device + ":" + day.date;
        String key = day.steps + ":" + day.calories + ":" + day.moveAbout + ":" + day.distance + ":milli";
        if (key.equals(written.getString(memory, ""))) return false;
        long end = day.startMs + 86_400_000L;
        List<?> existing = host.readStepDays(api, account, day.startMs, end);
        if (!covers(existing, device, day)) {
            Object row = statNew.newInstance();
            setAccount.invoke(row, account);
            setDevice.invoke(row, device);
            setDate.invoke(row, day.date);
            setMode.invoke(row, MINUTE_MODE);
            setSteps.invoke(row, (int) day.steps);
            if (day.timezone != null) setTimezone.invoke(row, day.timezone);
            if (day.calories >= 0) setCalories.invoke(row, day.calories * (long) CALORIE_SCALE);
            if (day.distance >= 0 && day.distance <= Integer.MAX_VALUE) {
                setDistance.invoke(row, (int) day.distance);
            }
            if (day.moveAbout >= 0) setMoveAbout.invoke(row, (int) day.moveAbout);
            setWorkoutMinutes.invoke(row, activeMinutes(records, day));
            host.insertRows(api, TABLE_STAT, List.of(row));
            List<?> confirmed = host.readStepDays(api, account, day.startMs, end);
            if (!covers(confirmed, device, day)) {
                String miss = "OHEALTH_STEP_STAT_MISS date=" + day.date
                        + " steps=" + day.steps + " cal=" + (day.calories * (long) CALORIE_SCALE)
                        + " move=" + day.moveAbout + " " + summarize(confirmed);
                Log.i("OplusBandBridge", miss);
                XposedBridge.log("OplusBandBridge " + miss);
                throw new IllegalStateException("STEP_STAT_UNCONFIRMED");
            }
        }
        if (!written.edit().putString(memory, key).commit()) {
            throw new IllegalStateException("STEP_IMPORT_MEMORY_FAILED");
        }
        return true;
    }

    private boolean covers(List<?> rows, String device, BandHistoryParser.StepDay day)
            throws ReflectiveOperationException {
        boolean steps = false;
        boolean calories = day.calories < 0;
        boolean moveAbout = day.moveAbout < 0;
        long wantCalories = day.calories < 0 ? -1 : day.calories * (long) CALORIE_SCALE;
        for (Object row : rows) {
            if (!statClass.isInstance(row)) continue;
            if (day.date != (Integer) getDate.invoke(row)) continue;
            if (!device.equals(getDevice.invoke(row))) continue;
            if ((Integer) getMode.invoke(row) != MINUTE_MODE) continue;
            if ((Integer) getSteps.invoke(row) >= day.steps) steps = true;
            if (day.calories >= 0 && (Long) getCalories.invoke(row) >= wantCalories) calories = true;
            if (day.moveAbout >= 0 && (Integer) getMoveAbout.invoke(row) >= day.moveAbout) moveAbout = true;
        }
        return steps && calories && moveAbout;
    }

    private String summarize(List<?> rows) {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (Object row : rows) {
            if (!statClass.isInstance(row)) continue;
            if (shown == 4) break;
            try {
                String id = String.valueOf(getDevice.invoke(row));
                text.append(" [date=").append(getDate.invoke(row))
                        .append(" mode=").append(getMode.invoke(row))
                        .append(" steps=").append(getSteps.invoke(row))
                        .append(" cal=").append(getCalories.invoke(row))
                        .append(" move=").append(getMoveAbout.invoke(row))
                        .append(" dev=").append(id.length() > 12 ? id.substring(0, 12) : id)
                        .append(']');
                shown++;
            } catch (ReflectiveOperationException ignored) {
                text.append(" [unreadable]");
            }
        }
        if (shown == 0) text.append(" none");
        return text.toString();
    }

    /** Inserts minute growth. The half-hour and hour charts group these rows; an hour blob does not. */
    private int writeChart(Object api, String account, String device, BandHistoryParser.StepDay day,
            List<HealthRecord> records, SharedPreferences written) throws Exception {
        if (!prepareDetail()) return 0;
        MinuteBar[] minutes = MinuteBar.day(records, day.startMs, day.timezone);
        String version = device + ":" + day.date + ":minute-v1";
        boolean rebuild = !"1".equals(written.getString(version, ""));
        if (rebuild) host.deleteRows(api, TABLE_DETAIL, account, device, day.startMs, day.startMs + 86_400_000L);
        List<Object> rows = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (int minute = 0; minute < MINUTES_PER_DAY; minute++) {
            MinuteBar bar = minutes[minute];
            if (bar == null) continue;
            int[] saved = rebuild ? new int[3] : savedMinute(written, device, day.date, minute);
            int deltaSteps = bar.steps - saved[0];
            int deltaCalories = bar.calories - saved[1];
            int deltaDistance = bar.distance - saved[2];
            if (deltaSteps <= 0 && deltaCalories <= 0 && deltaDistance <= 0) continue;
            int workout = bar.steps > 0 && saved[0] <= 0 ? 1 : 0;
            int move = bar.moveAbout > 0 && saved[0] <= 0 ? 1 : 0;
            rows.add(detail(account, device, day, minute,
                    Math.max(0, deltaSteps),
                    Math.max(0, deltaCalories) * (long) CALORIE_SCALE,
                    Math.max(0, deltaDistance), workout, move));
            indexes.add(minute);
        }
        if (rows.isEmpty()) {
            if (rebuild && !written.edit().putString(version, "1").commit()) {
                throw new IllegalStateException("STEP_IMPORT_MEMORY_FAILED");
            }
            return 0;
        }
        for (int from = 0; from < rows.size(); from += INSERT_CHUNK) {
            host.insertRows(api, TABLE_DETAIL, rows.subList(from, Math.min(rows.size(), from + INSERT_CHUNK)));
        }
        long dayEnd = day.startMs + 86_400_000L;
        List<?> confirmed = host.readRows(api, account, TABLE_DETAIL, device, day.startMs, dayEnd, 0, false,
                MINUTES_PER_DAY + 24);
        int[] got = new int[MINUTES_PER_DAY];
        for (Object row : confirmed) {
            if (!detailClass.isInstance(row)) continue;
            if (!device.equals(detailGetDevice.invoke(row))) continue;
            long start = (Long) detailGetStart.invoke(row);
            int minute = (int) ((start - day.startMs) / MINUTE_MS);
            if (minute < 0 || minute >= MINUTES_PER_DAY) continue;
            int steps = (Integer) detailGetSteps.invoke(row);
            if (steps > 0 && got[minute] <= Integer.MAX_VALUE - steps) got[minute] += steps;
        }
        SharedPreferences.Editor editor = written.edit();
        int stored = 0;
        for (int minute : indexes) {
            if (got[minute] < minutes[minute].steps) throw new IllegalStateException("STEP_CHART_UNCONFIRMED");
            editor.putString(minuteKey(device, day.date, minute),
                    minutes[minute].steps + ":" + minutes[minute].calories + ":" + minutes[minute].distance);
            stored++;
        }
        editor.putString(version, "1");
        if (!editor.commit()) throw new IllegalStateException("STEP_IMPORT_MEMORY_FAILED");
        return stored;
    }

    private Object detail(String account, String device, BandHistoryParser.StepDay day, int minute,
            int steps, long calories, int distance, int workout, int moveAbout) throws ReflectiveOperationException {
        Object row = detailNew.newInstance();
        detailAccount.invoke(row, account);
        detailDevice.invoke(row, device);
        long start = day.startMs + minute * MINUTE_MS;
        detailStart.invoke(row, start);
        detailEnd.invoke(row, start + MINUTE_MS);
        detailSteps.invoke(row, steps);
        detailCalories.invoke(row, calories);
        detailDistance.invoke(row, distance);
        detailWorkout.invoke(row, workout);
        detailMove.invoke(row, moveAbout);
        detailMode.invoke(row, MINUTE_MODE);
        detailDisplay.invoke(row, 1);
        detailType.invoke(row, DEVICE_BAND);
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
            detailWorkout = detailClass.getMethod("setWorkout", int.class);
            detailMove = detailClass.getMethod("setMoveAbout", int.class);
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


    private static int[] savedMinute(SharedPreferences written, String device, int date, int minute) {
        String saved = written.getString(minuteKey(device, date, minute), "");
        int[] parts = new int[3];
        String[] fields = saved.split(":");
        if (fields.length != 3) return parts;
        try {
            parts[0] = Integer.parseInt(fields[0]);
            parts[1] = Integer.parseInt(fields[1]);
            parts[2] = Integer.parseInt(fields[2]);
        } catch (NumberFormatException invalid) {
            return new int[3];
        }
        return parts;
    }

    private static String minuteKey(String device, int date, int minute) {
        return device + ":" + date + ":n" + minute;
    }

    private static int activeMinutes(List<HealthRecord> records, BandHistoryParser.StepDay day) {
        int active = 0;
        for (MinuteBar bar : MinuteBar.day(records, day.startMs, day.timezone)) {
            if (bar != null && bar.steps > 0) active++;
        }
        return active;
    }

    /** One local day of minute bars. Workout is one minute; move-about marks the first active minute of an hour. */
    static final class MinuteBar {
        final int steps;
        final int calories;
        final int distance;
        final int workout;
        final int moveAbout;

        MinuteBar(int steps, int calories, int distance, int workout, int moveAbout) {
            this.steps = steps;
            this.calories = calories;
            this.distance = distance;
            this.workout = workout;
            this.moveAbout = moveAbout;
        }

        static MinuteBar[] day(List<HealthRecord> records, long dayStart, String timezone) {
            int[] steps = new int[MINUTES_PER_DAY];
            int[] calories = new int[MINUTES_PER_DAY];
            int[] distance = new int[MINUTES_PER_DAY];
            boolean[] seen = new boolean[MINUTES_PER_DAY];
            long dayEnd = dayStart + 86_400_000L;
            if (records != null) {
                for (HealthRecord record : records) {
                    if (record == null || !"steps_interval".equals(record.kind) || record.value == null) continue;
                    if (record.startMs < dayStart || record.startMs >= dayEnd) continue;
                    if (timezone != null && !timezone.equals(record.timezone)) continue;
                    int minute = (int) ((record.startMs - dayStart) / MINUTE_MS);
                    if (minute < 0 || minute >= MINUTES_PER_DAY) continue;
                    steps[minute] = saturate(steps[minute], record.value.longValue());
                    if (record.calories != null) calories[minute] = saturate(calories[minute], record.calories);
                    if (record.distance != null) distance[minute] = saturate(distance[minute], record.distance);
                    seen[minute] = true;
                }
            }
            MinuteBar[] bars = new MinuteBar[MINUTES_PER_DAY];
            for (int minute = 0; minute < MINUTES_PER_DAY; minute++) {
                if (!seen[minute] || (steps[minute] <= 0 && calories[minute] <= 0 && distance[minute] <= 0)) continue;
                boolean firstActiveHour = steps[minute] > 0;
                if (firstActiveHour) {
                    int hourStart = minute / 60 * 60;
                    for (int earlier = hourStart; earlier < minute; earlier++) {
                        if (bars[earlier] != null && bars[earlier].steps > 0) {
                            firstActiveHour = false;
                            break;
                        }
                    }
                }
                bars[minute] = new MinuteBar(steps[minute], calories[minute], distance[minute],
                        steps[minute] > 0 ? 1 : 0, firstActiveHour ? 1 : 0);
            }
            return bars;
        }

        private static int saturate(int current, long add) {
            if (add <= 0) return current;
            return add >= Integer.MAX_VALUE - current ? Integer.MAX_VALUE : current + (int) add;
        }
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
