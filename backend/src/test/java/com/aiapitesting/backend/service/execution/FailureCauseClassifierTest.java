package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.ErrorKind;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.service.execution.FailureCauseClassifier.ExecutionContext;
import com.aiapitesting.backend.service.execution.FailureCauseClassifier.HistoryPoint;
import com.aiapitesting.backend.service.execution.FailureCauseClassifier.Input;
import com.aiapitesting.backend.service.execution.FailureCauseClassifier.Result;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module 16 - bộ phân loại nguyên nhân fail: tất định, không đụng DB. Test từng luật, thứ tự ưu tiên và các tình
 * huống dễ nhầm (case Security 401 cố ý, chuỗi xen kẽ không phải "bậc thang", mã khác nhau không phải "ổn định").
 */
class FailureCauseClassifierTest {

    private final FailureCauseClassifier classifier = new FailureCauseClassifier();

    private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");

    private static HistoryPoint pass(int minute) {
        return new HistoryPoint(TestResultStatus.PASSED, 200, T0.plusSeconds(60L * minute), "http://target");
    }

    private static HistoryPoint fail(int minute, int status) {
        return new HistoryPoint(TestResultStatus.FAILED, status, T0.plusSeconds(60L * minute), "http://target");
    }

    private static List<HistoryPoint> history(HistoryPoint... points) {
        return new ArrayList<>(List.of(points));
    }

    /** FAILED với status thật + lịch sử (lần hiện tại là phần tử cuối của lịch sử, đúng như analyzer truyền vào). */
    private Input failed(Integer expected, Integer actual, List<HistoryPoint> history) {
        return new Input(TestResultStatus.FAILED, expected, actual, null, 120L, null, TestCaseAuthOverride.DEFAULT,
                history, "http://target", null, ExecutionContext.none());
    }

    private Input errored(ErrorKind kind, Long durationMs, String message, ExecutionContext ctx, List<HistoryPoint> history) {
        return new Input(TestResultStatus.ERROR, 200, null, kind, durationMs, message, TestCaseAuthOverride.DEFAULT,
                history, "http://target", null, ctx);
    }

    // ---- Không phải FAILED/ERROR ----

    @Test
    void passedBlockedSkipped_haveNoFailureCause() {
        for (TestResultStatus status : List.of(TestResultStatus.PASSED, TestResultStatus.BLOCKED, TestResultStatus.SKIPPED)) {
            Input in = new Input(status, 200, 200, null, 10L, null, TestCaseAuthOverride.DEFAULT, List.of(), null, null, null);
            assertThat(classifier.classify(in)).as(status.name()).isNull();
        }
    }

    // ---- TEST_SETUP ----

    @Test
    void errorWithoutErrorKind_isTestSetupNotEnvironment() {
        Result result = classifier.classify(errored(null, null, "Thiếu giá trị cho tham số petId", ExecutionContext.none(), List.of()));

        assertThat(result.cause()).isEqualTo(FailureCause.TEST_SETUP);
        assertThat(result.evidence()).anyMatch(e -> e.contains("Thiếu giá trị cho tham số petId"));
        assertThat(result.cause().isNotApiFault()).isTrue();
    }

    // ---- ENVIRONMENT ----

    @Test
    void connectionErrors_areEnvironmentWithKindInEvidence() {
        for (ErrorKind kind : List.of(ErrorKind.CONNECTION, ErrorKind.DNS, ErrorKind.TIMEOUT, ErrorKind.OTHER)) {
            Result result = classifier.classify(errored(kind, 50L, "boom", ExecutionContext.none(), List.of()));

            assertThat(result.cause()).as(kind.name()).isEqualTo(FailureCause.ENVIRONMENT);
            assertThat(result.evidence().get(0)).contains(kind.label());
        }
    }

    @Test
    void timeout_evidenceMentionsDurationAndNearTimeoutOnlyWhenClose() {
        Result slow = classifier.classify(errored(ErrorKind.TIMEOUT, 29_500L, null, ExecutionContext.none(), List.of()));
        Result quick = classifier.classify(errored(ErrorKind.TIMEOUT, 2_000L, null, ExecutionContext.none(), List.of()));

        assertThat(slow.evidence()).anyMatch(e -> e.contains("29500 ms") && e.contains("sát giới hạn chờ"));
        assertThat(quick.evidence()).anyMatch(e -> e.contains("2000 ms")).noneMatch(e -> e.contains("sát giới hạn"));
    }

    @Test
    void manyTestsFailingToConnectInSameRun_isReportedAsTargetDownNotThisTest() {
        Result result = classifier.classify(errored(ErrorKind.CONNECTION, 5L, null, new ExecutionContext(4, 6, 0, 0), List.of()));

        assertThat(result.evidence()).anyMatch(e -> e.contains("4/6 test") && e.contains("không phải lỗi riêng test này"));
    }

    @Test
    void gatewayAndRateLimitStatuses_areEnvironment() {
        for (int status : List.of(502, 503, 504, 429)) {
            Result result = classifier.classify(failed(200, status, history(fail(1, status))));

            assertThat(result.cause()).as(String.valueOf(status)).isEqualTo(FailureCause.ENVIRONMENT);
            assertThat(result.evidence().get(0)).contains(String.valueOf(status));
        }
    }

    @Test
    void ordinaryServerErrorIsNotEnvironment() {
        assertThat(classifier.classify(failed(200, 500, history(fail(1, 500)))).cause()).isEqualTo(FailureCause.UNDETERMINED);
    }

    @Test
    void baseUrlChangedSinceLastRun_isListedAsEvidence() {
        List<HistoryPoint> history = history(
                new HistoryPoint(TestResultStatus.PASSED, 200, T0, "http://old-host"),
                new HistoryPoint(TestResultStatus.ERROR, null, T0.plusSeconds(60), "http://target"));

        Result result = classifier.classify(errored(ErrorKind.CONNECTION, 5L, null, ExecutionContext.none(), history));

        assertThat(result.evidence()).anyMatch(e -> e.contains("http://old-host → http://target"));
    }

    @Test
    void sameBaseUrl_noBaseUrlEvidence() {
        List<HistoryPoint> history = history(pass(0), fail(1, 503));

        assertThat(classifier.classify(failed(200, 503, history)).evidence()).noneMatch(e -> e.contains("Base URL"));
    }

    // ---- AUTH_CONFIG ----

    @Test
    void unexpected401WithDefaultAuth_isAuthConfig() {
        Result result = classifier.classify(failed(200, 401, history(fail(1, 401))));

        assertThat(result.cause()).isEqualTo(FailureCause.AUTH_CONFIG);
        assertThat(result.evidence().get(0)).contains("401").contains("token mặc định");
    }

    @Test
    void unexpected403WithDefaultAuth_isAuthConfigToo() {
        assertThat(classifier.classify(failed(201, 403, history(fail(1, 403)))).cause()).isEqualTo(FailureCause.AUTH_CONFIG);
    }

    /** Case Security cố ý bỏ/sai token: 401 là CHỦ ĐÍCH nên không bao giờ bị gắn "nghi token". */
    @Test
    void securityCaseThatDeliberatelyDropsOrBreaksToken_isNeverAuthConfig() {
        for (TestCaseAuthOverride override : List.of(TestCaseAuthOverride.NONE, TestCaseAuthOverride.INVALID)) {
            Input in = new Input(TestResultStatus.FAILED, 200, 401, null, 10L, null, override, history(fail(1, 401)),
                    "http://target", null, ExecutionContext.none());

            assertThat(classifier.classify(in).cause()).as(override.name()).isNotEqualTo(FailureCause.AUTH_CONFIG);
        }
    }

    @Test
    void expected401ButGot403_isNotAuthConfig() {
        // Kỳ vọng 401 mà nhận 403: test CHỦ ĐÍCH kiểm xác thực, lệch mã không phải do token cấu hình sai.
        assertThat(classifier.classify(failed(401, 403, history(fail(1, 403)))).cause()).isNotEqualTo(FailureCause.AUTH_CONFIG);
    }

    @Test
    void authEvidence_countsSiblingsAndComparesTokenChangeTimeWithLastPass() {
        List<HistoryPoint> history = history(pass(0), fail(10, 401));
        Input in = new Input(TestResultStatus.FAILED, 200, 401, null, 10L, null, TestCaseAuthOverride.DEFAULT, history,
                "http://target", T0.plusSeconds(60L * 5), new ExecutionContext(0, 6, 5, 6));

        Result result = classifier.classify(in);

        assertThat(result.evidence()).anyMatch(e -> e.contains("5/6 test dùng token mặc định"))
                .anyMatch(e -> e.contains("SAU lần Pass gần nhất"));
    }

    @Test
    void authEvidence_tokenOlderThanLastPass_suggestsNaturalExpiry() {
        List<HistoryPoint> history = history(pass(10), fail(20, 401));
        Input in = new Input(TestResultStatus.FAILED, 200, 401, null, 10L, null, TestCaseAuthOverride.DEFAULT, history,
                "http://target", T0.plusSeconds(60L * 5), ExecutionContext.none());

        assertThat(classifier.classify(in).evidence()).anyMatch(e -> e.contains("TRƯỚC lần Pass gần nhất") && e.contains("hết hạn tự nhiên"));
    }

    @Test
    void authBeatsDataStatePattern() {
        // Pass, Fail(401), Fail(401): đúng "bậc thang" nhưng 401 ngoài kỳ vọng thuộc nhóm xác thực - luật xác thực đứng trước.
        List<HistoryPoint> history = history(pass(0), fail(1, 401), fail(2, 401));

        assertThat(classifier.classify(failed(200, 401, history)).cause()).isEqualTo(FailureCause.AUTH_CONFIG);
    }

    // ---- DATA_STATE ----

    @Test
    void passThenConsecutiveFailsWithSameCode_isDataState() {
        List<HistoryPoint> history = history(pass(0), pass(1), fail(2, 404), fail(3, 404));

        Result result = classifier.classify(failed(200, 404, history));

        assertThat(result.cause()).isEqualTo(FailureCause.DATA_STATE);
        assertThat(result.evidence().get(0)).contains("Pass → Pass → Fail(404) → Fail(404)").contains("2 lần");
        assertThat(result.evidence()).anyMatch(e -> e.contains("404") && e.contains("tín hiệu phụ"));
        assertThat(result.cause().isNotApiFault()).as("bằng chứng yếu, không loại khỏi số liệu API").isFalse();
    }

    @Test
    void dataState_doesNotRequireTheWeakStatusCode() {
        List<HistoryPoint> history = history(pass(0), fail(1, 500), fail(2, 500));

        Result result = classifier.classify(failed(200, 500, history));

        assertThat(result.cause()).isEqualTo(FailureCause.DATA_STATE);
        assertThat(result.evidence()).noneMatch(e -> e.contains("tín hiệu phụ"));
    }

    @Test
    void passThenSingleFail_isNotEnoughForDataState_butSuggestsRerun() {
        Result result = classifier.classify(failed(200, 404, history(pass(0), fail(1, 404))));

        assertThat(result.cause()).isEqualTo(FailureCause.UNDETERMINED);
        assertThat(result.evidence()).anyMatch(e -> e.contains("từng Pass") && e.contains("chạy lại"));
    }

    /** Pass/Fail xen kẽ là dấu hiệu không ổn định, KHÔNG phải trạng thái đổi 1 lần - không được gắn DATA_STATE. */
    @Test
    void alternatingResults_areNotDataState() {
        List<HistoryPoint> history = history(pass(0), fail(1, 500), pass(2), fail(3, 500), fail(4, 500));

        assertThat(classifier.classify(failed(200, 500, history)).cause()).isEqualTo(FailureCause.UNDETERMINED);
    }

    @Test
    void consecutiveFailsWithDifferentCodes_areNotDataState() {
        List<HistoryPoint> history = history(pass(0), fail(1, 404), fail(2, 500));

        assertThat(classifier.classify(failed(200, 500, history)).cause()).isEqualTo(FailureCause.UNDETERMINED);
    }

    @Test
    void errorAndBlockedPointsInHistoryAreIgnoredWhenReadingThePattern() {
        List<HistoryPoint> history = history(
                pass(0),
                new HistoryPoint(TestResultStatus.ERROR, null, T0.plusSeconds(60), "http://target"),
                new HistoryPoint(TestResultStatus.BLOCKED, null, T0.plusSeconds(90), "http://target"),
                fail(2, 404), fail(3, 404));

        assertThat(classifier.classify(failed(200, 404, history)).cause()).isEqualTo(FailureCause.DATA_STATE);
    }

    // ---- STABLE_FAILURE ----

    @Test
    void threeIdenticalFailsAndNeverPassed_isStableFailureWithHonestCaveat() {
        List<HistoryPoint> history = history(fail(0, 500), fail(1, 500), fail(2, 500));

        Result result = classifier.classify(failed(200, 500, history));

        assertThat(result.cause()).isEqualTo(FailureCause.STABLE_FAILURE);
        assertThat(result.evidence().get(0)).contains("3 lần chạy đều Fail cùng mã 500");
        assertThat(result.evidence()).anyMatch(e -> e.contains("kiểm thử black-box không phân biệt"));
    }

    @Test
    void twoFailsAreNotEnoughForStableFailure() {
        Result result = classifier.classify(failed(200, 500, history(fail(0, 500), fail(1, 500))));

        assertThat(result.cause()).isEqualTo(FailureCause.UNDETERMINED);
        assertThat(result.evidence()).anyMatch(e -> e.contains("3 lần Fail cùng mã"));
    }

    @Test
    void failsWithDifferentCodes_areNotStable() {
        assertThat(classifier.classify(failed(200, 404, history(fail(0, 500), fail(1, 500), fail(2, 404)))).cause())
                .isEqualTo(FailureCause.UNDETERMINED);
    }

    // ---- UNDETERMINED ----

    @Test
    void firstEverFail_isUndeterminedAndSaysOneRunIsNotEnough() {
        Result result = classifier.classify(failed(200, 500, history(fail(0, 500))));

        assertThat(result.cause()).isEqualTo(FailureCause.UNDETERMINED);
        assertThat(result.evidence()).anyMatch(e -> e.contains("1 lần Fail chưa đủ"));
    }

    @Test
    void noHistoryAtAll_oldResultWithoutFingerprint_isUndetermined() {
        assertThat(classifier.classify(failed(200, 500, List.of())).cause()).isEqualTo(FailureCause.UNDETERMINED);
        assertThat(classifier.classify(failed(200, 500, null)).cause()).isEqualTo(FailureCause.UNDETERMINED);
    }

    // ---- Thuộc tính của nhóm ----

    @Test
    void onlyStrongEvidenceCausesAreExcludedFromApiMetrics() {
        assertThat(List.of(FailureCause.values())).filteredOn(FailureCause::isNotApiFault)
                .containsExactlyInAnyOrder(FailureCause.TEST_SETUP, FailureCause.ENVIRONMENT, FailureCause.AUTH_CONFIG);
    }
}
