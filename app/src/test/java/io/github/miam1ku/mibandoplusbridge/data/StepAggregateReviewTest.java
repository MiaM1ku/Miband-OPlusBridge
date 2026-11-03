// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import io.github.miam1ku.mibandoplusbridge.data.StepAggregateReview.Daily;
import io.github.miam1ku.mibandoplusbridge.data.StepAggregateReview.SavedMinute;
import io.github.miam1ku.mibandoplusbridge.data.StepAggregateReview.SourceMinute;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class StepAggregateReviewTest {
    private static final String ACCOUNT = "account-1";
    private static final String QUEUED = "queued-band";
    private static final String NATIVE = "02:00:00:00:00:11";
    private static final int DATE = 20261101;
    private static final long START = 1793462400000L;
    private static final long END = START + 86_400_000L;
    private static final long AT = START + 20 * 60_000L;

    private static final class Fixture {
        String account = ACCOUNT, queued = QUEUED, nativeDevice = NATIVE, zone = "Asia/Shanghai";
        int date = DATE, minuteMode = 1;
        long start = START, steps = 221, now = END;
        final List<SourceMinute> sources = new ArrayList<>();
        final List<SavedMinute> saved = new ArrayList<>();
        final List<Daily> days = new ArrayList<>();
        Fixture() {
            for (int i = 0; i < 1440; i++) {
                long at = START + i * 60_000L;
                sources.add(new SourceMinute(QUEUED, at, at + 60_000L, i == 20 ? 221 : 0,
                        i == 20 ? 60 : 0, i == 20 ? 117 : 0, "+08:00"));
            }
            saved.add(new SavedMinute(ACCOUNT, NATIVE, AT, AT + 60_000L, 221, 60000, 117, 1, 1, "+0800"));
            days.add(new Daily(ACCOUNT, NATIVE, DATE, -3, 221, 60000, 117));
            days.add(new Daily(ACCOUNT, NATIVE, DATE, 1, 221, 60000, 117));
            days.add(new Daily(ACCOUNT, null, DATE, -2, 11292, 60000, 117));
            days.add(new Daily(ACCOUNT, null, DATE, -4, 11292, 60000, 61));
        }
        StepAggregateReview assess() {
            return StepAggregateReview.assess(account, queued, nativeDevice, date, start, zone,
                    steps, minuteMode, now, sources, saved, days);
        }
    }

    @Test public void novemberFirstEvidenceProducesManualReviewOfBothOldTotals() throws Exception {
        Fixture f = new Fixture();
        StepAggregateReview result = f.assess();
        assertNotNull(result);
        assertEquals(221, result.expectedSteps());
        assertEquals(11292, result.combinedSteps());
        assertEquals(11292, result.phoneSteps());
        assertEquals(DATE, result.date());
        assertEquals(QUEUED, result.queuedDevice());
        assertEquals(NATIVE, result.nativeDevice());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(ACCOUNT.getBytes(StandardCharsets.UTF_8))), result.accountHash());
        assertTrue(result.fingerprint().matches("[0-9a-f]{64}"));
        assertEquals(1440, f.sources.size());
        assertEquals(11292, f.days.get(2).steps());
    }

    @Test public void watchMinutesUseTheNativeNegativeOneMode() {
        Fixture f = new Fixture();
        f.minuteMode = -1;
        SavedMinute minute = f.saved.get(0);
        f.saved.set(0, new SavedMinute(minute.account(), minute.device(), minute.startMs(), minute.endMs(),
                minute.steps(), minute.calories(), minute.distance(), -1, minute.display(), minute.timezone()));
        Daily day = f.days.get(1);
        f.days.set(1, new Daily(day.account(), day.device(), day.date(), -1, day.steps(), day.calories(), day.distance()));
        assertNotNull(f.assess());
        f.minuteMode = 2;
        assertNull(f.assess());
    }

    @Test public void zeroSourceMinutesMayHaveExactZeroSavedRows() {
        Fixture f = new Fixture();
        f.saved.add(new SavedMinute(ACCOUNT, NATIVE, START, START + 60000, 0, 0, 0, 1, 1, "Asia/Shanghai"));
        assertNotNull(f.assess());
        f.saved.set(1, new SavedMinute(ACCOUNT, NATIVE, START, START + 60000, 1, 0, 0, 1, 1, "+08:00"));
        assertNull(f.assess());
    }

    @Test public void namedMinuteDailyIsOptionalAndEmptyAggregateDeviceIsAccepted() {
        Fixture f = new Fixture();
        f.days.remove(1);
        f.days.set(1, new Daily(ACCOUNT, "", DATE, -2, 11292, 60000, 117));
        assertNotNull(f.assess());
    }

    @Test public void eitherHighAggregateProducesReviewIncludingHighPhoneSource() {
        Fixture f = new Fixture();
        f.days.set(2, new Daily(ACCOUNT, null, DATE, -2, 221, 60000, 117));
        assertNotNull(f.assess());
        assertEquals(11292, f.assess().phoneSteps());
        f.days.set(3, new Daily(ACCOUNT, null, DATE, -4, 221, 60000, 61));
        assertNull(f.assess());
        f.days.set(2, new Daily(ACCOUNT, null, DATE, -2, 222, 60000, 117));
        assertNotNull(f.assess());
    }

    @Test public void todayAndFutureDatesWaitForWholeDayToEnd() {
        Fixture f = new Fixture();
        f.now = END - 1;
        assertNull(f.assess());
        f.now = START;
        assertNull(f.assess());
        f.now = START - 1;
        assertNull(f.assess());
        f.now = END;
        assertNotNull(f.assess());
    }

    @Test public void daylightSavingDaysUseConservativeTwentyFourHourBoundary() {
        for (int date : new int[] {20260308, 20261101}) {
            Fixture f = new Fixture();
            f.date = date;
            f.zone = "America/Los_Angeles";
            LocalDate day = LocalDate.of(date / 10000, date / 100 % 100, date % 100);
            f.start = day.atStartOfDay(ZoneId.of(f.zone)).toInstant().toEpochMilli();
            f.now = f.start + 2 * 86_400_000L;
            assertNull(f.assess());
        }
    }

    @Test public void dateZoneAndMidnightMustMatchExactly() {
        Fixture f = new Fixture(); f.date = 20260230; assertNull(f.assess());
        f = new Fixture(); f.zone = "not-a-zone"; assertNull(f.assess());
        f = new Fixture(); f.zone = null; assertNull(f.assess());
        f = new Fixture(); f.start++; assertNull(f.assess());
        f = new Fixture(); f.start += 3600000; assertNull(f.assess());
        f = new Fixture(); f.start = -1; assertNull(f.assess());
        f = new Fixture(); f.date = 20261031; assertNull(f.assess());
    }

    @Test public void crossMidnightSavedSourcesKeepTheDayPendingReview() {
        for (String device : List.of(NATIVE, "phone")) {
            Fixture f = new Fixture();
            f.saved.add(new SavedMinute(ACCOUNT, device, START - 30_000, START + 30_000,
                    20, 1000, 10, 1, 1, "+08:00"));
            assertNull(f.assess());
            f = new Fixture();
            f.saved.add(new SavedMinute(ACCOUNT, device, END - 30_000, END + 30_000,
                    20, 1000, 10, 1, 1, "+08:00"));
            assertNull(f.assess());
        }
    }

    @Test public void sourceIncludesEveryMinuteExactlyOnce() {
        Fixture f = new Fixture(); f.sources.remove(100); assertNull(f.assess());
        f = new Fixture(); f.sources.set(100, f.sources.get(101)); assertNull(f.assess());
        f = new Fixture(); f.sources.add(f.sources.get(100)); assertNull(f.assess());
        f = new Fixture(); f.sources.set(100, null); assertNull(f.assess());
    }

    @Test public void sourceIdentityTimezoneAndMinuteShapeAreRequired() {
        List<SourceMinute> invalid = List.of(
                new SourceMinute("other", AT, AT + 60000, 221, 60, 117, "+08:00"),
                new SourceMinute(QUEUED, AT, AT + 60000, 221, 60, 117, "+09:00"),
                new SourceMinute(QUEUED, AT, AT + 60000, 221, 60, 117, null),
                new SourceMinute(QUEUED, AT + 1, AT + 60001, 221, 60, 117, "+08:00"),
                new SourceMinute(QUEUED, AT, AT + 59999, 221, 60, 117, "+08:00"),
                new SourceMinute(QUEUED, END, END + 60000, 221, 60, 117, "+08:00"),
                new SourceMinute(QUEUED, START - 60000, START, 221, 60, 117, "+08:00"),
                new SourceMinute(QUEUED, Long.MAX_VALUE, Long.MIN_VALUE, 221, 60, 117, "+08:00"));
        for (SourceMinute source : invalid) {
            Fixture f = new Fixture(); f.sources.set(20, source); assertNull(f.assess());
        }
    }

    @Test public void sourceValuesFitHostMinuteLimitsAndUseCheckedTotals() {
        for (long[] values : new long[][] {{-1,60,117},{351,60,117},{221,-1,117},{221,66,117},
                {221,60,-1},{221,60,1051},{Long.MAX_VALUE,60,117},{221,Long.MAX_VALUE,117},
                {221,60,Long.MAX_VALUE}}) {
            Fixture f = new Fixture();
            f.sources.set(20, new SourceMinute(QUEUED, AT, AT + 60000, values[0], values[1], values[2], "+08:00"));
            assertNull(f.assess());
        }
        Fixture f = new Fixture(); f.steps = -1; assertNull(f.assess());
        f = new Fixture(); f.steps = Long.MAX_VALUE; assertNull(f.assess());
        f = new Fixture(); f.steps = (long) Integer.MAX_VALUE + 1; assertNull(f.assess());
    }

    @Test public void exactHostMinuteUpperBoundsAreAccepted() {
        Fixture f = new Fixture();
        f.steps = 350;
        f.sources.set(20,new SourceMinute(QUEUED,AT,AT+60000,350,65,1050,"+08:00"));
        f.saved.set(0,new SavedMinute(ACCOUNT,NATIVE,AT,AT+60000,350,65000,1050,1,1,"+08:00"));
        f.days.set(0,new Daily(ACCOUNT,NATIVE,DATE,-3,350,65000,1050));
        f.days.set(1,new Daily(ACCOUNT,NATIVE,DATE,1,350,65000,1050));
        assertNotNull(f.assess());
    }

    @Test public void caloriesOnlyAndDistanceOnlyMinutesRequireSavedEvidence() {
        for (long[] extra : new long[][] {{1,0},{0,1}}) {
            Fixture f = new Fixture();
            f.sources.set(21,new SourceMinute(QUEUED,AT+60000,AT+120000,0,extra[0],extra[1],"+08:00"));
            for (int i=0;i<2;i++) {
                Daily day=f.days.get(i);
                f.days.set(i,new Daily(ACCOUNT,NATIVE,DATE,day.mode(),221,60000+extra[0]*1000,117+extra[1]));
            }
            assertNull(f.assess());
            f.saved.add(new SavedMinute(ACCOUNT,NATIVE,AT+60000,AT+120000,0,extra[0]*1000,extra[1],1,1,"+08:00"));
            assertNotNull(f.assess());
        }
    }

    @Test public void sourceDailyTotalAgreesWithMinutesAndHasPositiveSteps() {
        Fixture f = new Fixture(); f.steps = 11292; assertNull(f.assess());
        f = new Fixture(); f.steps = 220; assertNull(f.assess());
        f = new Fixture();
        f.steps = 0;
        f.sources.set(20, new SourceMinute(QUEUED, AT, AT + 60000, 0, 0, 0, "+08:00"));
        assertNull(f.assess());
    }

    @Test public void savedNonzeroMinutesMatchSourceExactly() {
        Fixture f = new Fixture(); f.saved.clear(); assertNull(f.assess());
        for (long[] values : new long[][] {{220,60000,117},{221,60,117},{221,60001,117},
                {221,60000,116},{-1,60000,117},{221,-1,117},{221,60000,-1},
                {Long.MAX_VALUE,60000,117},{221,Long.MAX_VALUE,117},{221,60000,Long.MAX_VALUE}}) {
            f = new Fixture();
            f.saved.set(0, new SavedMinute(ACCOUNT,NATIVE,AT,AT+60000,values[0],values[1],values[2],1,1,"+08:00"));
            assertNull(f.assess());
        }
    }

    @Test public void savedAccountDeviceModeDisplayTimezoneAndMinuteShapeAreRequired() {
        List<SavedMinute> invalid = List.of(
                new SavedMinute("other",NATIVE,AT,AT+60000,221,60000,117,1,1,"+08:00"),
                new SavedMinute(ACCOUNT,"other",AT,AT+60000,221,60000,117,1,1,"+08:00"),
                new SavedMinute(ACCOUNT,NATIVE,AT,AT+60000,221,60000,117,2,1,"+08:00"),
                new SavedMinute(ACCOUNT,NATIVE,AT,AT+60000,221,60000,117,1,0,"+08:00"),
                new SavedMinute(ACCOUNT,NATIVE,AT,AT+60000,221,60000,117,1,1,"+09:00"),
                new SavedMinute(ACCOUNT,NATIVE,AT+1,AT+60001,221,60000,117,1,1,"+08:00"),
                new SavedMinute(ACCOUNT,NATIVE,AT,AT+59999,221,60000,117,1,1,"+08:00"),
                new SavedMinute(ACCOUNT,NATIVE,END,END+60000,221,60000,117,1,1,"+08:00"));
        for (SavedMinute row : invalid) {
            Fixture f = new Fixture(); f.saved.set(0,row); assertNull(f.assess());
        }
        Fixture f = new Fixture(); f.saved.add(f.saved.get(0)); assertNull(f.assess());
        f = new Fixture(); f.saved.add(null); assertNull(f.assess());
    }

    @Test public void extraPhoneOrWearableSavedRowsExcludeMixedSources() {
        for (String other : List.of("phone", "another-band")) {
            Fixture f = new Fixture();
            f.saved.add(new SavedMinute(ACCOUNT,other,START,START+60000,0,0,0,1,1,"+08:00"));
            assertNull(f.assess());
        }
    }

    @Test public void dailySetContainsUniqueDeviceCombinedAndPhoneRows() {
        for (int missing : new int[] {0,2,3}) {
            Fixture f = new Fixture(); f.days.remove(missing); assertNull(f.assess());
        }
        Fixture f = new Fixture(); f.days.set(1,f.days.get(0)); assertNull(f.assess());
        f = new Fixture(); f.days.set(3,f.days.get(2)); assertNull(f.assess());
        f = new Fixture(); f.days.add(f.days.get(0)); assertNull(f.assess());
        f = new Fixture(); f.days.set(1,null); assertNull(f.assess());
    }

    @Test public void dailyAccountDateAndAllNamedSourcesAreExact() {
        List<Daily> invalid = List.of(
                new Daily("other",NATIVE,DATE,-3,221,60000,117),
                new Daily(ACCOUNT,NATIVE,20261031,-3,221,60000,117),
                new Daily(ACCOUNT,"phone",DATE,-3,221,60000,117),
                new Daily(ACCOUNT,"another-band",DATE,-3,221,60000,117),
                new Daily(ACCOUNT,NATIVE,DATE,2,221,60000,117),
                new Daily(ACCOUNT,null,DATE,-3,221,60000,117),
                new Daily(ACCOUNT,NATIVE,DATE,-3,222,60000,117),
                new Daily(ACCOUNT,NATIVE,DATE,-3,221,60001,117),
                new Daily(ACCOUNT,NATIVE,DATE,-3,221,60000,118));
        for (Daily row : invalid) {
            Fixture f = new Fixture(); f.days.set(0,row); assertNull(f.assess());
        }
        Fixture f = new Fixture();
        f.days.set(1,new Daily(ACCOUNT,NATIVE,DATE,1,11292,60000,117));
        assertNull(f.assess());
        f = new Fixture(); f.days.set(2,new Daily(ACCOUNT," ",DATE,-2,11292,60000,117));
        assertNull(f.assess());
    }

    @Test public void dailyMetricRangesIncludeAggregateFields() {
        for (long[] values : new long[][] {{-1,60000,117},{11292,-1,117},{11292,60000,-1},
                {(long)Integer.MAX_VALUE+1,60000,117},{11292,Long.MAX_VALUE,117},{11292,60000,Long.MAX_VALUE}}) {
            Fixture f = new Fixture();
            f.days.set(2,new Daily(ACCOUNT,null,DATE,-2,values[0],values[1],values[2]));
            assertNull(f.assess());
        }
        Fixture f = new Fixture();
        f.days.set(2,new Daily(ACCOUNT,null,DATE,-2,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));
        assertNotNull(f.assess());
    }

    @Test public void emptyIdentityAndUnsupportedMinuteModeExcludeReports() {
        Fixture f = new Fixture(); f.account = " "; assertNull(f.assess());
        f = new Fixture(); f.queued = ""; assertNull(f.assess());
        f = new Fixture(); f.nativeDevice = null; assertNull(f.assess());
        f = new Fixture(); f.minuteMode = -3; assertNull(f.assess());
        f = new Fixture(); f.minuteMode = 0; assertNull(f.assess());
    }

    @Test public void fingerprintIsStableAcrossInputOrderAndReviewTime() {
        Fixture f = splitFixture();
        String before = f.assess().fingerprint();
        Collections.reverse(f.sources);
        Collections.reverse(f.saved);
        Collections.reverse(f.days);
        f.now += 999999;
        assertEquals(before,f.assess().fingerprint());
    }

    @Test public void fingerprintBindsAccountsAndBothDeviceIdentities() {
        Fixture f = new Fixture();
        StepAggregateReview original = f.assess();
        f.account = "account-2";
        f.saved.replaceAll(r -> new SavedMinute(f.account,r.device(),r.startMs(),r.endMs(),r.steps(),r.calories(),r.distance(),r.mode(),r.display(),r.timezone()));
        f.days.replaceAll(r -> new Daily(f.account,r.device(),r.date(),r.mode(),r.steps(),r.calories(),r.distance()));
        StepAggregateReview changed = f.assess();
        assertNotEquals(original.accountHash(),changed.accountHash());
        assertNotEquals(original.fingerprint(),changed.fingerprint());
        f.queued = "different-queued";
        f.sources.replaceAll(r -> new SourceMinute(f.queued,r.startMs(),r.endMs(),r.steps(),r.calories(),r.distance(),r.timezone()));
        assertNotEquals(changed.fingerprint(),f.assess().fingerprint());
        changed = f.assess();
        f.nativeDevice = "different-native";
        f.saved.replaceAll(r -> new SavedMinute(f.account,f.nativeDevice,r.startMs(),r.endMs(),r.steps(),r.calories(),r.distance(),r.mode(),r.display(),r.timezone()));
        f.days.replaceAll(r -> new Daily(f.account,r.device()==null?null:f.nativeDevice,r.date(),r.mode(),r.steps(),r.calories(),r.distance()));
        assertNotEquals(changed.fingerprint(),f.assess().fingerprint());
    }

    @Test public void fingerprintBindsMinuteVectorSavedEvidenceAndAggregateValues() {
        Fixture f = new Fixture();
        String original = f.assess().fingerprint();
        f.days.set(2,new Daily(ACCOUNT,null,DATE,-2,11293,60000,117));
        assertNotEquals(original,f.assess().fingerprint());
        f = new Fixture();
        f.saved.add(new SavedMinute(ACCOUNT,NATIVE,START,START+60000,0,0,0,1,1,"+08:00"));
        assertNotEquals(original,f.assess().fingerprint());
        assertNotEquals(original,splitFixture().assess().fingerprint());
        f = new Fixture(); f.zone = "+08:00";
        assertNotEquals(original,f.assess().fingerprint());
    }

    @Test public void fingerprintBindsLocalDateAndDayStart() {
        Fixture f = new Fixture();
        String original = f.assess().fingerprint();
        f.date = 20261102; f.start += 86400000; f.now += 86400000;
        f.sources.replaceAll(r -> new SourceMinute(r.device(),r.startMs()+86400000,r.endMs()+86400000,
                r.steps(),r.calories(),r.distance(),r.timezone()));
        f.saved.replaceAll(r -> new SavedMinute(r.account(),r.device(),r.startMs()+86400000,r.endMs()+86400000,
                r.steps(),r.calories(),r.distance(),r.mode(),r.display(),r.timezone()));
        f.days.replaceAll(r -> new Daily(r.account(),r.device(),f.date,r.mode(),r.steps(),r.calories(),r.distance()));
        assertNotNull(f.assess());
        assertNotEquals(original,f.assess().fingerprint());
    }

    private static Fixture splitFixture() {
        Fixture f = new Fixture();
        f.sources.set(20,new SourceMinute(QUEUED,AT,AT+60000,200,50,100,"+08:00"));
        f.sources.set(21,new SourceMinute(QUEUED,AT+60000,AT+120000,21,10,17,"Asia/Shanghai"));
        f.saved.set(0,new SavedMinute(ACCOUNT,NATIVE,AT,AT+60000,200,50000,100,1,1,"+0800"));
        f.saved.add(new SavedMinute(ACCOUNT,NATIVE,AT+60000,AT+120000,21,10000,17,1,1,"+08:00"));
        return f;
    }

    @Test public void jsonRoundTripPreservesExactReview() throws Exception {
        StepAggregateReview review = new Fixture().assess();
        assertEquals(review,StepAggregateReview.fromJson(new JSONObject(review.toJson().toString())));
        JSONObject asLongs = review.toJson().put("date",(long)DATE).put("combinedSteps",11292L);
        assertEquals(review,StepAggregateReview.fromJson(asLongs));
    }

    @Test public void jsonRequiresExactFieldsAndIntegralValues() throws Exception {
        JSONObject json = new Fixture().assess().toJson().put("extra",true);
        assertThrows(IllegalArgumentException.class,() -> StepAggregateReview.fromJson(json));
        JSONObject missing = new Fixture().assess().toJson(); missing.remove("phoneSteps");
        assertThrows(IllegalArgumentException.class,() -> StepAggregateReview.fromJson(missing));
        for (String key : List.of("date","expectedSteps","combinedSteps","phoneSteps")) {
            for (Object invalid : List.of(-1,Long.MAX_VALUE,1.5,"221",true,JSONObject.NULL)) {
                JSONObject bad = new Fixture().assess().toJson().put(key,invalid);
                assertThrows(IllegalArgumentException.class,() -> StepAggregateReview.fromJson(bad));
            }
        }
        assertThrows(IllegalArgumentException.class,() -> StepAggregateReview.fromJson(null));
    }

    @Test public void jsonValidatesHashesIdentitiesDateAndReviewRange() throws Exception {
        for (String key : List.of("fingerprint","accountHash","queuedDevice","nativeDevice")) {
            for (Object invalid : List.of("", " ", 1, JSONObject.NULL)) {
                JSONObject bad = new Fixture().assess().toJson().put(key,invalid);
                assertThrows(IllegalArgumentException.class,() -> StepAggregateReview.fromJson(bad));
            }
        }
        for (String key : List.of("fingerprint","accountHash")) {
            JSONObject bad = new Fixture().assess().toJson().put(key,"F".repeat(64));
            assertThrows(IllegalArgumentException.class,() -> StepAggregateReview.fromJson(bad));
        }
        JSONObject badDate = new Fixture().assess().toJson().put("date",20260230);
        assertThrows(RuntimeException.class,() -> StepAggregateReview.fromJson(badDate));
        JSONObject zero = new Fixture().assess().toJson().put("expectedSteps",0);
        assertThrows(IllegalArgumentException.class,() -> StepAggregateReview.fromJson(zero));
        JSONObject equal = new Fixture().assess().toJson().put("combinedSteps",221).put("phoneSteps",221);
        assertThrows(IllegalArgumentException.class,() -> StepAggregateReview.fromJson(equal));
    }
}
