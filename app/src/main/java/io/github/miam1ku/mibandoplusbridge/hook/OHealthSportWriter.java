// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.miam1ku.mibandoplusbridge.protocol.SportGpsParser;
import io.github.miam1ku.mibandoplusbridge.protocol.SportReportParser;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Writes one Xiaomi sport summary into OHealth table 1004 (DBOneTimeSport). */
final class OHealthSportWriter {
    static final int TABLE = 1004;
    private static final String[] COLUMNS = {"recordId", "revision", "record"};
    private final OHealthHealthImportHook.HostContract host;
    private final Constructor<?> created;
    private final Method setAccount, setDevice, setType, setStart, setEnd, setMode, setZone;
    private final Method setDisplay, setSync, setMeta, setClient, setVersion, setData;
    private final Method getStart, getEnd, getData, getMode, getDevice;

    OHealthSportWriter(OHealthHealthImportHook.HostContract host) throws ReflectiveOperationException {
        this.host = host;
        Class<?> type = Class.forName("com.heytap.databaseengine.model.OneTimeSport", false, host.loader);
        created = type.getConstructor();
        setAccount = type.getMethod("setSsoid", String.class);
        setDevice = type.getMethod("setDeviceUniqueId", String.class);
        setType = type.getMethod("setDeviceType", String.class);
        setStart = type.getMethod("setStartTimestamp", long.class);
        setEnd = type.getMethod("setEndTimestamp", long.class);
        setMode = type.getMethod("setSportMode", int.class);
        setZone = type.getMethod("setTimezone", String.class);
        setDisplay = type.getMethod("setDisplay", int.class);
        setSync = type.getMethod("setSyncStatus", int.class);
        setMeta = type.getMethod("setMetaData", String.class);
        setClient = type.getMethod("setClientDataId", String.class);
        setVersion = type.getMethod("setVersion", int.class);
        setData = type.getMethod("setData", String.class);
        getStart = type.getMethod("getStartTimestamp");
        getEnd = type.getMethod("getEndTimestamp");
        getData = type.getMethod("getData");
        getMode = type.getMethod("getSportMode");
        getDevice = type.getMethod("getDeviceUniqueId");
    }

    void write(Context context) throws Exception {
        String account = host.account();
        if (account == null || account.isBlank()) return;
        Object api = host.api();
        if (api == null) throw new IllegalStateException("SPORT_IMPORT_NOT_READY");
        Bundle band = OHealthDeviceHook.registeredSnapshot();
        if (band == null || band.getString("deviceId", "").isBlank()) return;
        String queued = band.getString("deviceId");
        String device = OHealthDeviceHook.healthId(queued);
        SharedPreferences written = context.getSharedPreferences("oplusband-sport-import", Context.MODE_PRIVATE);
        List<HealthRecord> tracks = history(context, account, queued, "sport_track");
        for (HealthRecord record : history(context, account, queued, "sport_session")) {
            HealthRecord track = match(record, tracks);
            String key = device + ":" + record.recordId;
            long occupied;
            try {
                occupied = written.getLong(key + ":end", 0);
            } catch (ClassCastException old) {
                occupied = 0;
            }
            String saved;
            try {
                saved = written.getString(key, "");
            } catch (ClassCastException old) {
                saved = "";
            }
            int trackLength = track == null ? 0 : track.track.length();
            long slot = nextSlot(record.endMs, saved, occupied, record.revision, trackLength);
            String mark = record.revision + ":" + trackLength + ":" + slot + ":u7";
            if (mark.equals(saved)) continue;
            if (!written.edit().putLong(key + ":end", slot).commit()) {
                throw new IllegalStateException("SPORT_IMPORT_MEMORY_FAILED");
            }
            try {
                if (store(api, account, queued, device,
                        OHealthStepWriter.deviceCategory(band.getString("modelId", "")),
                        record, track, slot, !saved.isEmpty())) {
                    if (!written.edit().putString(key, mark).commit()) {
                        throw new IllegalStateException("SPORT_IMPORT_MEMORY_FAILED");
                    }
                } else {
                    HookRuntime.log("OHEALTH_SPORT_UNCONFIRMED mode=" + record.value);
                }
            } catch (Exception failure) {
                HookRuntime.log("OHEALTH_SPORT_FAIL " + failure.getClass().getSimpleName());
            }
        }
    }

    private static HealthRecord match(HealthRecord session, List<HealthRecord> tracks) {
        HealthRecord best = null;
        for (HealthRecord track : tracks) {
            if (track.startMs >= session.endMs || track.endMs <= session.startMs) continue;
            if (best == null || track.value.intValue() > best.value.intValue()) best = track;
        }
        return best;
    }

    /**
     * Health keeps the first row for a start, end and mode, and a later insert does not replace
     * its track. A hidden row still occupies that key, so a replacement ends one millisecond later.
     */
    static long nextSlot(long sessionEnd, String saved, long occupied, int revision, int trackLength) {
        String prefix = revision + ":" + trackLength + ":";
        if (saved != null && saved.startsWith(prefix) && saved.endsWith(":u7")) {
            try {
                long marked = Long.parseLong(saved.substring(prefix.length(), saved.length() - 3));
                if (marked >= sessionEnd && marked - sessionEnd < 20) return marked;
            } catch (NumberFormatException | IndexOutOfBoundsException ignored) { }
        }
        if (occupied >= sessionEnd && occupied - sessionEnd < 20) return Math.min(occupied + 1, sessionEnd + 19);
        if (saved != null && !saved.isEmpty()) return sessionEnd + 1;
        return sessionEnd;
    }

    private boolean store(Object api, String account, String queued, String device, String category,
            HealthRecord record, HealthRecord track, long slot, boolean replacing) throws Exception {
        int mode = record.value.intValue();
        Object row = created.newInstance();
        setAccount.invoke(row, account);
        setDevice.invoke(row, device);
        setType.invoke(row, category);
        setStart.invoke(row, record.startMs);
        setEnd.invoke(row, slot);
        setMode.invoke(row, mode);
        setDisplay.invoke(row, 1);
        setSync.invoke(row, 0);
        setClient.invoke(row, record.recordId);
        if (record.timezone != null) setZone.invoke(row, record.timezone);
        String pace = track == null ? null : SportGpsParser.paceSeries(track.track);
        setMeta.invoke(row, metadata(record, mode, track != null, pace));
        if (track != null) {
            setVersion.invoke(row, 2);
            JSONObject data = new JSONObject().put("gps", track.track);
            if (pace != null) data.put("pace", pace);
            setData.invoke(row, data.toString());
        }
        if (replacing) {
            for (String id : queued.equals(device) ? List.of(device) : List.of(device, queued)) {
                hide(api, account, id, record.startMs, record.endMs, mode);
                if (slot != record.endMs) hide(api, account, id, record.startMs, slot - 1, mode);
            }
        }
        host.insertRows(api, TABLE, List.of(row));
        List<?> found = host.readRows(api, account, TABLE, device, record.startMs, slot, 0, false);
        for (Object saved : found) {
            if (!device.equals(getDevice.invoke(saved))) continue;
            if (((Number) getStart.invoke(saved)).longValue() != record.startMs) continue;
            if (((Number) getEnd.invoke(saved)).longValue() != slot) continue;
            if (((Number) getMode.invoke(saved)).intValue() != mode) continue;
            if (track != null) {
                Object data = getData.invoke(saved);
                if (!(data instanceof String text) || !text.contains("gps")) return false;
            }
            int samples = pace == null ? 0 : Math.max(0, (pace.split(",").length - 3) / 2);
            HookRuntime.log("OHEALTH_SPORT mode=" + mode + " pace=" + samples
                    + " best=" + (record.bestPace == null ? 0 : record.bestPace));
            return true;
        }
        return false;
    }

    private void hide(Object api, String account, String device, long start, long end, int mode) {
        try {
            host.deleteSport(api, account, device, start, end, mode);
        } catch (Exception ignored) {
            // A missing previous row still lets the new summary take its place.
        }
    }

    private static String metadata(HealthRecord record, int mode, boolean tracked, String pace)
            throws Exception {
        JSONObject json = new JSONObject();
        json.put("sportMode", mode);
        json.put("sportName", SportReportParser.name(mode));
        // OHealth multiplies seconds and kilocalories by 1000 before storing them.
        long seconds = record.duration != null ? record.duration
                : Math.max(1, (record.endMs - record.startMs) / 1000);
        json.put("totalTime", seconds * 1000L);
        if (record.calories != null) json.put("totalCalories", record.calories * 1000L);
        if (record.distance != null) json.put("totalDistance", record.distance);
        if (record.reps != null) json.put("totalSteps", record.reps);
        if (record.heart != null) json.put("avgHeartRate", record.heart);
        if (record.distance != null && record.distance > 0) {
            long secondsPerKm = Math.round(seconds * 1000.0 / record.distance);
            if (secondsPerKm >= 30 && secondsPerKm <= 7200) json.put("avgPace", secondsPerKm);
        }
        Integer bestPace = record.bestPace != null ? record.bestPace : SportGpsParser.fastestPace(pace);
        if (bestPace != null) json.put("bestPace", bestPace);
        if (tracked) json.put("hasTrackPoint", true);
        return json.toString();
    }

    private List<HealthRecord> history(Context context, String account, String device, String kind) throws Exception {
        List<HealthRecord> records = new ArrayList<>();
        String after = null;
        for (;;) {
            Uri uri = after == null ? HealthQueueProvider.RECORDS_URI
                    : HealthQueueProvider.RECORDS_URI.buildUpon().appendQueryParameter("after", after).build();
            int count = 0;
            try (Cursor rows = context.getContentResolver().query(uri, COLUMNS,
                    "account=? AND deviceId=? AND kind=? AND startMs<? AND endMs>?",
                    new String[] {account, device, kind, Long.toString(Long.MAX_VALUE), "0"}, null)) {
                if (rows == null) throw new IllegalStateException("SPORT_HISTORY_UNAVAILABLE");
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
}
