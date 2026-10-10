// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the sleep summary rows an OPPO band uploads and the bridge skipped: the sleep index (1049),
 * the sleep heart-rate summary (1071) and the sleep day stat (1052). The values are derived from the
 * band's sleep window; there is no band source for breath or HRV, so those stay unset.
 */
final class OHealthSleepDerivedWriter {
    static final int TABLE_INDEX = 1049;
    static final int TABLE_HR_STAT = 1071;
    static final int TABLE_DAY_STAT = 1052;
    private static final String PKG = "com.heytap.databaseengine.model.";
    private static final String DAY_PKG = PKG + "sleepdaystat.";

    /** Min/max/mean of one metric. */
    record Summary(int count, int min, int max, int mean) {}

    private final OHealthHealthImportHook.HostContract host;
    private final Class<?> indexClass;
    private final Class<?> hrStatClass;
    private final Constructor<?> indexNew;
    private final Constructor<?> hrStatNew;
    private final Method idxAccount, idxDevice, idxTime, idxSpo2, idxAvgHeart, idxRangeLow, idxRangeHigh,
            idxWarning, idxBasalBreath, idxBreathLow, idxBreathHigh, idxBreathReasonableLow,
            idxBreathReasonableHigh, idxGetBreathHigh;
    private final Method hrAccount, hrDate, hrMin, hrMax, hrLow, hrHigh, hrAvg, hrWarning, hrGetDate;
    private final Class<?> dayClass;
    private final Class<?> mainClass;
    private final Class<?> frgClass;
    private final Constructor<?> dayNew;
    private final Constructor<?> mainNew;
    private final Constructor<?> frgNew;
    private final Constructor<?> pieceNew;
    private final Method dayAccount, dayDevice, dayDate, dayIn, dayOut, daySleep, dayDeep, dayLight,
            dayRem, dayWake, dayCount, dayCalibrated, dayScore, dayMain, dayFrg, dayVersion, daySource,
            dayStandard, dayRestIn, dayRestOut;
    private final Method dayGetDate, dayGetDevice, dayGetIn, dayGetOut, dayGetSleep, dayGetDeep,
            dayGetLight, dayGetRem, dayGetWake, dayGetMain, dayGetFrgList;
    private final Method mainAccount, mainDevice, mainDate, mainBefore, mainIn, mainOut, mainSleep,
            mainDeep, mainLight, mainRem, mainWake, mainCount, mainPieces, mainSource;
    private final Method frgAccount, frgDevice, frgDate, frgIn, frgOut, frgSleep, frgDeep, frgLight,
            frgRem, frgWake, frgCount, frgPieces, frgSource;

    OHealthSleepDerivedWriter(OHealthHealthImportHook.HostContract host) throws ReflectiveOperationException {
        this.host = host;
        ClassLoader loader = host.loader;
        indexClass = Class.forName(PKG + "SleepIndex", false, loader);
        hrStatClass = Class.forName(PKG + "newsleep.SleepHeartRateStat", false, loader);
        indexNew = indexClass.getConstructor();
        hrStatNew = hrStatClass.getConstructor();
        idxAccount = indexClass.getMethod("setSsoid", String.class);
        idxDevice = indexClass.getMethod("setDeviceUniqueId", String.class);
        idxTime = indexClass.getMethod("setDataTimestamp", long.class);
        idxSpo2 = indexClass.getMethod("setAvgSleepSpo2", Integer.class);
        idxAvgHeart = indexClass.getMethod("setAvgSleepHeartRate", Integer.class);
        idxRangeLow = indexClass.getMethod("setSleepHeartRateRangeLow", Integer.class);
        idxRangeHigh = indexClass.getMethod("setSleepHeartRateRangeHigh", Integer.class);
        idxWarning = indexClass.getMethod("setHasHeartRateWarning", int.class);
        idxBasalBreath = optional(indexClass, "setBasalBreathe", Integer.class);
        idxBreathLow = optional(indexClass, "setAvgSleepBreathRangeLow", Integer.class);
        idxBreathHigh = optional(indexClass, "setAvgSleepBreathRangeHigh", Integer.class);
        idxBreathReasonableLow = optional(indexClass, "setBreatheReasonableRangeLow", Integer.class);
        idxBreathReasonableHigh = optional(indexClass, "setBreatheReasonableRangeHigh", Integer.class);
        idxGetBreathHigh = optional(indexClass, "getAvgSleepBreathRangeHigh");
        hrAccount = hrStatClass.getMethod("setSsoid", String.class);
        hrDate = hrStatClass.getMethod("setDate", int.class);
        hrMin = hrStatClass.getMethod("setMinHeartRate", int.class);
        hrMax = hrStatClass.getMethod("setMaxHeartRate", int.class);
        hrLow = hrStatClass.getMethod("setReasonableRangeLow", int.class);
        hrHigh = hrStatClass.getMethod("setReasonableRangeHigh", int.class);
        hrAvg = hrStatClass.getMethod("setAvgSleepHeartRate", int.class);
        hrWarning = hrStatClass.getMethod("setWarningNumber", int.class);
        hrGetDate = getter(hrStatClass, "getDate", int.class);

        dayClass = Class.forName(DAY_PKG + "SleepDayStat", false, loader);
        mainClass = Class.forName(DAY_PKG + "SleepMainData", false, loader);
        frgClass = Class.forName(DAY_PKG + "SleepDayFrgData", false, loader);
        Class<?> pieceClass = Class.forName(DAY_PKG + "SleepPiece", false, loader);
        dayNew = dayClass.getConstructor(String.class, String.class, int.class, long.class, long.class,
                int.class, int.class, int.class, int.class, int.class, int.class, boolean.class, int.class,
                mainClass, List.class, int.class, int.class, int.class, long.class, long.class);
        mainNew = mainClass.getConstructor();
        frgNew = frgClass.getConstructor(String.class, String.class, int.class, long.class, long.class,
                int.class, int.class, int.class, int.class, int.class, int.class, Integer.class, List.class,
                int.class);
        pieceNew = pieceClass.getConstructor(String.class, String.class, long.class, long.class,
                int.class, boolean.class);
        dayAccount = dayClass.getMethod("setSsoid", String.class);
        dayDevice = dayClass.getMethod("setDeviceUniqueId", String.class);
        dayDate = dayClass.getMethod("setDate", int.class);
        dayIn = dayClass.getMethod("setSleepInTime", long.class);
        dayOut = dayClass.getMethod("setSleepOutTime", long.class);
        daySleep = dayClass.getMethod("setTotalSleepTime", int.class);
        dayDeep = dayClass.getMethod("setTotalDeepSleepTime", int.class);
        dayLight = dayClass.getMethod("setTotalLightlySleepTime", int.class);
        dayRem = dayClass.getMethod("setTotalREMSleepTime", int.class);
        dayWake = dayClass.getMethod("setTotalWakeTime", int.class);
        dayCount = dayClass.getMethod("setWakeCount", int.class);
        dayCalibrated = dayClass.getMethod("setCalibrated", boolean.class);
        dayScore = dayClass.getMethod("setScore", int.class);
        dayMain = dayClass.getMethod("setSleepMainData", mainClass);
        dayFrg = dayClass.getMethod("setSleepDayFrgDataList", List.class);
        dayVersion = dayClass.getMethod("setDataVersion", int.class);
        daySource = dayClass.getMethod("setSource", int.class);
        dayStandard = dayClass.getMethod("setStandardTime", int.class);
        dayRestIn = dayClass.getMethod("setRestInTime", long.class);
        dayRestOut = dayClass.getMethod("setRestOutTime", long.class);
        dayGetDate = getter(dayClass, "getDate", int.class);
        dayGetDevice = getter(dayClass, "getDeviceUniqueId", String.class);
        dayGetIn = getter(dayClass, "getSleepInTime", long.class);
        dayGetOut = getter(dayClass, "getSleepOutTime", long.class);
        dayGetSleep = getter(dayClass, "getTotalSleepTime", int.class);
        dayGetDeep = getter(dayClass, "getTotalDeepSleepTime", int.class);
        dayGetLight = getter(dayClass, "getTotalLightlySleepTime", int.class);
        dayGetRem = getter(dayClass, "getTotalREMSleepTime", int.class);
        dayGetWake = getter(dayClass, "getTotalWakeTime", int.class);
        dayGetMain = getter(dayClass, "getSleepMainData", mainClass);
        dayGetFrgList = getter(dayClass, "getSleepDayFrgDataList", List.class);
        mainAccount = mainClass.getMethod("setSsoid", String.class);
        mainDevice = mainClass.getMethod("setDeviceUniqueId", String.class);
        mainDate = mainClass.getMethod("setDate", int.class);
        mainBefore = mainClass.getMethod("setSleep3HoursBeforeTime", long.class);
        mainIn = mainClass.getMethod("setSleepInTime", long.class);
        mainOut = mainClass.getMethod("setSleepOutTime", long.class);
        mainSleep = mainClass.getMethod("setTotalSleepTime", int.class);
        mainDeep = mainClass.getMethod("setTotalDeepSleepTime", int.class);
        mainLight = mainClass.getMethod("setTotalLightlySleepTime", int.class);
        mainRem = mainClass.getMethod("setTotalREMSleepTime", int.class);
        mainWake = mainClass.getMethod("setTotalWakeTime", int.class);
        mainCount = mainClass.getMethod("setWakeCount", int.class);
        mainPieces = mainClass.getMethod("setSleepUnitDataList", List.class);
        mainSource = mainClass.getMethod("setSource", int.class);
        frgAccount = frgClass.getMethod("setSsoid", String.class);
        frgDevice = frgClass.getMethod("setDeviceUniqueId", String.class);
        frgDate = frgClass.getMethod("setDate", int.class);
        frgIn = frgClass.getMethod("setSleepInTime", long.class);
        frgOut = frgClass.getMethod("setSleepOutTime", long.class);
        frgSleep = frgClass.getMethod("setTotalSleepTime", int.class);
        frgDeep = frgClass.getMethod("setTotalDeepSleepTime", int.class);
        frgLight = frgClass.getMethod("setTotalLightlySleepTime", int.class);
        frgRem = frgClass.getMethod("setTotalREMSleepTime", int.class);
        frgWake = frgClass.getMethod("setTotalWakeTime", int.class);
        frgCount = frgClass.getMethod("setWakeCount", int.class);
        frgPieces = frgClass.getMethod("setSleepUnitDataList", List.class);
        frgSource = frgClass.getMethod("setSource", int.class);
    }

    static Summary summarize(int[] values, int used) {
        if (used <= 0) return null;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        for (int i = 0; i < used; i++) {
            min = Math.min(min, values[i]);
            max = Math.max(max, values[i]);
            sum += values[i];
        }
        return new Summary(used, min, max, (int) Math.round((double) sum / used));
    }

    /** Writes the 1049 and 1071 rows. Values outside the band's own accept ranges are dropped. */
    void write(Object api, String account, String device, OHealthSleepPlan.Night night,
            Summary heart, Summary spo2) throws Exception {
        write(api, account, device, night, heart, spo2, null, true);
    }

    void write(Object api, String account, String device, OHealthSleepPlan.Night night,
            Summary heart, Summary spo2, Summary breath) throws Exception {
        write(api, account, device, night, heart, spo2, breath, true);
    }

    void write(Object api, String account, String device, OHealthSleepPlan.Night night,
            Summary heart, Summary spo2, Summary breath, boolean writeHrStat) throws Exception {
        if (heart == null && spo2 == null && breath == null) return;
        Object index = indexNew.newInstance();
        idxAccount.invoke(index, account);
        idxDevice.invoke(index, device);
        idxTime.invoke(index, night.wakeMs());
        if (spo2 != null) idxSpo2.invoke(index, Integer.valueOf(spo2.mean()));
        if (heart != null && heart.min() >= 40 && heart.max() <= 220) {
            idxAvgHeart.invoke(index, Integer.valueOf(heart.mean()));
            idxRangeLow.invoke(index, Integer.valueOf(heart.min()));
            idxRangeHigh.invoke(index, Integer.valueOf(heart.max()));
        }
        if (breath != null && breath.min() >= 60 && breath.max() <= 500) {
            if (idxBasalBreath != null) idxBasalBreath.invoke(index, Integer.valueOf(breath.mean()));
            if (idxBreathLow != null) idxBreathLow.invoke(index, Integer.valueOf(breath.min()));
            if (idxBreathHigh != null) idxBreathHigh.invoke(index, Integer.valueOf(breath.max()));
            if (idxBreathReasonableLow != null) idxBreathReasonableLow.invoke(index, Integer.valueOf(120));
            if (idxBreathReasonableHigh != null) idxBreathReasonableHigh.invoke(index, Integer.valueOf(200));
        }
        idxWarning.invoke(index, 0);
        host.insertRows(api, TABLE_INDEX, List.of(index));

        if (writeHrStat && heart != null && heart.min() >= 40 && heart.max() <= 220) {
            Object stat = hrStatNew.newInstance();
            hrAccount.invoke(stat, account);
            hrDate.invoke(stat, night.date());
            hrMin.invoke(stat, heart.min());
            hrMax.invoke(stat, heart.max());
            hrLow.invoke(stat, heart.min());
            hrHigh.invoke(stat, heart.max());
            hrAvg.invoke(stat, heart.mean());
            hrWarning.invoke(stat, 0);
            host.insertRows(api, TABLE_HR_STAT, List.of(stat));
        }
    }

    /** Writes the 1052 sleep day stat with child fragments for all sessions and main session data. */
    void writeDayStat(Object api, String account, String device, OHealthSleepPlan.Day day)
            throws Exception {
        List<Object> frgList = new ArrayList<>();
        for (OHealthSleepPlan.Night night : day.nights()) {
            List<Object> sessionPieces = pieces(account, device, night);
            int sessionSleep = (int) night.sleepMinutes();
            Object frg = frgNew.newInstance(account, device, night.date(), night.fallAsleepMs(),
                    night.wakeMs(), sessionSleep, (int) night.deepMinutes(), (int) night.lightMinutes(),
                    (int) night.remMinutes(), (int) night.wakeMinutes(), 0, null, sessionPieces, 1);
            frgList.add(frg);
        }

        Object main = null;
        OHealthSleepPlan.Night mainSession = day.mainSession();
        if (mainSession != null) {
            List<Object> mainPieceList = pieces(account, device, mainSession);
            int mainSleepMin = (int) mainSession.sleepMinutes();
            main = mainNew.newInstance();
            mainAccount.invoke(main, account);
            mainDevice.invoke(main, device);
            mainDate.invoke(main, mainSession.date());
            mainBefore.invoke(main, mainSession.fallAsleepMs() - 10_800_000L);
            mainIn.invoke(main, mainSession.fallAsleepMs());
            mainOut.invoke(main, mainSession.wakeMs());
            mainSleep.invoke(main, mainSleepMin);
            mainDeep.invoke(main, (int) mainSession.deepMinutes());
            mainLight.invoke(main, (int) mainSession.lightMinutes());
            mainRem.invoke(main, (int) mainSession.remMinutes());
            mainWake.invoke(main, (int) mainSession.wakeMinutes());
            mainCount.invoke(main, 0);
            mainPieces.invoke(main, mainPieceList);
            mainSource.invoke(main, 1);
        }

        int totalSleep = (int) day.sleepMinutes();
        Object row = dayNew.newInstance(account, device, day.date(), day.fallAsleepMs(),
                day.wakeMs(), totalSleep, (int) day.deepMinutes(), (int) day.lightMinutes(),
                (int) day.remMinutes(), (int) day.wakeMinutes(), 0, false, 0, main,
                frgList, 0, 1, 0, day.fallAsleepMs(), day.wakeMs());
        host.insertRows(api, TABLE_DAY_STAT, List.of(row));
    }

    void writeDayStat(Object api, String account, String device, OHealthSleepPlan.Night night)
            throws Exception {
        writeDayStat(api, account, device, OHealthSleepPlan.days(List.of(night)).get(0));
    }

    boolean dayStatMatches(List<?> rows, String device, String previous, OHealthSleepPlan.Day day)
            throws ReflectiveOperationException {
        if (rows == null) return false;
        for (Object row : rows) {
            if (!dayClass.isInstance(row) || day.date() != (Integer) dayGetDate.invoke(row)) continue;
            String owner = (String) dayGetDevice.invoke(row);
            if (owner != null && !owner.isBlank() && !device.equals(owner) && !owner.equals(previous)) continue;
            if ((int) day.sleepMinutes() != (Integer) dayGetSleep.invoke(row)
                    || (int) day.deepMinutes() != (Integer) dayGetDeep.invoke(row)
                    || (int) day.lightMinutes() != (Integer) dayGetLight.invoke(row)
                    || (int) day.remMinutes() != (Integer) dayGetRem.invoke(row)
                    || (int) day.wakeMinutes() != (Integer) dayGetWake.invoke(row)
                    || day.fallAsleepMs() != (Long) dayGetIn.invoke(row)
                    || day.wakeMs() != (Long) dayGetOut.invoke(row)) {
                return false;
            }
            List<?> frgs = (List<?>) dayGetFrgList.invoke(row);
            if (frgs == null || frgs.size() != day.nights().size()) return false;
            Object main = dayGetMain.invoke(row);
            if ((day.mainSession() == null) != (main == null)) return false;
            return true;
        }
        return false;
    }

    boolean dayOwnedByOther(List<?> rows, int date, String device, String previous)
            throws ReflectiveOperationException {
        if (rows == null) return false;
        for (Object row : rows) {
            if (!dayClass.isInstance(row) || date != (Integer) dayGetDate.invoke(row)) continue;
            String owner = (String) dayGetDevice.invoke(row);
            if (owner == null || owner.isBlank() || device.equals(owner) || owner.equals(previous)) continue;
            return true;
        }
        return false;
    }

    boolean hrStatMatches(List<?> rows, int date) throws ReflectiveOperationException {
        if (rows == null) return false;
        for (Object row : rows) {
            if (!hrStatClass.isInstance(row)) continue;
            if (date == (Integer) hrGetDate.invoke(row)) return true;
        }
        return false;
    }

    boolean indexHasBreath(List<?> rows) {
        if (rows == null || idxGetBreathHigh == null) return false;
        for (Object row : rows) {
            if (!indexClass.isInstance(row)) continue;
            try {
                Integer high = (Integer) idxGetBreathHigh.invoke(row);
                if (high != null && high >= 60 && high <= 500) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private List<Object> pieces(String account, String device, OHealthSleepPlan.Night night)
            throws ReflectiveOperationException {
        List<Object> pieces = new ArrayList<>();
        for (OHealthSleepPlan.Segment segment : night.segments()) {
            pieces.add(pieceNew.newInstance(account, device, segment.startMs(), segment.endMs(),
                    segment.sleepState(), false));
        }
        return pieces;
    }

    private static Method optional(Class<?> type, String name, Class<?>... parameters) {
        try { return type.getMethod(name, parameters); }
        catch (NoSuchMethodException missing) { return null; }
    }

    private static Method getter(Class<?> type, String name, Class<?> returnType) throws NoSuchMethodException {
        Method method = type.getMethod(name);
        if (!returnType.isAssignableFrom(method.getReturnType())) {
            throw new NoSuchMethodException("DERIVED_MODEL_CONTRACT " + name);
        }
        return method;
    }
}
