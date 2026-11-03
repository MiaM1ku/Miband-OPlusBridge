// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class StepAggregateAuditStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final String ACCOUNT = "account-a";
    private static final String QUEUED = "miband11_device-a";
    private static final String NATIVE = "02:00:00:00:00:11";
    private static final String FP = "a".repeat(64);
    private static final String OTHER_FP = "b".repeat(64);

    private StepAggregateAuditStore.Request request() { return request(ACCOUNT, QUEUED, LocalDate.of(2026, 11, 1), 221); }
    private static StepAggregateAuditStore.Request request(String account, String queued, LocalDate day, long steps) {
        int date = day.getYear() * 10_000 + day.getMonthValue() * 100 + day.getDayOfMonth();
        return new StepAggregateAuditStore.Request(account, queued, NATIVE, date,
                day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(), "Asia/Shanghai", steps, 1);
    }
    private StepAggregateReview review(StepAggregateAuditStore.Request request, String fingerprint) {
        return new StepAggregateReview(fingerprint, request.accountHash(), request.queuedDevice(),
                request.nativeDevice(), request.date(), (int) request.daySteps(), 11292, 11292);
    }
    private File file() { return new File(folder.getRoot(), "step-aggregate-audits.json"); }
    private static String text(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }
    private static void save(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private StepAggregateAuditStore store() { return new StepAggregateAuditStore(file()); }
    private StepAggregateAuditStore.Entry entry(StepAggregateAuditStore store, StepAggregateAuditStore.Request request) {
        return store.entries(request.accountHash(), request.queuedDevice()).get(0);
    }
    private static void failure(String code, Runnable action) {
        try { action.run(); fail("Expected " + code); }
        catch (IllegalArgumentException | IllegalStateException failure) { assertEquals(code, failure.getMessage()); }
    }

    @Test public void requestRoundTripsAllEightFieldsAndKeepsAccountOutOfDiagnostics() {
        var request = request();
        assertEquals(8, request.toJson().length());
        assertEquals(request, StepAggregateAuditStore.Request.fromJson(request.toJson()));
        assertEquals(64, request.accountHash().length());
        assertTrue(request.key().startsWith(request.accountHash() + ":" + QUEUED + ":"));
        assertFalse(request.toString().contains(ACCOUNT));
    }

    @Test public void requestAcceptsBothSupportedMinuteModes() {
        var r = request();
        var negative = new StepAggregateAuditStore.Request(r.account(), r.queuedDevice(), r.nativeDevice(),
                r.date(), r.startMs(), r.timezone(), r.daySteps(), -1);
        assertEquals(-1, StepAggregateAuditStore.Request.fromJson(negative.toJson()).minuteMode());
    }

    @Test public void requestRejectsCoercionExtraFieldsMissingFieldsAndIntegerOverflow() throws Exception {
        for (String field : List.of("date", "startMs", "daySteps", "minuteMode")) {
            JSONObject json = request().toJson().put(field, "1");
            failure("STEP_AUDIT_REQUEST_INVALID", () -> StepAggregateAuditStore.Request.fromJson(json));
        }
        JSONObject extra = request().toJson().put("approved", true);
        failure("STEP_AUDIT_REQUEST_INVALID", () -> StepAggregateAuditStore.Request.fromJson(extra));
        JSONObject missing = request().toJson();
        missing.remove("account");
        failure("STEP_AUDIT_REQUEST_INVALID", () -> StepAggregateAuditStore.Request.fromJson(missing));
        JSONObject decimal = request().toJson().put("daySteps", 221.0);
        failure("STEP_AUDIT_REQUEST_INVALID", () -> StepAggregateAuditStore.Request.fromJson(decimal));
        JSONObject overflow = request().toJson().put("date", 0x1_0000_0000L + 20261101);
        failure("STEP_AUDIT_REQUEST_INVALID", () -> StepAggregateAuditStore.Request.fromJson(overflow));
    }

    @Test public void requestRejectsInvalidIdentitiesDatesTimezonesAndDayBoundaries() {
        var r = request();
        for (String account : List.of("", " ", "account\n", " leading", "x".repeat(513))) {
            failure("STEP_AUDIT_REQUEST_INVALID", () -> new StepAggregateAuditStore.Request(account,
                    r.queuedDevice(), r.nativeDevice(), r.date(), r.startMs(), r.timezone(), 221, 1));
        }
        failure("STEP_AUDIT_REQUEST_INVALID", () -> new StepAggregateAuditStore.Request(r.account(),
                r.queuedDevice(), r.nativeDevice(), 20260230, r.startMs(), r.timezone(), 221, 1));
        failure("STEP_AUDIT_REQUEST_INVALID", () -> new StepAggregateAuditStore.Request(r.account(),
                r.queuedDevice(), r.nativeDevice(), r.date(), r.startMs() + 1, r.timezone(), 221, 1));
        failure("STEP_AUDIT_REQUEST_INVALID", () -> new StepAggregateAuditStore.Request(r.account(),
                r.queuedDevice(), r.nativeDevice(), r.date(), r.startMs(), "Invalid/Zone", 221, 1));
        for (long steps : new long[] {0, -1, (long) Integer.MAX_VALUE + 1}) {
            failure("STEP_AUDIT_REQUEST_INVALID", () -> new StepAggregateAuditStore.Request(r.account(),
                    r.queuedDevice(), r.nativeDevice(), r.date(), r.startMs(), r.timezone(), steps, 1));
        }
        failure("STEP_AUDIT_REQUEST_INVALID", () -> new StepAggregateAuditStore.Request(r.account(),
                r.queuedDevice(), r.nativeDevice(), r.date(), r.startMs(), r.timezone(), 221, 2));
    }

    @Test public void requestRejectsShortAndLongDstDays() {
        for (LocalDate day : List.of(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1))) {
            int date = day.getYear() * 10_000 + day.getMonthValue() * 100 + day.getDayOfMonth();
            long start = day.atStartOfDay(ZoneId.of("America/New_York")).toInstant().toEpochMilli();
            failure("STEP_AUDIT_REQUEST_INVALID", () -> new StepAggregateAuditStore.Request(ACCOUNT,
                    QUEUED, NATIVE, date, start, "America/New_York", 221, 1));
        }
    }

    @Test public void scheduledCandidatesAreVisibleBeforeReviewAndScopedToAccountAndDevice() {
        var store = store();
        var request = request();
        assertTrue(store.schedule(List.of(request,
                request("other-account", QUEUED, LocalDate.of(2026, 11, 1), 300),
                request(ACCOUNT, "other-device", LocalDate.of(2026, 11, 1), 400))));
        assertEquals(1, store.entries(request.accountHash(), QUEUED).size());
        assertNull(entry(store, request).review());
        assertFalse(entry(store, request).approved());
        assertTrue(store.pending(request.accountHash(), QUEUED).isEmpty());
        assertFalse(store.schedule(List.of(request)));
    }

    @Test public void identicalScheduleAndRepeatedReportsPreserveApprovalAcrossInstances() {
        var first = store();
        var request = request();
        var review = review(request, FP);
        first.schedule(List.of(request));
        assertEquals(new StepAggregateAuditStore.ReportResult(false, true), first.report(request, review));
        first.approve(request.accountHash(), QUEUED, FP);
        var second = store();
        assertFalse(second.schedule(List.of(request)));
        assertTrue(entry(second, request).approved());
        assertEquals(List.of(review), second.pending(request.accountHash(), QUEUED));
        assertEquals(new StepAggregateAuditStore.ReportResult(true, false), second.report(request, review));
    }

    @Test public void changedRequestClearsReviewAndApprovalAndRejectsStaleReportAndCompletion() {
        var store = store();
        var old = request();
        var oldReview = review(old, FP);
        store.schedule(List.of(old));
        store.report(old, oldReview);
        store.approve(old.accountHash(), QUEUED, FP);
        var changed = request(ACCOUNT, QUEUED, LocalDate.of(2026, 11, 1), 222);
        assertTrue(store.schedule(List.of(changed)));
        assertEquals(new StepAggregateAuditStore.ReportResult(false, false), store.report(old, oldReview));
        assertFalse(store.complete(old, oldReview));
        assertEquals(changed, entry(store, changed).request());
        assertNull(entry(store, changed).review());
        assertFalse(entry(store, changed).approved());
    }

    @Test public void changedFingerprintAndNullReportClearApproval() {
        var store = store();
        var request = request();
        store.schedule(List.of(request));
        store.report(request, review(request, FP));
        store.approve(request.accountHash(), QUEUED, FP);
        assertEquals(new StepAggregateAuditStore.ReportResult(false, true), store.report(request, review(request, OTHER_FP)));
        assertFalse(entry(store, request).approved());
        failure("STEP_AUDIT_CHANGED", () -> store.approve(request.accountHash(), QUEUED, FP));
        store.approve(request.accountHash(), QUEUED, OTHER_FP);
        assertEquals(new StepAggregateAuditStore.ReportResult(false, true), store.report(request, null));
        assertNull(entry(store, request).review());
        assertFalse(entry(store, request).approved());
        assertEquals(new StepAggregateAuditStore.ReportResult(false, false), store.report(request, null));
    }

    @Test public void reviewIdentityFieldsMustMatchTheScheduledRequest() {
        var store = store();
        var request = request();
        store.schedule(List.of(request));
        List<StepAggregateReview> mismatches = List.of(
                new StepAggregateReview(FP, "b".repeat(64), QUEUED, NATIVE, request.date(), 221, 1000, 1000),
                new StepAggregateReview(FP, request.accountHash(), "other-device", NATIVE, request.date(), 221, 1000, 1000),
                new StepAggregateReview(FP, request.accountHash(), QUEUED, "other-native", request.date(), 221, 1000, 1000),
                new StepAggregateReview(FP, request.accountHash(), QUEUED, NATIVE, 20261102, 221, 1000, 1000),
                new StepAggregateReview(FP, request.accountHash(), QUEUED, NATIVE, request.date(), 222, 1000, 1000));
        for (var review : mismatches) {
            failure("STEP_AUDIT_REVIEW_MISMATCH", () -> store.report(request, review));
            assertNull(entry(store, request).review());
        }
    }

    @Test public void approvalIsScopedAndCompletionRequiresCurrentApprovedEvidence() {
        var store = store();
        var request = request();
        var review = review(request, FP);
        store.schedule(List.of(request));
        store.report(request, review);
        assertFalse(store.complete(request, review));
        failure("STEP_AUDIT_CHANGED", () -> store.approve("b".repeat(64), QUEUED, FP));
        failure("STEP_AUDIT_CHANGED", () -> store.approve(request.accountHash(), "other-device", FP));
        store.approve(request.accountHash(), QUEUED, FP);
        assertFalse(store.complete(request, review(request, OTHER_FP)));
        assertTrue(store.complete(request, review));
        assertEquals(request, entry(store, request).request());
        assertNull(entry(store, request).review());
        assertFalse(entry(store, request).approved());
        assertFalse(store.complete(request, review));
        assertEquals(new StepAggregateAuditStore.ReportResult(false, true), store.report(request, review));
    }

    @Test public void keepsTheNewestNinetyThreeCandidatesInDescendingDateOrder() throws Exception {
        var store = store();
        List<StepAggregateAuditStore.Request> requests = new ArrayList<>();
        LocalDate newest = LocalDate.of(2026, 11, 1);
        for (int i = 104; i >= 0; i--) requests.add(request(ACCOUNT, QUEUED, newest.minusDays(i), 221));
        assertTrue(store.schedule(requests));
        var entries = store.entries(request().accountHash(), QUEUED);
        assertEquals(93, entries.size());
        assertEquals(20261101, entries.get(0).request().date());
        assertEquals(request(ACCOUNT, QUEUED, newest.minusDays(92), 221), entries.get(92).request());
        assertEquals(93, new JSONObject(text(file())).length());
        assertFalse(store.schedule(List.of(request(ACCOUNT, QUEUED, newest.minusDays(200), 221))));
    }

    @Test public void allStoreOperationsReportCorruptionAndPreserveTheOriginalFile() throws Exception {
        var store = store();
        var request = request();
        var review = review(request, FP);
        for (String broken : List.of("", "{", "{} trailing", "[]", "{\"foreign\":true}")) {
            save(file(), broken);
            failure("STEP_AUDIT_CORRUPT", () -> store.schedule(List.of(request)));
            failure("STEP_AUDIT_CORRUPT", () -> store.entries(request.accountHash(), QUEUED));
            failure("STEP_AUDIT_CORRUPT", () -> store.pending(request.accountHash(), QUEUED));
            failure("STEP_AUDIT_CORRUPT", () -> store.report(request, review));
            failure("STEP_AUDIT_CORRUPT", () -> store.approve(request.accountHash(), QUEUED, FP));
            failure("STEP_AUDIT_CORRUPT", () -> store.complete(request, review));
            assertEquals(broken, text(file()));
        }
    }

    @Test public void persistedApprovalFingerprintMustMatchPersistedReview() throws Exception {
        var store = store();
        var request = request();
        store.schedule(List.of(request));
        store.report(request, review(request, FP));
        store.approve(request.accountHash(), QUEUED, FP);
        JSONObject data = new JSONObject(text(file()));
        data.getJSONObject(request.key()).put("approvedFingerprint", OTHER_FP);
        save(file(), data.toString());
        failure("STEP_AUDIT_CORRUPT", () -> store.entries(request.accountHash(), QUEUED));
    }

    @Test public void failedAtomicWritePreservesPriorApproval() throws Exception {
        var store = store();
        var request = request();
        store.schedule(List.of(request));
        store.report(request, review(request, FP));
        store.approve(request.accountHash(), QUEUED, FP);
        String before = text(file());
        File temporary = new File(file().getPath() + ".tmp");
        assertTrue(temporary.mkdir());
        save(new File(temporary, "occupied"), "occupied");
        failure("STEP_AUDIT_STORAGE_FAILED", () -> store.report(request, review(request, OTHER_FP)));
        assertEquals(before, text(file()));
        assertTrue(entry(store, request).approved());
    }

    @Test public void concurrentInstancesSerializeReadModifyCommit() throws Exception {
        var first = store();
        var second = store();
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> {
                ready.countDown(); start.await();
                for (int i = 0; i < 20; i++) first.schedule(List.of(request(ACCOUNT, QUEUED,
                        LocalDate.of(2026, 11, 1).minusDays(i), 221)));
                return null;
            });
            var b = pool.submit(() -> {
                ready.countDown(); start.await();
                for (int i = 0; i < 20; i++) second.schedule(List.of(request("other-account", QUEUED,
                        LocalDate.of(2026, 11, 1).minusDays(i), 222)));
                return null;
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
            assertEquals(20, first.entries(request().accountHash(), QUEUED).size());
            assertEquals(20, first.entries(request("other-account", QUEUED,
                    LocalDate.of(2026, 11, 1), 222).accountHash(), QUEUED).size());
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
