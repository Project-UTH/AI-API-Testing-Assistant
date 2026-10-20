package com.aiapitesting.backend.service.report;

import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.service.report.ExecutionConclusionCalculator.Conclusion;
import com.aiapitesting.backend.service.report.ExecutionConclusionCalculator.FailOn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionConclusionCalculatorTest {

    private static TestResult r(TestResultStatus status, FailureCause cause, Boolean unstable) {
        TestResult result = ReportTestSupport.result("GET", "/a", "t", status, 200, 200, cause, null);
        result.setUnstable(unstable);
        return result;
    }

    private static Conclusion conclusion(ExecutionStatus status, FailOn failOn, TestResult... results) {
        return ExecutionConclusionCalculator.calculate(status, List.of(results), failOn).conclusion();
    }

    @Test
    void allPassed_isPassed() {
        assertThat(conclusion(ExecutionStatus.COMPLETED, FailOn.API_FAIL, r(TestResultStatus.PASSED, null, null))).isEqualTo(Conclusion.PASSED);
    }

    @Test
    void apiFailure_failsThePipeline_inBothModes() {
        TestResult apiFail = r(TestResultStatus.FAILED, FailureCause.STABLE_FAILURE, null);

        assertThat(conclusion(ExecutionStatus.COMPLETED, FailOn.API_FAIL, apiFail)).isEqualTo(Conclusion.FAILED);
        assertThat(conclusion(ExecutionStatus.COMPLETED, FailOn.ANY_FAIL, apiFail)).isEqualTo(Conclusion.FAILED);
    }

    @Test
    void failFromTokenOrEnvironment_doesNotFailPipelineByDefault_butDoesInStrictMode_andWarns() {
        TestResult tokenFail = r(TestResultStatus.FAILED, FailureCause.AUTH_CONFIG, null);

        var lenient = ExecutionConclusionCalculator.calculate(ExecutionStatus.COMPLETED, List.of(tokenFail), FailOn.API_FAIL);
        assertThat(lenient.conclusion()).isEqualTo(Conclusion.PASSED);
        assertThat(lenient.summary().notApiFault()).isEqualTo(1);
        assertThat(lenient.summary().apiFailed()).isZero();
        assertThat(lenient.warnings()).anyMatch(w -> w.contains("môi trường/token"));
        assertThat(conclusion(ExecutionStatus.COMPLETED, FailOn.ANY_FAIL, tokenFail)).isEqualTo(Conclusion.FAILED);
    }

    @Test
    void unstableFailure_isNotCountedAsApiFailure() {
        TestResult flaky = r(TestResultStatus.FAILED, FailureCause.STABLE_FAILURE, true);

        assertThat(conclusion(ExecutionStatus.COMPLETED, FailOn.API_FAIL, flaky)).isEqualTo(Conclusion.PASSED);
    }

    @Test
    void errorAndBlocked_onlyFailInStrictMode() {
        TestResult error = r(TestResultStatus.ERROR, null, null);
        TestResult blocked = r(TestResultStatus.BLOCKED, null, null);

        assertThat(conclusion(ExecutionStatus.COMPLETED, FailOn.API_FAIL, error, blocked)).isEqualTo(Conclusion.PASSED);
        assertThat(conclusion(ExecutionStatus.COMPLETED, FailOn.ANY_FAIL, error)).isEqualTo(Conclusion.FAILED);
        assertThat(conclusion(ExecutionStatus.COMPLETED, FailOn.ANY_FAIL, blocked)).isEqualTo(Conclusion.FAILED);
    }

    @Test
    void runningExecutionIsPending_andCrashedExecutionIsError() {
        assertThat(conclusion(ExecutionStatus.RUNNING, FailOn.API_FAIL)).isEqualTo(Conclusion.PENDING);
        assertThat(conclusion(ExecutionStatus.PENDING, FailOn.ANY_FAIL)).isEqualTo(Conclusion.PENDING);
        assertThat(conclusion(ExecutionStatus.FAILED, FailOn.API_FAIL, r(TestResultStatus.PASSED, null, null))).isEqualTo(Conclusion.ERROR);
    }

    // ---- Module 17 (S3) - Fail "cần xem xét" tách khỏi Fail do API ----

    private static TestResult failedWith(FailureCause cause, Integer actual) {
        return ReportTestSupport.result("GET", "/a", "t", TestResultStatus.FAILED, actual, 200, cause, null);
    }

    @Test
    void undeterminedWith4xx_andTestDataSuspect_goToNeedsReview_andDoNotFailPipelineByDefault() {
        List<TestResult> results = List.of(failedWith(FailureCause.UNDETERMINED, 403), failedWith(FailureCause.TEST_DATA_SUSPECT, 400));

        ExecutionConclusionCalculator.Result byDefault = ExecutionConclusionCalculator.calculate(ExecutionStatus.COMPLETED, results, FailOn.API_FAIL);
        assertThat(byDefault.conclusion()).isEqualTo(Conclusion.PASSED);
        assertThat(byDefault.summary().needsReview()).isEqualTo(2);
        assertThat(byDefault.summary().apiFailed()).isZero();
        assertThat(byDefault.summary().failed()).isEqualTo(2);
        assertThat(byDefault.warnings()).anyMatch(w -> w.contains("2 kết quả Fail cần xem lại"));

        assertThat(ExecutionConclusionCalculator.calculate(ExecutionStatus.COMPLETED, results, FailOn.ANY_FAIL).conclusion())
                .isEqualTo(Conclusion.FAILED); // chế độ nghiêm ngặt giữ nguyên
    }

    @Test
    void undeterminedWith5xxOrNoResponse_stillCountsAsApiFailure_soServerErrorsNeverSlipThrough() {
        assertThat(conclusion(ExecutionStatus.COMPLETED, FailOn.API_FAIL, failedWith(FailureCause.UNDETERMINED, 500))).isEqualTo(Conclusion.FAILED);
        assertThat(conclusion(ExecutionStatus.COMPLETED, FailOn.API_FAIL, failedWith(FailureCause.UNDETERMINED, null))).isEqualTo(Conclusion.FAILED);
        assertThat(conclusion(ExecutionStatus.COMPLETED, FailOn.API_FAIL, failedWith(null, 404))).isEqualTo(Conclusion.FAILED);
    }

    @Test
    void mixOfNeedsReviewAndRealApiFailure_isStillRed_andCountsAreSeparate() {
        ExecutionConclusionCalculator.Result result = ExecutionConclusionCalculator.calculate(ExecutionStatus.COMPLETED,
                List.of(failedWith(FailureCause.TEST_DATA_SUSPECT, 400), failedWith(FailureCause.STABLE_FAILURE, 400)), FailOn.API_FAIL);
        assertThat(result.conclusion()).isEqualTo(Conclusion.FAILED);
        assertThat(result.summary().apiFailed()).isEqualTo(1);
        assertThat(result.summary().needsReview()).isEqualTo(1);
    }
}
