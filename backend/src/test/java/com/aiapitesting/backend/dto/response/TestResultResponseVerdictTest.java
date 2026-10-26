package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
import com.aiapitesting.backend.entity.TestCaseReliability;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.VerdictTrust;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 17q - độ tin kết luận ở trang kết quả chạy phải theo CÙNG quy tắc với trang Coverage (kiểm định 2026-10-23). */
class TestResultResponseVerdictTest {

    private final Endpoint endpoint = Endpoint.builder().id(UUID.randomUUID()).method("POST").path("/jobs").build();

    private TestCase testCase(int expected) {
        return TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).name("t").expectedStatus(expected).build();
    }

    private VerdictTrust trustOf(TestCase tc, TestResultStatus status, FailureCause cause) {
        TestResult result = TestResult.builder().id(UUID.randomUUID()).testCase(tc).status(status).failureCause(cause).build();
        return TestResultResponse.from(result, null).verdictTrust();
    }

    @Test
    void passOfSuccessOrDenialTest_isCertain_butPassOf400Test_isLeftToCoverage() {
        assertThat(trustOf(testCase(201), TestResultStatus.PASSED, null)).isEqualTo(VerdictTrust.CERTAIN);
        assertThat(trustOf(testCase(401), TestResultStatus.PASSED, null)).isEqualTo(VerdictTrust.CERTAIN);
        assertThat(trustOf(testCase(400), TestResultStatus.PASSED, null)).isNull();
    }

    @Test
    void passWithSpecDeviation_isDoubtful() {
        TestResult result = TestResult.builder().id(UUID.randomUUID()).testCase(testCase(200)).status(TestResultStatus.PASSED)
                .specDeviationsJson("[\"lệch\"]").build();
        assertThat(TestResultResponse.from(result, null).verdictTrust()).isEqualTo(VerdictTrust.DOUBTFUL);
    }

    @Test
    void fail_ofImplicitBoundaryOrAiWrittenExpectation_isAtMostDoubtful() {
        TestCase implicit = testCase(201);
        implicit.setChecklistType("BOUNDARY_ZERO");
        implicit.setGenerationOrigin(TestCaseGenerationOrigin.SYSTEM_FILLED);
        assertThat(trustOf(implicit, TestResultStatus.FAILED, FailureCause.STABLE_FAILURE)).isEqualTo(VerdictTrust.DOUBTFUL);

        TestCase aiWritten = testCase(201);
        aiWritten.setGenerationOrigin(TestCaseGenerationOrigin.AI_CHECKLIST);
        assertThat(trustOf(aiWritten, TestResultStatus.FAILED, FailureCause.STABLE_FAILURE)).isEqualTo(VerdictTrust.DOUBTFUL);

        TestCase systemNeedsReview = testCase(201);
        systemNeedsReview.setGenerationOrigin(TestCaseGenerationOrigin.SYSTEM_FILLED);
        systemNeedsReview.setReliabilityStatus(TestCaseReliability.NEEDS_REVIEW);
        assertThat(trustOf(systemNeedsReview, TestResultStatus.FAILED, FailureCause.STABLE_FAILURE)).isEqualTo(VerdictTrust.CERTAIN);
    }

    @Test
    void errorOrUnstable_isNoVerdict() {
        assertThat(trustOf(testCase(201), TestResultStatus.ERROR, null)).isEqualTo(VerdictTrust.NO_VERDICT);
        TestResult unstable = TestResult.builder().id(UUID.randomUUID()).testCase(testCase(201)).status(TestResultStatus.PASSED)
                .unstable(true).build();
        assertThat(TestResultResponse.from(unstable, null).verdictTrust()).isEqualTo(VerdictTrust.NO_VERDICT);
    }
}
