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
}
