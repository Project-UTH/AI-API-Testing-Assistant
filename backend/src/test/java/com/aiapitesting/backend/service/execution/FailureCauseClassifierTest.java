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

    private Input unauthorized(Boolean endpointRequiresAuth) {
        return new Input(TestResultStatus.FAILED, 200, 401, null, 10L, null, TestCaseAuthOverride.DEFAULT, history(fail(1, 401)),
                "http://target", null, ExecutionContext.none(), List.of(), null, endpointRequiresAuth);
    }

    /** Endpoint đăng nhập (spec không khai cần xác thực): vẫn gắn nhãn nhưng nói thẳng 401 có thể do tài khoản/mật khẩu. */
    @Test
    void unauthorizedOnEndpointThatDoesNotRequireAuth_keepsLabelButAddsCredentialsCaveat() {
        Result result = classifier.classify(unauthorized(false));

        assertThat(result.cause()).isEqualTo(FailureCause.AUTH_CONFIG);
        assertThat(result.evidence()).anyMatch(e -> e.contains("spec không khai endpoint này yêu cầu xác thực")
                && e.contains("401") && e.contains("tài khoản/mật khẩu"));
    }

    @Test
    void unauthorizedOnEndpointThatRequiresOrUnknownAuth_hasNoCredentialsCaveat() {
        for (Boolean requiresAuth : new Boolean[]{true, null}) {
            Result result = classifier.classify(unauthorized(requiresAuth));

            assertThat(result.cause()).as("requiresAuth=" + requiresAuth).isEqualTo(FailureCause.AUTH_CONFIG);
            assertThat(result.evidence()).as("requiresAuth=" + requiresAuth).noneMatch(e -> e.contains("tài khoản/mật khẩu"));
        }
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

    /**
     * Pass/Fail xen kẽ là dấu hiệu không ổn định, KHÔNG phải trạng thái đổi 1 lần - không được gắn DATA_STATE. Giai đoạn 1
     * chỉ ra "chưa đủ dữ liệu"; từ giai đoạn 2, đủ 5 lần xen kẽ thì gắn thẳng nhãn "Không ổn định".
     */
    @Test
    void alternatingResults_areFlakyNotDataState() {
        List<HistoryPoint> history = history(pass(0), fail(1, 500), pass(2), fail(3, 500), fail(4, 500));

        assertThat(classifier.classify(failed(200, 500, history)).cause()).isEqualTo(FailureCause.FLAKY);
    }

    @Test
    void alternatingButFewerThanFiveRuns_stillNotDataStateAndNotYetFlaky() {
        assertThat(classifier.classify(failed(200, 500, history(pass(0), fail(1, 500), pass(2), fail(3, 500)))).cause())
                .isEqualTo(FailureCause.UNDETERMINED);
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

    // ---- Test dùng profile vai trò (Module 17 - 3.1) ----

    @Test
    void forbiddenOnTestUsingAnAuthProfile_isNeverBlamedOnTheProjectToken() {
        // 403 của vai trò Candidate khi gọi endpoint của HR: có thể là phân quyền đúng/sai, không nói gì về token của project.
        Input in = new Input(TestResultStatus.FAILED, 200, 403, null, 50L, null, TestCaseAuthOverride.DEFAULT, history(fail(0, 403)),
                "http://target", null, ExecutionContext.none(), List.of(), null, true, java.util.Map.of(), false, true, 0);

        assertThat(classifier.classify(in).cause()).isNotEqualTo(FailureCause.AUTH_CONFIG);
    }

    @Test
    void sameForbiddenOnProjectDefaultToken_stillBlamedOnTheToken() {
        Input in = new Input(TestResultStatus.FAILED, 200, 403, null, 50L, null, TestCaseAuthOverride.DEFAULT, history(fail(0, 403)),
                "http://target", null, ExecutionContext.none(), List.of(), null, true, java.util.Map.of(), false, false, 0);

        assertThat(classifier.classify(in).cause()).isEqualTo(FailureCause.AUTH_CONFIG);
    }

    // ---- AUTHZ_VIOLATION (Module 17 - 17a) ----

    private Input profileCall(Integer expected, Integer actual, int profileAcceptedCount) {
        return new Input(TestResultStatus.FAILED, expected, actual, null, 50L, null, TestCaseAuthOverride.DEFAULT, history(fail(0, actual)),
                "http://target", null, ExecutionContext.none(), List.of(), null, true, java.util.Map.of(), false, true, profileAcceptedCount);
    }

    @Test
    void profileExpectedDeniedButCallSucceeds_isAuthzViolationEscalation() {
        Result result = classifier.classify(profileCall(403, 200, 5));

        assertThat(result.cause()).isEqualTo(FailureCause.AUTHZ_VIOLATION);
        assertThat(result.evidence().get(0)).contains("bị cấm").contains("200");
        // Phát hiện thật: không bị loại khỏi tự động hoá Bug Report như lỗi môi trường/token.
        assertThat(FailureCause.AUTHZ_VIOLATION.isNotApiFault()).isFalse();
        assertThat(FailureCause.AUTHZ_VIOLATION.suppressesBugAutomation()).isFalse();
    }

    @Test
    void profileExpectedAllowedButForbidden_isAuthzViolation() {
        assertThat(classifier.classify(profileCall(200, 403, 5)).cause()).isEqualTo(FailureCause.AUTHZ_VIOLATION);
    }

    @Test
    void profile401WithNoAcceptedTestForThatProfile_isAuthConfigOfTheProfile() {
        Result result = classifier.classify(profileCall(200, 401, 0));

        assertThat(result.cause()).isEqualTo(FailureCause.AUTH_CONFIG);
        assertThat(result.evidence()).anyMatch(e -> e.contains("vai trò"));
    }

    @Test
    void profile401WhileOtherTestsOfThatProfileAreAccepted_isNotBlamedOnToken() {
        assertThat(classifier.classify(profileCall(200, 401, 3)).cause()).isNotEqualTo(FailureCause.AUTH_CONFIG);
    }

    @Test
    void deniedExpectedAnd401Received_staysAnExpectationMismatchNotAViolation() {
        // Kỳ vọng 403 mà API trả 401: cùng nhóm "từ chối" - API vẫn chặn đúng, chỉ khác mã.
        assertThat(classifier.classify(profileCall(403, 401, 2)).cause()).isEqualTo(FailureCause.EXPECTATION_MISMATCH);
    }

    // ---- STATE_CONFLICT (Module 17 - 1.5) ----

    @Test
    void conflict409_whenExpectingOther_isStateConflictEvenOnFirstRun() {
        // Ca thật: POST tạo công ty (mỗi HR chỉ 1) - 20/30 test Negative nhận 409 ở lần chạy đầu, trước đây UNDETERMINED.
        Result result = classifier.classify(failed(400, 409, history(fail(0, 409))));

        assertThat(result.cause()).isEqualTo(FailureCause.STATE_CONFLICT);
        assertThat(result.evidence().get(0)).contains("409");
        assertThat(FailureCause.STATE_CONFLICT.isNotApiFault()).isFalse();
        assertThat(FailureCause.STATE_CONFLICT.suppressesBugAutomation()).isTrue();
    }

    @Test
    void conflict409_whenExpecting409_isNotStateConflict() {
        // Kỳ vọng 409 mà nhận 409 là PASS (không tới được classifier); nhận 200 khi kỳ vọng 409 là nhãn khác.
        Result result = classifier.classify(failed(409, 200, history(fail(0, 200))));

        assertThat(result.cause()).isNotEqualTo(FailureCause.STATE_CONFLICT);
    }

    @Test
    void conflict409_afterPassing_isDataStateNotStateConflict() {
        // Từng Pass rồi mới 409 liên tiếp là bằng chứng "dữ liệu đổi" mạnh hơn - giữ DATA_STATE như trước.
        Result result = classifier.classify(failed(200, 409, history(pass(0), pass(1), fail(2, 409), fail(3, 409))));

        assertThat(result.cause()).isEqualTo(FailureCause.DATA_STATE);
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

    // ---- ASSERTION_FAILED + test vừa được sửa ----

    private Input withAssertions(Integer expected, Integer actual, List<FailureCauseClassifier.FailedAssertion> failedAssertions,
                                 List<HistoryPoint> history, HistoryPoint lastOtherVersion) {
        return new Input(TestResultStatus.FAILED, expected, actual, null, 194L, null, TestCaseAuthOverride.DEFAULT, history,
                "http://target", null, ExecutionContext.none(), failedAssertions, lastOtherVersion);
    }

    private static FailureCauseClassifier.FailedAssertion failedAssertion(String path, String op, String expected, String actual) {
        return new FailureCauseClassifier.FailedAssertion(path, op, expected, actual);
    }

    /** Đúng ca người dùng gặp: mã trạng thái khớp (404 → 404) nhưng assertion sai - phải nói thẳng fail do assertion. */
    @Test
    void statusMatchesButAssertionFails_isAssertionFailedNotUndetermined() {
        Input in = withAssertions(404, 404, List.of(failedAssertion("1", "EQUALS", "1", null)), history(fail(1, 404)), null);

        Result result = classifier.classify(in);

        assertThat(result.cause()).isEqualTo(FailureCause.ASSERTION_FAILED);
        assertThat(result.cause().isNotApiFault()).as("không loại khỏi số liệu API").isFalse();
        assertThat(result.evidence().get(0)).contains("Mã trạng thái đúng kỳ vọng (404)").contains("1 assertion sai")
                .contains("không phải do mã trạng thái");
        assertThat(result.evidence()).anyMatch(e -> e.contains("`1` EQUALS") && e.contains("kỳ vọng \"1\"") && e.contains("thực tế \"—\""));
        assertThat(result.evidence()).anyMatch(e -> e.contains("black-box không phân biệt"));
    }

    @Test
    void existsAssertion_isDescribedAsMissingFieldNotAsValueMismatch() {
        Input in = withAssertions(200, 200, List.of(failedAssertion("data.id", "EXISTS", null, null)), history(fail(1, 200)), null);

        assertThat(classifier.classify(in).evidence()).anyMatch(e -> e.contains("`data.id` EXISTS: không tìm thấy trường này"));
    }

    @Test
    void manyFailedAssertions_listedUpToFiveThenSummarised() {
        List<FailureCauseClassifier.FailedAssertion> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(failedAssertion("f" + i, "EQUALS", "a", "b"));
        }

        Result result = classifier.classify(withAssertions(200, 200, eight, history(fail(1, 200)), null));

        assertThat(result.evidence()).filteredOn(e -> e.startsWith("Assertion `")).hasSize(5);
        assertThat(result.evidence()).anyMatch(e -> e.contains("và 3 assertion sai khác"));
    }

    @Test
    void failedWithMatchingStatusButNoAssertionDetail_stillAssertionFailed() {
        // JSON assertion hỏng/thiếu: vẫn biết chắc fail do assertion (runner chỉ chấm assertion khi mã đúng).
        Result result = classifier.classify(withAssertions(200, 200, List.of(), history(fail(1, 200)), null));

        assertThat(result.cause()).isEqualTo(FailureCause.ASSERTION_FAILED);
        assertThat(result.evidence().get(0)).contains("do assertion về nội dung phản hồi");
    }

    /** Mã 503 chính là điều test KỲ VỌNG: không phải dấu hiệu lỗi môi trường - đi theo nhánh assertion. */
    @Test
    void expectedGatewayStatusThatMatches_isNotEnvironment() {
        Input in = withAssertions(503, 503, List.of(failedAssertion("msg", "CONTAINS", "x", "y")), history(fail(1, 503)), null);

        assertThat(classifier.classify(in).cause()).isEqualTo(FailureCause.ASSERTION_FAILED);
    }

    @Test
    void statusMismatch_isNeverAssertionFailed() {
        // Mã sai kỳ vọng thì nguyên nhân thuộc nhóm theo mã - dù kèm assertion nào.
        Input in = withAssertions(200, 404, List.of(failedAssertion("x", "EQUALS", "1", "2")), history(fail(1, 404)), null);

        assertThat(classifier.classify(in).cause()).isNotEqualTo(FailureCause.ASSERTION_FAILED);
    }

    @Test
    void assertionFailureAfterEditingTheTest_saysTheTestWasEditedAndWhatHappenedBefore() {
        HistoryPoint before = new HistoryPoint(TestResultStatus.PASSED, 404, T0, "http://target");
        Input in = withAssertions(404, 404, List.of(failedAssertion("1", "EQUALS", "1", null)), history(fail(5, 404)), before);

        Result result = classifier.classify(in);

        assertThat(result.evidence()).anyMatch(e -> e.contains("đã được sửa kể từ lần chạy gần nhất ở phiên bản trước")
                && e.contains("kết quả lúc đó là Pass") && e.contains("có thể do chính thay đổi vừa làm"));
    }

    @Test
    void undeterminedAfterEditingTheTest_stillSaysTheTestWasEdited() {
        HistoryPoint before = new HistoryPoint(TestResultStatus.FAILED, 500, T0, "http://target");
        Input in = withAssertions(200, 500, List.of(), history(fail(5, 500)), before);

        Result result = classifier.classify(in);

        assertThat(result.cause()).isEqualTo(FailureCause.UNDETERMINED);
        assertThat(result.evidence()).anyMatch(e -> e.contains("1 lần Fail chưa đủ"))
                .anyMatch(e -> e.contains("đã được sửa") && e.contains("Fail(500)"));
    }

    /** Sau khi sửa đã chạy Pass rồi mới Fail: việc sửa test không còn là nghi can chính nên không nhắc lại. */
    @Test
    void failAfterSeveralRunsOfTheNewVersion_noEditedEvidence() {
        HistoryPoint before = new HistoryPoint(TestResultStatus.PASSED, 404, T0, "http://target");
        Input in = withAssertions(404, 404, List.of(failedAssertion("1", "EQUALS", "1", null)),
                history(pass(5), fail(6, 404)), before);

        Result result = classifier.classify(in);

        assertThat(result.cause()).isEqualTo(FailureCause.ASSERTION_FAILED);
        assertThat(result.evidence()).noneMatch(e -> e.contains("đã được sửa"));
    }

    @Test
    void undeterminedAfterSeveralRunsOfTheNewVersion_noEditedEvidence() {
        HistoryPoint before = new HistoryPoint(TestResultStatus.FAILED, 500, T0, "http://target");
        Input in = withAssertions(200, 500, List.of(), history(pass(5), fail(6, 500)), before);

        Result result = classifier.classify(in);

        assertThat(result.cause()).isEqualTo(FailureCause.UNDETERMINED);
        assertThat(result.evidence()).noneMatch(e -> e.contains("đã được sửa"));
    }

    @Test
    void neverRunAtAnotherVersion_noEditedEvidence() {
        Result result = classifier.classify(withAssertions(200, 500, List.of(), history(fail(1, 500)), null));

        assertThat(result.evidence()).noneMatch(e -> e.contains("đã được sửa"));
    }

    // ---- FLAKY (giai đoạn 2) ----

    @Test
    void alternatingFiveRuns_failIsFlaky() {
        // Pass, Fail, Pass, Pass, Fail(lần hiện tại): đổi 3 lần trong 5 lần chạy cùng phiên bản test.
        List<HistoryPoint> history = history(pass(0), fail(1, 500), pass(2), pass(3), fail(4, 500));

        Result result = classifier.classify(failed(200, 500, history));

        assertThat(result.cause()).isEqualTo(FailureCause.FLAKY);
        assertThat(result.evidence().get(0)).contains("Pass → Fail(500) → Pass → Pass → Fail(500)")
                .contains("đổi qua lại 3 lần trong 5 lần chạy").contains("Pass 3/5");
        assertThat(result.evidence()).anyMatch(e -> e.contains("không tự mở lại hay đóng Bug Report"))
                .anyMatch(e -> e.contains("black-box không phân biệt"));
        assertThat(result.cause().isNotApiFault()).isFalse();
        assertThat(result.cause().suppressesBugAutomation()).isTrue();
    }

    @Test
    void fourRunsOnly_isNotYetFlaky() {
        // Chưa đủ 5 lần: chỉ UNDETERMINED (gợi ý chạy lại nằm ở phần stability của kết quả), không gắn nhãn Không ổn định.
        List<HistoryPoint> history = history(pass(0), fail(1, 500), pass(2), fail(3, 500));

        assertThat(classifier.classify(failed(200, 500, history)).cause()).isEqualTo(FailureCause.UNDETERMINED);
    }

    @Test
    void flakyHistoryButOtherSpecificOriginWins_authEnvironmentAndAssertionStayMoreSpecific() {
        List<HistoryPoint> flakyHistory = history(pass(0), fail(1, 500), pass(2), pass(3), fail(4, 403));

        assertThat(classifier.classify(failed(200, 403, flakyHistory)).cause()).isEqualTo(FailureCause.AUTH_CONFIG);
        assertThat(classifier.classify(errored(ErrorKind.CONNECTION, 5L, null, ExecutionContext.none(), flakyHistory)).cause())
                .isEqualTo(FailureCause.ENVIRONMENT);
        Input assertion = withAssertions(200, 200, List.of(failedAssertion("a", "EQUALS", "1", "2")), flakyHistory, null);
        assertThat(classifier.classify(assertion).cause()).isEqualTo(FailureCause.ASSERTION_FAILED);
    }

    @Test
    void tokenToggling_isNotFlaky() {
        // Pass khi có token, Fail do token xen kẽ: các lần Fail do token không tính - còn lại Pass, Pass, Fail(hiện tại).
        List<HistoryPoint> history = history(
                pass(0),
                new HistoryPoint(TestResultStatus.FAILED, 403, T0.plusSeconds(60), "http://target", FailureCause.AUTH_CONFIG),
                pass(2),
                new HistoryPoint(TestResultStatus.FAILED, 403, T0.plusSeconds(180), "http://target", FailureCause.AUTH_CONFIG),
                fail(4, 500));

        assertThat(classifier.classify(failed(200, 500, history)).cause()).isNotEqualTo(FailureCause.FLAKY);
    }

    @Test
    void stableFailureAndDataStateNeverOverlapWithFlaky() {
        assertThat(classifier.classify(failed(200, 404, history(pass(0), pass(1), fail(2, 404), fail(3, 404), fail(4, 404)))).cause())
                .isEqualTo(FailureCause.DATA_STATE);
        assertThat(classifier.classify(failed(200, 500, history(fail(0, 500), fail(1, 500), fail(2, 500), fail(3, 500), fail(4, 500)))).cause())
                .isEqualTo(FailureCause.STABLE_FAILURE);
    }

    // ---- Thuộc tính của nhóm ----

    @Test
    void onlyStrongEvidenceCausesAreExcludedFromApiMetrics() {
        assertThat(List.of(FailureCause.values())).filteredOn(FailureCause::isNotApiFault)
                .containsExactlyInAnyOrder(FailureCause.TEST_SETUP, FailureCause.ENVIRONMENT, FailureCause.AUTH_CONFIG,
                        FailureCause.RESOURCE_NOT_FOUND);
    }
}
