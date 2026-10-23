package com.aiapitesting.backend.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictTrustTest {

    private static final Set<FailureCause> CERTAIN_CAUSES = Set.of(
            FailureCause.STABLE_FAILURE, FailureCause.AUTHZ_VIOLATION, FailureCause.MASS_ASSIGNMENT, FailureCause.API_WRONG_STATUS,
            FailureCause.RULE_VIOLATION_ACCEPTED);

    @ParameterizedTest
    @EnumSource(FailureCause.class)
    void everyFailureCauseHasADefinedTrust(FailureCause cause) {
        VerdictTrust trust = VerdictTrust.ofFailure(cause);
        if (cause.isNotApiFault() || cause == FailureCause.FLAKY) {
            assertThat(trust).isEqualTo(VerdictTrust.NO_VERDICT);
        } else if (CERTAIN_CAUSES.contains(cause)) {
            assertThat(trust).isEqualTo(VerdictTrust.CERTAIN);
        } else {
            assertThat(trust).isEqualTo(VerdictTrust.DOUBTFUL);
        }
    }

    @Test
    void unstableOrNonConclusiveStatus_isNoVerdict() {
        assertThat(VerdictTrust.of(TestResultStatus.PASSED, null, true, true, false)).isEqualTo(VerdictTrust.NO_VERDICT);
        assertThat(VerdictTrust.of(TestResultStatus.FAILED, FailureCause.STABLE_FAILURE, true, true, false)).isEqualTo(VerdictTrust.NO_VERDICT);
        assertThat(VerdictTrust.of(TestResultStatus.ERROR, null, null, true, false)).isEqualTo(VerdictTrust.NO_VERDICT);
        assertThat(VerdictTrust.of(TestResultStatus.BLOCKED, null, null, true, false)).isEqualTo(VerdictTrust.NO_VERDICT);
        assertThat(VerdictTrust.of(null, null, null, true, false)).isEqualTo(VerdictTrust.NO_VERDICT);
    }

    @Test
    void pass_dependsOnOracleStrength() {
        assertThat(VerdictTrust.of(TestResultStatus.PASSED, null, null, true, false)).isEqualTo(VerdictTrust.CERTAIN);
        assertThat(VerdictTrust.of(TestResultStatus.PASSED, null, null, false, false)).isEqualTo(VerdictTrust.DOUBTFUL);
    }

    @Test
    void fail_withAssumedOracle_isCappedAtDoubtful_andLegacyFailWithoutCauseIsDoubtful() {
        assertThat(VerdictTrust.of(TestResultStatus.FAILED, FailureCause.STABLE_FAILURE, null, true, true)).isEqualTo(VerdictTrust.DOUBTFUL);
        assertThat(VerdictTrust.of(TestResultStatus.FAILED, FailureCause.ENVIRONMENT, null, true, true)).isEqualTo(VerdictTrust.NO_VERDICT);
        assertThat(VerdictTrust.of(TestResultStatus.FAILED, null, null, true, false)).isEqualTo(VerdictTrust.DOUBTFUL);
    }
}
