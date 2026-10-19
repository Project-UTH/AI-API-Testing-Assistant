package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.dto.response.ExecutionDiffCategory;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.service.execution.ExecutionDiffClassifier.Side;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Module 17 (17c) - mỗi luật phân loại thay đổi giữa 2 lượt chạy, đặc biệt các tình huống dễ báo hồi quy oan. */
class ExecutionDiffClassifierTest {

    private static Side side(TestResultStatus status, FailureCause cause, Boolean unstable, String fingerprint) {
        return new Side(status, cause, unstable, fingerprint);
    }

    private static Side pass() {
        return side(TestResultStatus.PASSED, null, null, "fp");
    }

    private static Side fail(FailureCause cause) {
        return side(TestResultStatus.FAILED, cause, null, "fp");
    }

    private static ExecutionDiffCategory category(Side base, Side target) {
        return ExecutionDiffClassifier.classify(base, target).category();
    }

    @Test
    void passThenFailFromApi_isRegression() {
        assertThat(category(pass(), fail(FailureCause.STABLE_FAILURE))).isEqualTo(ExecutionDiffCategory.REGRESSED);
        assertThat(category(pass(), fail(null))).isEqualTo(ExecutionDiffCategory.REGRESSED); // dữ liệu cũ chưa có nguyên nhân
    }

    @Test
    void failThenPass_isFixed() {
        assertThat(category(fail(FailureCause.STABLE_FAILURE), pass())).isEqualTo(ExecutionDiffCategory.FIXED);
    }

    @Test
    void failBothTimes_isStillFailing_andPassBothTimes_isStillPassing() {
        assertThat(category(fail(FailureCause.UNDETERMINED), fail(FailureCause.STABLE_FAILURE))).isEqualTo(ExecutionDiffCategory.STILL_FAILING);
        assertThat(category(pass(), pass())).isEqualTo(ExecutionDiffCategory.STILL_PASSING);
    }

    @Test
    void failCausedByEnvironmentOrToken_isNeverCountedAsRegression() {
        // Token hết hạn ở lượt sau làm test Pass → Fail: không phải API hồi quy.
        assertThat(category(pass(), fail(FailureCause.AUTH_CONFIG))).isEqualTo(ExecutionDiffCategory.INCONCLUSIVE);
        assertThat(category(pass(), fail(FailureCause.ENVIRONMENT))).isEqualTo(ExecutionDiffCategory.INCONCLUSIVE);
        assertThat(category(pass(), fail(FailureCause.RESOURCE_NOT_FOUND))).isEqualTo(ExecutionDiffCategory.INCONCLUSIVE);
        // Và cũng không phải "đã sửa" khi lượt trước Fail do token rồi lượt sau Pass.
        assertThat(category(fail(FailureCause.AUTH_CONFIG), pass())).isEqualTo(ExecutionDiffCategory.INCONCLUSIVE);
    }

    @Test
    void blockedErrorOrSkipped_isInconclusive() {
        for (TestResultStatus status : new TestResultStatus[]{TestResultStatus.BLOCKED, TestResultStatus.ERROR, TestResultStatus.SKIPPED}) {
            assertThat(category(pass(), side(status, null, null, "fp"))).as(status.name()).isEqualTo(ExecutionDiffCategory.INCONCLUSIVE);
        }
    }

    @Test
    void unstableTest_isUnreliableEvenWhenResultFlips() {
        assertThat(category(pass(), side(TestResultStatus.FAILED, null, true, "fp"))).isEqualTo(ExecutionDiffCategory.UNRELIABLE);
        assertThat(category(side(TestResultStatus.PASSED, null, true, "fp"), fail(FailureCause.STABLE_FAILURE))).isEqualTo(ExecutionDiffCategory.UNRELIABLE);
        assertThat(category(pass(), fail(FailureCause.FLAKY))).isEqualTo(ExecutionDiffCategory.UNRELIABLE);
    }

    @Test
    void testEditedBetweenRuns_isChangedTestNotRegression() {
        Side before = side(TestResultStatus.PASSED, null, null, "fp-v1");
        Side after = side(TestResultStatus.FAILED, FailureCause.STABLE_FAILURE, null, "fp-v2");

        assertThat(category(before, after)).isEqualTo(ExecutionDiffCategory.CHANGED_TEST);
    }

    @Test
    void missingFingerprint_isTreatedAsChangedTestBecauseVersionCannotBeCompared() {
        assertThat(category(side(TestResultStatus.PASSED, null, null, null), pass())).isEqualTo(ExecutionDiffCategory.CHANGED_TEST);
        assertThat(category(pass(), side(TestResultStatus.PASSED, null, null, null))).isEqualTo(ExecutionDiffCategory.CHANGED_TEST);
    }

    @Test
    void testPresentInOnlyOneRun() {
        assertThat(category(null, pass())).isEqualTo(ExecutionDiffCategory.ONLY_IN_TARGET);
        assertThat(category(pass(), null)).isEqualTo(ExecutionDiffCategory.ONLY_IN_BASE);
    }

    @Test
    void severityOrderPutsRegressionsFirst() {
        // Thứ tự khai báo enum chính là thứ tự hiển thị.
        assertThat(ExecutionDiffCategory.values()[0]).isEqualTo(ExecutionDiffCategory.REGRESSED);
        assertThat(ExecutionDiffCategory.REGRESSED.ordinal()).isLessThan(ExecutionDiffCategory.FIXED.ordinal());
        assertThat(ExecutionDiffCategory.STILL_PASSING.ordinal()).isEqualTo(ExecutionDiffCategory.values().length - 1);
    }
}
