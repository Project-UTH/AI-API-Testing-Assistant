package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.service.execution.FailureCauseClassifier.ExecutionContext;
import com.aiapitesting.backend.service.execution.FailureCauseClassifier.HistoryPoint;
import com.aiapitesting.backend.service.execution.FailureCauseClassifier.Input;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RESOURCE_NOT_FOUND (404 do id dự phòng) và EXPECTATION_MISMATCH (mã lệch kỳ vọng nhưng cùng nhóm nghĩa) - bảng luật
 * được chốt cùng chuyên gia nghiệp vụ: cặp nào KHÔNG BAO GIỜ được gắn nhãn (che bug thật) cũng có test riêng.
 */
class FailureCauseExpectationRulesTest {

    private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");
    private final FailureCauseClassifier classifier = new FailureCauseClassifier();

    private static HistoryPoint point(TestResultStatus status, int code, int minute) {
        return new HistoryPoint(status, code, T0.plusSeconds(60L * minute), "http://target");
    }

    private Input input(Integer expected, Integer actual, TestCaseAuthOverride auth, Boolean requiresAuth,
                        List<HistoryPoint> history, Map<String, String> fallbacks, boolean hasAssertions) {
        return new Input(TestResultStatus.FAILED, expected, actual, null, 50L, null, auth, history, "http://target", null,
                ExecutionContext.none(), List.of(), null, requiresAuth, fallbacks, hasAssertions, false);
    }

    private FailureCause causeOf(Input in) {
        return classifier.classify(in).cause();
    }

    // ---- RESOURCE_NOT_FOUND ----

    @Test
    void notFoundWithFallbackPathParamAndNeverPassed_isResourceNotFound() {
        Input in = input(200, 404, TestCaseAuthOverride.DEFAULT, true, List.of(point(TestResultStatus.FAILED, 404, 0)),
                Map.of("id", "1"), false);

        FailureCauseClassifier.Result result = classifier.classify(in);

        assertThat(result.cause()).isEqualTo(FailureCause.RESOURCE_NOT_FOUND);
        assertThat(result.cause().isNotApiFault()).isTrue();
        assertThat(result.evidence()).anyMatch(e -> e.contains("{id}=1"));
    }

    @Test
    void notFoundWithoutFallbackPathParam_isNotResourceNotFound() {
        Input in = input(200, 404, TestCaseAuthOverride.DEFAULT, true, List.of(point(TestResultStatus.FAILED, 404, 0)),
                Map.of(), false);

        assertThat(causeOf(in)).isNotEqualTo(FailureCause.RESOURCE_NOT_FOUND);
    }

    @Test
    void notFoundAfterAnEarlierPass_isLeftToHistoryRulesBecauseTheResourceDisappearedMidway() {
        Input in = input(200, 404, TestCaseAuthOverride.DEFAULT, true,
                List.of(point(TestResultStatus.PASSED, 200, 0), point(TestResultStatus.FAILED, 404, 1)),
                Map.of("id", "1"), false);

        assertThat(causeOf(in)).isNotEqualTo(FailureCause.RESOURCE_NOT_FOUND);
    }

    @Test
    void expectedNotFoundButGotNotFound_isNotLabelled() {
        // expected == actual: đây là ca assertion fail (mã đúng), không phải "chưa chạm đúng dữ liệu".
        Input in = input(404, 404, TestCaseAuthOverride.DEFAULT, true, List.of(point(TestResultStatus.FAILED, 404, 0)),
                Map.of("id", "1"), false);

        assertThat(causeOf(in)).isEqualTo(FailureCause.ASSERTION_FAILED);
    }

    @Test
    void resourceNotFoundBeatsStableFailure_soRepeated404sDoNotBecomeALikelyBug() {
        Input in = input(400, 404, TestCaseAuthOverride.DEFAULT, true,
                List.of(point(TestResultStatus.FAILED, 404, 0), point(TestResultStatus.FAILED, 404, 1),
                        point(TestResultStatus.FAILED, 404, 2), point(TestResultStatus.FAILED, 404, 3)),
                Map.of("id", "1"), false);

        assertThat(causeOf(in)).isEqualTo(FailureCause.RESOURCE_NOT_FOUND);
    }

    // ---- EXPECTATION_MISMATCH: cặp được gắn nhãn ----

    @Test
    void securityWithoutTokenGets403InsteadOf401_onAuthRequiredEndpoint_isExpectationMismatch() {
        for (TestCaseAuthOverride override : List.of(TestCaseAuthOverride.NONE, TestCaseAuthOverride.INVALID)) {
            Input in = input(401, 403, override, true, List.of(point(TestResultStatus.FAILED, 403, 0)), Map.of(), false);

            FailureCauseClassifier.Result result = classifier.classify(in);

            assertThat(result.cause()).as(override.name()).isEqualTo(FailureCause.EXPECTATION_MISMATCH);
            assertThat(result.cause().suppressesBugAutomation()).isTrue();
            assertThat(result.cause().isNotApiFault()).isFalse();
        }
    }

    @Test
    void badRequestVersusUnprocessable_bothDirections_isExpectationMismatch() {
        assertThat(causeOf(input(400, 422, TestCaseAuthOverride.DEFAULT, true, List.of(), Map.of(), false)))
                .isEqualTo(FailureCause.EXPECTATION_MISMATCH);
        assertThat(causeOf(input(422, 400, TestCaseAuthOverride.DEFAULT, true, List.of(), Map.of(), false)))
                .isEqualTo(FailureCause.EXPECTATION_MISMATCH);
    }

    @Test
    void differentPlainSuccessCodes_isExpectationMismatchOnlyWithoutAssertions() {
        assertThat(causeOf(input(201, 200, TestCaseAuthOverride.DEFAULT, true, List.of(), Map.of(), false)))
                .isEqualTo(FailureCause.EXPECTATION_MISMATCH);
        assertThat(causeOf(input(200, 204, TestCaseAuthOverride.DEFAULT, true, List.of(), Map.of(), true)))
                .as("có assertion nội dung thì 200 vs 204 không còn vô hại")
                .isNotEqualTo(FailureCause.EXPECTATION_MISMATCH);
    }

    // ---- EXPECTATION_MISMATCH: cặp KHÔNG BAO GIỜ được gắn (che bug thật) ----

    @Test
    void securityWithoutTokenButApiReturnsSuccess_isNeverAMismatch() {
        // Bỏ qua xác thực = lỗi nghiêm trọng, không được gợi ý "chấp nhận mã thực tế".
        for (int actual : List.of(200, 201, 204)) {
            Input in = input(401, actual, TestCaseAuthOverride.NONE, true, List.of(), Map.of(), false);
            assertThat(causeOf(in)).as("actual " + actual).isNotEqualTo(FailureCause.EXPECTATION_MISMATCH);
        }
    }

    @Test
    void authCodesOnPublicEndpoint_areNotTreatedAsTheSameGroup() {
        // Endpoint công khai: 403 có thể là CSRF/WAF/quyền chứ không phải "từ chối xác thực".
        assertThat(causeOf(input(401, 403, TestCaseAuthOverride.NONE, false, List.of(), Map.of(), false)))
                .isNotEqualTo(FailureCause.EXPECTATION_MISMATCH);
        assertThat(causeOf(input(401, 403, TestCaseAuthOverride.NONE, null, List.of(), Map.of(), false)))
                .isNotEqualTo(FailureCause.EXPECTATION_MISMATCH);
    }

    @Test
    void serverErrorsAndAcceptedCodeAndRejectedInputAccepted_areNeverMismatch() {
        // 5xx luôn là tín hiệu thật; 202 khác nghĩa 200; kỳ vọng từ chối mà API chấp nhận có thể là thiếu validate.
        assertThat(causeOf(input(400, 500, TestCaseAuthOverride.DEFAULT, true, List.of(), Map.of(), false)))
                .isNotEqualTo(FailureCause.EXPECTATION_MISMATCH);
        assertThat(causeOf(input(200, 202, TestCaseAuthOverride.DEFAULT, true, List.of(), Map.of(), false)))
                .isNotEqualTo(FailureCause.EXPECTATION_MISMATCH);
        for (int actual : List.of(200, 201)) {
            assertThat(causeOf(input(400, actual, TestCaseAuthOverride.DEFAULT, true, List.of(), Map.of(), false)))
                    .as("400 -> " + actual).isNotEqualTo(FailureCause.EXPECTATION_MISMATCH);
        }
        assertThat(causeOf(input(200, 400, TestCaseAuthOverride.DEFAULT, true, List.of(), Map.of(), false)))
                .isNotEqualTo(FailureCause.EXPECTATION_MISMATCH);
    }

    @Test
    void badRequestVersusNotFoundWithoutFallback_isNotAMismatch() {
        assertThat(causeOf(input(400, 404, TestCaseAuthOverride.DEFAULT, true, List.of(), Map.of(), false)))
                .isNotEqualTo(FailureCause.EXPECTATION_MISMATCH);
    }

    @Test
    void defaultAuthRejected_stillAuthConfig_notMismatch() {
        // Token mặc định bị từ chối thuộc nhóm AUTH_CONFIG (nghi token), không phải "kỳ vọng lệch".
        assertThat(causeOf(input(200, 401, TestCaseAuthOverride.DEFAULT, true, List.of(), Map.of(), false)))
                .isEqualTo(FailureCause.AUTH_CONFIG);
    }
}
