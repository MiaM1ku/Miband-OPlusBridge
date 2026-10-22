// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedBridge;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Writes walk and home daily totals into OHealth table 1002, and minute bars into table 1001. */
final class OHealthStepWriter {
    static final int TABLE_STAT = 1002;
    static final int TABLE_DETAIL = 1001;
    /** SportMode.WATCH_OR_BAND_DATA_ONLY_USED_READ. Official saveStatData stores the day here. */
    static final int DAY_STEP_MODE = -3;
    /** SportMode.WALK. A band stores this minute type as-is. A watch remaps it. */
    static final int MINUTE_MODE = 1;
    /** DeviceCategory.BAND. The lowercase value is not a wearable, so the source list keeps only the phone. */
    static final String DEVICE_BAND = "Band";
    /** DeviceCategory.WATCH. Same spelling the health library stores for a watch. */
    static final String DEVICE_WATCH = "Watch";
    /** Phone import stores calories times 1000. The daily label divides by 1000 to show kilocalories. */
    static final int CALORIE_SCALE = 1000;
    static final int MINUTES_PER_DAY = 1_440;
    private static final long MINUTE_MS = 60_000L;
    private static final int INSERT_CHUNK = 400;
    /** Bumped when stored minute keys stop matching what the host holds. */
    static final String CHART_VERSION = "minute-v4";
    /** InsertOptionCheck rejects the whole batch when one minute is above these. */
    static final int MAX_MINUTE_STEPS = 350;
    static final long MAX_MINUTE_CALORIES = 65_536L;
    static final int MAX_MINUTE_DISTANCE = 1_050;
    private static final String[] COLUMNS = {"recordId", "revision", "record"};
    private final OHealthHealthImportHook.HostContract host;
    private final Class<?> statClass;
    private final Constructor<?> statNew;
    private final Method setAccount, setDevice, setDate, setMode, setSteps, setTimezone;
    private final Method setCalories, setDistance, setMoveAbout, setSync, setUpdated;
    private final Method getDevice, getDate, getMode, getSteps, getCalories, getMoveAbout;
    private boolean detailResolved;
    private Class<?> detailClass;
    private Constructor<?> detailNew;
    private Method detailAccount, detailDevice, detailStart, detailEnd, detailSteps, detailCalories;
    private Method detailDistance, detailWorkout, detailMode, detailDisplay, detailType, detailZone;
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
        setSync = statClass.getMethod("setSyncStatus", int.class);
        setUpdated = statClass.getMethod("setUpdateTimestamp", long.class);
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
        String queued = band.getString("deviceId");
        String device = OHealthDeviceHook.healthId(queued);
        String category = deviceCategory(band.getString("modelId", ""));
        List<HealthRecord> records = history(context, account, queued);
        List<BandHistoryParser.StepDay> days = new ArrayList<>(BandHistoryParser.preferLiveTotal(
                BandHistoryParser.stepDays(records), band.getLong("stepsToday", -1),
                band.getLong("stepsAtMs", 0), java.time.ZoneId.systemDefault()));
        days.removeIf(day -> day.steps <= 0 && day.calories < 0 && day.moveAbout < 0 && day.distance < 0);
        if (days.isEmpty()) return;
        SharedPreferences written = context.getSharedPreferences("oplusband-step-import", Context.MODE_PRIVATE);
        int inserted = 0;
        int skipped = 0;
        for (BandHistoryParser.StepDay day : days) {
            try {
                if (writeStat(api, account, device, day, written)) inserted++;
                else skipped++;
            } catch (Exception failure) {
                skipped++;
                XposedBridge.log("OplusBandBridge OHEALTH_STEP_STAT_FAIL date=" + day.date + " " + failure);
            }
        }
        int chart = 0;
        for (BandHistoryParser.StepDay day : days) {
            try {
                chart += writeChart(api, account, device, category, day, records, written);
            } catch (Exception failure) {
                XposedBridge.log("OplusBandBridge OHEALTH_STEP_CHART_FAIL date=" + day.date + " " + failure);
            }
        }
        // Rows left under the queued id stay. A table 1001 delete cannot be scoped to one device:
        // it cleared every minute of the day, this band's new rows and the phone's included.
        publishToday(context, days);
        publishSnapshot(context);
        String summary = "OHEALTH_STEP_IMPORT days=" + days.size()
                + " inserted=" + inserted + " skipped=" + skipped + " chart=" + chart;
        HookRuntime.log(summary);
        XposedBridge.log("OplusBandBridge " + summary);
    }

    private boolean writeStat(Object api, String account, String device, BandHistoryParser.StepDay day,
            SharedPreferences written) throws Exception {
        String memory = "metric:" + device + ":" + day.date;
        String key = day.steps + ":" + day.calories + ":" + day.moveAbout + ":" + day.distance + ":milli";
        boolean changed = !key.equals(written.getString(memory, ""));
        if (changed) {
            long end = day.startMs + 86_400_000L;
            List<?> existing = host.readStepDays(api, account, day.startMs, end, DAY_STEP_MODE);
            if (!covers(existing, device, day, DAY_STEP_MODE)) {
                host.insertRows(api, TABLE_STAT, List.of(statRow(account, device, day, DAY_STEP_MODE)));
                List<?> confirmed = host.readStepDays(api, account, day.startMs, end, DAY_STEP_MODE);
                if (!covers(confirmed, device, day, DAY_STEP_MODE)) {
                    String miss = "OHEALTH_STEP_STAT_MISS date=" + day.date
                            + " steps=" + day.steps + " cal=" + (day.calories * (long) CALORIE_SCALE)
                            + " move=" + day.moveAbout + " " + summarize(confirmed);
                    HookRuntime.log(miss);
                    XposedBridge.log("OplusBandBridge " + miss);
                    throw new IllegalStateException("STEP_STAT_UNCONFIRMED");
                }
            }
            if (!written.edit().putString(memory, key).commit()) {
                throw new IllegalStateException("STEP_IMPORT_MEMORY_FAILED");
            }
        }
        return changed;
    }

    private Object statRow(String account, String device, BandHistoryParser.StepDay day, int mode)
            throws ReflectiveOperationException {
        Object row = statNew.newInstance();
        setAccount.invoke(row, account);
        setDevice.invoke(row, device);
        setDate.invoke(row, day.date);
        setMode.invoke(row, mode);
        setSteps.invoke(row, (int) day.steps);
        if (day.timezone != null) setTimezone.invoke(row, day.timezone);
        if (day.calories >= 0) setCalories.invoke(row, day.calories * (long) CALORIE_SCALE);
        if (day.distance >= 0 && day.distance <= Integer.MAX_VALUE) {
            setDistance.invoke(row, (int) day.distance);
        }
        if (day.moveAbout >= 0) setMoveAbout.invoke(row, (int) day.moveAbout);
        // The library's exercise minutes come from the device's totalExercise, not from
        // counting minutes that merely have steps. This report does not carry that field.
        setSync.invoke(row, 0);
        setUpdated.invoke(row, day.startMs);
        return row;
    }

    private boolean covers(List<?> rows, String device, BandHistoryParser.StepDay day, int mode)
            throws ReflectiveOperationException {
        boolean steps = false;
        boolean calories = day.calories < 0;
        boolean moveAbout = day.moveAbout < 0;
        long wantCalories = day.calories < 0 ? -1 : day.calories * (long) CALORIE_SCALE;
        for (Object row : rows) {
            if (!statClass.isInstance(row)) continue;
            if (day.date != (Integer) getDate.invoke(row)) continue;
            if (!device.equals(getDevice.invoke(row))) continue;
            if ((Integer) getMode.invoke(row) != mode) continue;
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

    /**
     * Inserts whole minute values. Step and calorie charts sum these rows into half hours.
     * The exercise chart sums {@code workout} the same way and caps a half hour at 30.
     * The hour chart marks a band hour only when some minute in it has more than 30 steps,
     * and a watch hour when some minute has any steps. An hour blob is a different table.
     *
     * <p>Table 1001 delete and read ignore the device id: a delete clears every device's minutes
     * in the window, including the phone's own. So this never deletes. The host merge keys a minute
     * on (account, device, start) and replaces the stored minute only when the new calories are
     * higher, so each pass sends the full minute value, never the growth since the last pass.
     */
    private int writeChart(Object api, String account, String device, String category,
            BandHistoryParser.StepDay day, List<HealthRecord> records, SharedPreferences written)
            throws Exception {
        if (!prepareDetail()) return 0;
        MinuteBar[] minutes = MinuteBar.day(records, day.startMs, day.timezone);
        // v3 and earlier deleted the day after writing it, so every saved minute key is stale.
        String version = device + ":" + day.date + ":" + CHART_VERSION;
        boolean resend = !"1".equals(written.getString(version, ""));
        List<Object> rows = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (int minute = 0; minute < MINUTES_PER_DAY; minute++) {
            MinuteBar bar = minutes[minute];
            if (bar == null) continue;
            int[] saved = resend ? new int[3] : savedMinute(written, device, day.date, minute);
            if (bar.steps <= saved[0] && bar.calories <= saved[1] && bar.distance <= saved[2]) continue;
            rows.add(detail(account, device, category, day, minute,
                    Math.min(bar.steps, MAX_MINUTE_STEPS),
                    Math.min((long) bar.calories * CALORIE_SCALE, MAX_MINUTE_CALORIES),
                    Math.min(bar.distance, MAX_MINUTE_DISTANCE), bar.workout > 0 ? 1 : 0));
            indexes.add(minute);
        }
        if (rows.isEmpty()) {
            if (resend && !written.edit().putString(version, "1").commit()) {
                throw new IllegalStateException("STEP_IMPORT_MEMORY_FAILED");
            }
            return 0;
        }
        for (int from = 0; from < rows.size(); from += INSERT_CHUNK) {
            host.insertRows(api, TABLE_DETAIL, rows.subList(from, Math.min(rows.size(), from + INSERT_CHUNK)));
        }
        long dayEnd = day.startMs + 86_400_000L;
        // The host returns every device's minutes here; keep this band's rows only.
        List<?> confirmed = host.readRows(api, account, TABLE_DETAIL, device, day.startMs, dayEnd, 0, false,
                MINUTES_PER_DAY + 24);
        int[] got = new int[MINUTES_PER_DAY];
        boolean[] present = new boolean[MINUTES_PER_DAY];
        for (Object row : confirmed) {
            if (!detailClass.isInstance(row)) continue;
            if (!device.equals(detailGetDevice.invoke(row))) continue;
            long start = (Long) detailGetStart.invoke(row);
            int minute = (int) ((start - day.startMs) / MINUTE_MS);
            if (minute < 0 || minute >= MINUTES_PER_DAY) continue;
            present[minute] = true;
            got[minute] = Math.max(got[minute], (Integer) detailGetSteps.invoke(row));
        }
        SharedPreferences.Editor editor = written.edit();
        int stored = 0;
        int missing = 0;
        int kept = 0;
        for (int minute : indexes) {
            if (!present[minute]) {
                missing++;
                continue;
            }
            // A minute already stored with the same calories keeps its old steps; the host
            // merge has no way to raise it. Remember it so the same rows are not resent forever.
            if (got[minute] < Math.min(minutes[minute].steps, MAX_MINUTE_STEPS)) kept++;
            editor.putString(minuteKey(device, day.date, minute),
                    minutes[minute].steps + ":" + minutes[minute].calories + ":" + minutes[minute].distance);
            stored++;
        }
        if (missing == 0) editor.putString(version, "1");
        if (!editor.commit()) throw new IllegalStateException("STEP_IMPORT_MEMORY_FAILED");
        if (kept > 0 || missing > 0) {
            HookRuntime.log("OHEALTH_STEP_CHART_SHORT date=" + day.date + " sent=" + rows.size()
                    + " missing=" + missing + " kept=" + kept);
        }
        if (missing > 0) throw new IllegalStateException("STEP_CHART_UNCONFIRMED");
        return stored;
    }

    private Object detail(String account, String device, String category, BandHistoryParser.StepDay day,
            int minute, int steps, long calories, int distance, int workout) throws ReflectiveOperationException {
        Object row = detailNew.newInstance();
        detailAccount.invoke(row, account);
        detailDevice.invoke(row, device);
        long start = day.startMs + minute * MINUTE_MS;
        detailStart.invoke(row, start);
        detailEnd.invoke(row, start + MINUTE_MS);
        detailSteps.invoke(row, steps);
        detailCalories.invoke(row, calories);
        detailDistance.invoke(row, distance);
        // The half-hour exercise chart is the sum of this flag. Xiaomi does not send
        // minuteExercise, so a minute that has steps is the minute the chart can draw.
        detailWorkout.invoke(row, workout);
        // A watch remaps walk (1) to -1, which keeps those minutes out of the phone walk bucket.
        detailMode.invoke(row, minuteSportMode(DEVICE_WATCH.equals(category)));
        detailDisplay.invoke(row, 1);
        detailType.invoke(row, category);
        if (day.timezone != null) detailZone.invoke(row, day.timezone);
        return row;
    }

    /** {@code DeviceCategory} string for a Xiaomi model. Unknown models stay a band. */
    static String deviceCategory(String modelId) {
        return io.github.miam1ku.mibandoplusbridge.data.BandCatalog.healthCategory(modelId);
    }

    /** Walk minutes on a watch use the library remap. A band keeps type 1. */
    static int minuteSportMode(boolean watch) {
        return watch ? groupSportType(MINUTE_MODE) : MINUTE_MODE;
    }

    /** {@code DataUtils.groupSportType}. Watch daily-activity minutes go through this. */
    static int groupSportType(int type) {
        return switch (type) {
            case 0 -> 6;
            case 1 -> -1;
            case 2 -> 1;
            case 4, 5 -> 5;
            case 6 -> 2;
            case 7 -> 3;
            case 8 -> 4;
            case 9 -> 7;
            default -> 0;
        };
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
            HookRuntime.log("OHEALTH_STEP_CHART_UNAVAILABLE");
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

    /** True only when the band total should replace the cached home steps. */
    static boolean publishSteps(long shown, long band) {
        return band > shown && band > 0;
    }

    /**
     * Official step daemon writes today's total through
     * {@code content://com.heytap.health.sporthealthprovider/self/sport}. The health card reads that
     * cache, not the bridge snapshot. Only {@code step} is sent so calorie and goals already there stay.
     */
    void publishSnapshot(Context context) {
        Bundle band = OHealthDeviceHook.registeredSnapshot();
        if (band == null) return;
        long steps = band.getLong("stepsToday", -1);
        if (!publishSteps(0, steps)) return;
        ContentValues values = new ContentValues();
        values.put("step", steps);
        updateSportCache(context, values, steps);
    }

    private void publishToday(Context context, List<BandHistoryParser.StepDay> days) {
        BandHistoryParser.StepDay today = null;
        for (BandHistoryParser.StepDay day : days) {
            if (isToday(day)) today = day;
        }
        if (today == null) return;
        ContentValues values = new ContentValues();
        values.put("step", today.steps);
        if (today.calories >= 0) values.put("calorie", (double) today.calories);
        if (today.distance >= 0) values.put("distance", today.distance / 1000.0);
        if (today.moveAbout >= 0 && today.moveAbout <= Integer.MAX_VALUE) {
            values.put("activityCount", (int) today.moveAbout);
        }
        updateSportCache(context, values, today.steps);
        OHealthHomeMetricHook.requestReload();
    }

    private void updateSportCache(Context context, ContentValues values, long steps) {
        try {
            Class<?> adapter = Class.forName(
                    "com.heytap.health.core.provider.adapter.open.SportDataAdapter", false, host.loader);
            Object result = adapter.getMethod("querySportData", Context.class).invoke(null, context);
            if (!(result instanceof Bundle bundle)) throw new IllegalStateException("STEP_CACHE_BUNDLE");
            if (!publishSteps(bundle.getLong("step"), steps)) return;
            adapter.getMethod("updateSportData", Context.class, ContentValues.class)
                    .invoke(null, context, values);
        } catch (Throwable failure) {
            String unavailable = "OHEALTH_STEP_CACHE_UNAVAILABLE " + failure.getClass().getSimpleName();
            HookRuntime.log(unavailable);
            XposedBridge.log("OplusBandBridge " + unavailable);
        }
    }

    private static boolean isToday(BandHistoryParser.StepDay day) {
        ZoneId zone = ZoneId.systemDefault();
        if (day.timezone != null && !day.timezone.isBlank()) {
            try {
                zone = ZoneId.of(day.timezone);
            } catch (DateTimeException ignored) { }
        }
        LocalDate now = LocalDate.now(zone);
        int today = now.getYear() * 10_000 + now.getMonthValue() * 100 + now.getDayOfMonth();
        return today == day.date;
    }

    /** One local day of minute bars. A minute with steps is one workout minute on the chart. */
    static final class MinuteBar {
        final int steps;
        final int calories;
        final int distance;
        final int workout;

        MinuteBar(int steps, int calories, int distance, int workout) {
            this.steps = steps;
            this.calories = calories;
            this.distance = distance;
            this.workout = workout;
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
                bars[minute] = new MinuteBar(steps[minute], calories[minute], distance[minute],
                        steps[minute] > 0 ? 1 : 0);
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
