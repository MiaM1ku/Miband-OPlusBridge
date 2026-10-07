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
            idxWarning;
    private final Method hrAccount, hrDate, hrMin, hrMax, hrLow, hrHigh, hrAvg, hrWarning;
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
        hrAccount = hrStatClass.getMethod("setSsoid", String.class);
        hrDate = hrStatClass.getMethod("setDate", int.class);
        hrMin = hrStatClass.getMethod("setMinHeartRate", int.class);
        hrMax = hrStatClass.getMethod("setMaxHeartRate", int.class);
        hrLow = hrStatClass.getMethod("setReasonableRangeLow", int.class);
        hrHigh = hrStatClass.getMethod("setReasonableRangeHigh", int.class);
        hrAvg = hrStatClass.getMethod("setAvgSleepHeartRate", int.class);
        hrWarning = hrStatClass.getMethod("setWarningNumber", int.class);

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
        if (heart == null && spo2 == null) return;
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
        idxWarning.invoke(index, 0);
        host.insertRows(api, TABLE_INDEX, List.of(index));

        if (heart != null && heart.min() >= 40 && heart.max() <= 220) {
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

    /** Writes the 1052 sleep day stat with a piece per stage. */
    void writeDayStat(Object api, String account, String device, OHealthSleepPlan.Night night)
            throws Exception {
        List<Object> pieces = pieces(account, device, night);
        int sleep = (int) night.sleepMinutes();
        Object main = mainNew.newInstance();
        mainAccount.invoke(main, account);
        mainDevice.invoke(main, device);
        mainDate.invoke(main, night.date());
        mainBefore.invoke(main, night.fallAsleepMs() - 10_800_000L);
        mainIn.invoke(main, night.fallAsleepMs());
        mainOut.invoke(main, night.wakeMs());
        mainSleep.invoke(main, sleep);
        mainDeep.invoke(main, (int) night.deepMinutes());
        mainLight.invoke(main, (int) night.lightMinutes());
        mainRem.invoke(main, (int) night.remMinutes());
        mainWake.invoke(main, (int) night.wakeMinutes());
        mainCount.invoke(main, 0);
        mainPieces.invoke(main, pieces);
        mainSource.invoke(main, 1);
        Object frg = frgNew.newInstance(account, device, night.date(), night.fallAsleepMs(),
                night.wakeMs(), sleep, (int) night.deepMinutes(), (int) night.lightMinutes(),
                (int) night.remMinutes(), (int) night.wakeMinutes(), 0, null, pieces, 1);
        Object row = dayNew.newInstance(account, device, night.date(), night.fallAsleepMs(),
                night.wakeMs(), sleep, (int) night.deepMinutes(), (int) night.lightMinutes(),
                (int) night.remMinutes(), (int) night.wakeMinutes(), 0, false, 0, main,
                List.of(frg), 0, 1, 0, night.fallAsleepMs(), night.wakeMs());
        host.insertRows(api, TABLE_DAY_STAT, List.of(row));
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
}
