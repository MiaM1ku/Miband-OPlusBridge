// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Verifies dual-version 6.9.x / 6.11.7 host compatibility helpers. */
public final class OHealth6117CompatTest {
    public static final class Dao69 {
        public List<String> query(String account, int start, int end) {
            return List.of("69:" + account + ":" + start + ":" + end);
        }
    }

    public static final class Dao6117 {
        public List<String> i(int start, int end, String account) {
            return List.of("6117:" + account + ":" + start + ":" + end);
        }
    }

    @Test public void stepAuditDaoSupportsBothArgumentOrders() throws Exception {
        Method m69 = Dao69.class.getMethod("query", String.class, int.class, int.class);
        assertEquals(List.of("69:acc:20261123:20261123"),
                OHealthStepAggregateHook.NativeAccess.invokeQueryDays(m69, false, new Dao69(), "acc", 20261123));

        Method m6117 = Dao6117.class.getMethod("i", int.class, int.class, String.class);
        assertEquals(List.of("6117:acc:20261123:20261123"),
                OHealthStepAggregateHook.NativeAccess.invokeQueryDays(m6117, true, new Dao6117(), "acc", 20261123));
    }

    public static class BaseStat6117 {
        protected final String db = "shared-db";
    }

    public static final class SubStat6117 extends BaseStat6117 {
        private final String sportDataStatDao = "dao-6117";
    }

    @Test public void stepAuditFindsInheritedProtectedAndPrivateFields() throws Exception {
        SubStat6117 instance = new SubStat6117();
        assertEquals("shared-db",
                OHealthStepAggregateHook.NativeAccess.findField(SubStat6117.class, "db").get(instance));
        assertEquals("dao-6117",
                OHealthStepAggregateHook.NativeAccess.findField(SubStat6117.class, "sportDataStatDao").get(instance));
    }

    @Test public void deviceRowsMap6117ItemTypesToNativeActivities() {
        assertEquals("com.heytap.health.device.tab.notify.NotifySettingsActivity",
                OHealthDeviceHook.nativePageActivity(38));
        assertEquals("com.heytap.health.daily.ui.DailyActivityDetailActivity",
                OHealthDeviceHook.nativePageActivity(39));
        assertEquals("com.heytap.health.daily.ui.DailyActivityDetailActivity",
                OHealthDeviceHook.nativePageActivity(61));
        assertEquals("com.heytap.health.heartrate.ui.HeartRateHistoryActivity",
                OHealthDeviceHook.nativePageActivity(62));
        assertEquals("com.heytap.health.bloodoxygen.ui.BloodOxygenHistoryActivity",
                OHealthDeviceHook.nativePageActivity(63));
        assertEquals("com.heytap.health.sleep.SleepHistoryActivity",
                OHealthDeviceHook.nativePageActivity(65));
        assertEquals("com.heytap.health.stress.ui.StressHistoryActivity",
                OHealthDeviceHook.nativePageActivity(77));
        assertNull(OHealthDeviceHook.nativePageActivity(79));
    }
}
