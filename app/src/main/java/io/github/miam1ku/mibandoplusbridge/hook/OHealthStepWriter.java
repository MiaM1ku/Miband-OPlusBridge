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

/** Writes one daily step total per day into OHealth table 1002, attributed to this band. */
final class OHealthStepWriter {
    static final int TABLE_STAT = 1002;
    /** Phone step import uses this mode for a day total. The device id keeps it off the phone. */
    static final int DAY_STEP_MODE = -2;
    private static final String[] COLUMNS = {"recordId", "revision", "record"};
    private final OHealthHealthImportHook.HostContract host;
    private final Class<?> statClass;
    private final Constructor<?> statNew;
    private final Method setAccount, setDevice, setDate, setMode, setSteps;
    private final Method getDevice, getDate, getMode, getSteps;

    OHealthStepWriter(OHealthHealthImportHook.HostContract host) throws ReflectiveOperationException {
        this.host = host;
        statClass = Class.forName("com.heytap.databaseengine.model.SportDataStat", false, host.loader);
        statNew = statClass.getConstructor();
        setAccount = statClass.getMethod("setSsoid", String.class);
        setDevice = statClass.getMethod("setDeviceUniqueId", String.class);
        setDate = statClass.getMethod("setDate", int.class);
        setMode = statClass.getMethod("setSportMode", int.class);
        setSteps = statClass.getMethod("setTotalSteps", int.class);
        getDevice = getter("getDeviceUniqueId", String.class);
        getDate = getter("getDate", int.class);
        getMode = getter("getSportMode", int.class);
        getSteps = getter("getTotalSteps", int.class);
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
        for (BandHistoryParser.StepDay day : days) {
            String key = device + ":" + day.date;
            if (written.getLong(key, -1) == day.steps) {
                skipped++;
                continue;
            }
            long end = day.startMs + 86_400_000L;
            List<?> existing = host.readStepDays(api, account, day.startMs, end);
            if (covers(existing, device, day)) {
                if (!written.edit().putLong(key, day.steps).commit()) {
                    throw new IllegalStateException("STEP_IMPORT_MEMORY_FAILED");
                }
                skipped++;
                continue;
            }
            Object row = statNew.newInstance();
            setAccount.invoke(row, account);
            setDevice.invoke(row, device);
            setDate.invoke(row, day.date);
            setMode.invoke(row, DAY_STEP_MODE);
            setSteps.invoke(row, (int) day.steps);
            host.insertRows(api, TABLE_STAT, List.of(row));
            List<?> confirmed = host.readStepDays(api, account, day.startMs, end);
            if (!covers(confirmed, device, day)) {
                throw new IllegalStateException("STEP_STAT_UNCONFIRMED");
            }
            if (!written.edit().putLong(key, day.steps).commit()) {
                throw new IllegalStateException("STEP_IMPORT_MEMORY_FAILED");
            }
            inserted++;
        }
        Log.i("OplusBandBridge", "OHEALTH_STEP_IMPORT days=" + days.size()
                + " inserted=" + inserted + " skipped=" + skipped);
    }

    private boolean covers(List<?> rows, String device, BandHistoryParser.StepDay day)
            throws ReflectiveOperationException {
        for (Object row : rows) {
            if (!statClass.isInstance(row)) continue;
            if (day.date != (Integer) getDate.invoke(row)) continue;
            if (!device.equals(getDevice.invoke(row))) continue;
            if ((Integer) getMode.invoke(row) != DAY_STEP_MODE) continue;
            if ((Integer) getSteps.invoke(row) >= day.steps) return true;
        }
        return false;
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

    private Method getter(String name, Class<?> returnType) throws NoSuchMethodException {
        Method method = statClass.getMethod(name);
        if (method.getReturnType() != returnType) throw new NoSuchMethodException("STEP_MODEL_CONTRACT");
        return method;
    }
}
