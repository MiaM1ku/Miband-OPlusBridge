// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OHealthStepAggregateHookTest {
    private static Map<String, String> row(int id, String device, int mode, int steps) {
        Map<String, String> row = new HashMap<>();
        row.put("_id", Integer.toString(id)); row.put("ssoid", "account");
        row.put("date", "20261101"); row.put("device_unique_id", device);
        row.put("sport_mode", Integer.toString(mode)); row.put("total_steps", Integer.toString(steps));
        row.put("total_calories", "60000"); row.put("total_distance", "117");
        row.put("total_workout_minutes", "13"); row.put("total_move_about_times", "2");
        row.put("sync_status", "1"); row.put("updated", "0"); row.put("modified_time", "1234");
        row.put("update_timestamp", "1234"); row.put("steps_goal_complete", "1");
        row.put("day_goal_complete", "0"); row.put("current_day_steps_goal", "6000");
        return row;
    }
    private static List<Map<String, String>> originals() {
        return List.of(row(1, null, -4, 11292), row(2, null, -2, 11292), row(3, "MAC", -3, 221));
    }
    private static List<Map<String, String>> repaired(List<Map<String, String>> before) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, String> row : before) {
            Map<String, String> copy = new HashMap<>(row);
            if (copy.get("device_unique_id") == null) {
                copy.put("total_steps", "221"); copy.put("sync_status", "0"); copy.put("updated", "1");
                copy.put("modified_time", "5678"); copy.put("update_timestamp", "5678");
                copy.put("steps_goal_complete", "0");
            }
            rows.add(copy);
        }
        return rows;
    }

    private static final class FakeStatFactory implements OHealthStepAggregateHook.StatFactory {
        final Object stat = new Object();
        Object database;
        int calls;
        FakeStatFactory(Object database) { this.database = database; }
        @Override public Object create() { calls++; return stat; }
        @Override public Object database(Object stat) { return database; }
    }

    @Test public void unopenedSharedDatabaseDefersFactoryInitialization() throws Exception {
        Object database = new Object();
        var factory = new FakeStatFactory(database);
        assertNull(OHealthStepAggregateHook.readyStat(null, false, null, factory));
        assertNull(OHealthStepAggregateHook.readyStat(database, false, null, factory));
        assertEquals(0, factory.calls);
    }

    @Test public void readyDatabaseInitializesAnEmptyOfficialStatCache() throws Exception {
        Object database = new Object();
        var factory = new FakeStatFactory(database);
        assertSame(factory.stat, OHealthStepAggregateHook.readyStat(database, true, null, factory));
        assertEquals(1, factory.calls);
    }

    @Test public void warmStatIsReusedWithTheSameSharedDatabase() throws Exception {
        Object database = new Object();
        var factory = new FakeStatFactory(database);
        assertSame(factory.stat, OHealthStepAggregateHook.readyStat(database, true, factory.stat, factory));
        assertEquals(0, factory.calls);
    }

    @Test public void anotherDatabaseInstanceDefersMutation() {
        var factory = new FakeStatFactory(new Object());
        assertThrows(IllegalStateException.class,
                () -> OHealthStepAggregateHook.readyStat(new Object(), true, null, factory));
    }

    @Test public void coldAttachUsesTheProvidedContextUntilApplicationIsReady() {
        Object attached = new Object();
        assertSame(attached, OHealthStepAggregateHook.attachedContext(attached, null));
        assertThrows(NullPointerException.class, () -> OHealthStepAggregateHook.attachedContext(null, null));
    }

    @Test public void warmReloadUsesTheReadyApplicationContext() {
        Object attached = new Object(), application = new Object();
        assertSame(application, OHealthStepAggregateHook.attachedContext(attached, application));
    }

    @Test public void stepsAndNativeDerivedMetadataMayChangeAfterConfirmation() {
        var before = originals();
        OHealthStepAggregateHook.verifyPreserved(before, repaired(before), 221);
        assertEquals("11292", before.get(0).get("total_steps"));
    }

    @Test public void everyOtherFieldIsPreservedIncludingUnknownHostColumns() {
        var before = originals();
        for (String field : List.of("_id", "ssoid", "date", "device_unique_id", "sport_mode", "total_calories",
                "total_distance", "total_workout_minutes", "total_move_about_times", "current_day_steps_goal")) {
            var after = repaired(before);
            after.get(0).put(field, "changed");
            assertThrows("Preserve " + field, IllegalStateException.class,
                    () -> OHealthStepAggregateHook.verifyPreserved(before, after, 221));
        }
        var after = repaired(before);
        after.get(0).put("future_metric", "1");
        assertThrows(IllegalStateException.class, () -> OHealthStepAggregateHook.verifyPreserved(before, after, 221));
    }

    @Test public void failedOrPartiallyAppliedStepCorrectionRequiresRollback() {
        var before = originals();
        var after = repaired(before);
        after.get(1).put("total_steps", "11292");
        assertThrows(IllegalStateException.class, () -> OHealthStepAggregateHook.verifyPreserved(before, after, 221));
        after.get(1).put("total_steps", "220");
        assertThrows(IllegalStateException.class, () -> OHealthStepAggregateHook.verifyPreserved(before, after, 221));
    }

    @Test public void deviceRowsAndUncorrectedRowsRemainExact() {
        var before = originals();
        var after = repaired(before);
        after.get(2).put("total_steps", "220");
        assertThrows(IllegalStateException.class, () -> OHealthStepAggregateHook.verifyPreserved(before, after, 221));
        var changedDevice = repaired(before);
        changedDevice.get(2).put("sync_status", "0");
        assertThrows(IllegalStateException.class, () -> OHealthStepAggregateHook.verifyPreserved(before, changedDevice, 221));
    }

    @Test public void changedRowSetAndRowIdentityRequireRollback() {
        var before = originals();
        var after = repaired(before);
        after.remove(0);
        assertThrows(IllegalStateException.class, () -> OHealthStepAggregateHook.verifyPreserved(before, after, 221));
        var reordered = repaired(before);
        java.util.Collections.swap(reordered, 0, 1);
        assertThrows(IllegalStateException.class, () -> OHealthStepAggregateHook.verifyPreserved(before, reordered, 221));
    }

    @Test public void lowerPhoneContributionRemainsItsOwnValue() {
        var before = List.of(row(1, null, -4, 100), row(2, null, -2, 11292));
        List<Map<String, String>> after = List.of(new HashMap<>(before.get(0)), new HashMap<>(before.get(1)));
        after.get(1).put("total_steps", "221");
        OHealthStepAggregateHook.verifyPreserved(before, after, 221);
        after.get(0).put("total_steps", "221");
        assertThrows(IllegalStateException.class, () -> OHealthStepAggregateHook.verifyPreserved(before, after, 221));
    }

    @Test public void alreadyCorrectedRowsVerifyIdempotently() {
        var correct = List.of(row(1, null, -4, 221), row(2, null, -2, 221), row(3, "MAC", -3, 221));
        OHealthStepAggregateHook.verifyPreserved(correct, correct, 221);
    }

    public static class BaseEntity {
        private long sportStatId = 91;
        private String account = "account";
    }
    public static final class Entity extends BaseEntity {
        private int steps = 11292;
        private long calories = 60000;
        private Long fragmentedWorkouts = 3L;
        private String timezone = "+08:00";
        public Entity() { }
    }
    public static final class MutableEntity {
        public List<String> mutable = new ArrayList<>();
        public MutableEntity() { }
    }

    @Test public void clonedEntityKeepsPrimaryKeyInheritedFieldsAndMetrics() throws Exception {
        Entity original = new Entity();
        Entity copied = (Entity) OHealthStepAggregateHook.copyEntity(original);
        assertNotSame(original, copied);
        assertEquals(91, ((BaseEntity) copied).sportStatId);
        assertEquals("account", ((BaseEntity) copied).account);
        assertEquals(original.calories, copied.calories);
        assertEquals(original.fragmentedWorkouts, copied.fragmentedWorkouts);
        assertEquals(original.timezone, copied.timezone);
        copied.steps = 221;
        assertEquals(11292, original.steps);
    }

    @Test public void unknownMutableEntityContractDefersCorrection() {
        assertThrows(IllegalStateException.class, () -> OHealthStepAggregateHook.copyEntity(new MutableEntity()));
    }

    @Test public void mutationTokenBindsCurrentAccountDateModePrimaryKeyAndOldSteps() {
        var token = new OHealthStepAggregateHook.Mutation(new Object(), new Object(), "account", 20261101,
                -2, 91L, 11292, 221);
        assertTrue(token.matchesValues("account", null, 20261101, -2, 91L, 11292));
        assertTrue(token.matchesValues("account", "", 20261101, -2, 91L, 11292));
        assertFalse(token.matchesValues("other", null, 20261101, -2, 91L, 11292));
        assertFalse(token.matchesValues("account", "MAC", 20261101, -2, 91L, 11292));
        assertFalse(token.matchesValues("account", null, 20261102, -2, 91L, 11292));
        assertFalse(token.matchesValues("account", null, 20261101, -4, 91L, 11292));
        assertFalse(token.matchesValues("account", null, 20261101, -2, 92L, 11292));
        assertFalse(token.matchesValues("account", null, 20261101, -2, 91L, 12000));
        token.applied = true;
        assertFalse(token.matchesValues("account", null, 20261101, -2, 91L, 11292));
    }
}
