package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.Endpoint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** OWASP probe là opt-in: tắt mặc định kể cả với endpoint cũ chưa chọn phạm vi, không ảnh hưởng 4 cờ còn lại. */
class CoverageScopeOwaspTest {

    @Test
    void owaspIsOffByDefault_forOldAndNewEndpoints() {
        assertThat(CoverageScope.of(Endpoint.builder().build())).isEqualTo(CoverageScope.FULL);
        assertThat(CoverageScope.FULL.owasp()).isFalse();
        assertThat(CoverageScope.of(Endpoint.builder().scopePositive(true).scopeNegative(false).scopeBoundary(true).scopeSecurity(true).build()).owasp()).isFalse();
    }

    @Test
    void owaspOnlyWhenExplicitlyEnabled_andKeepsFullScopeForUntouchedEndpoints() {
        CoverageScope onlyOwasp = CoverageScope.of(Endpoint.builder().scopeOwasp(true).build());
        assertThat(onlyOwasp.owasp()).isTrue();
        assertThat(onlyOwasp.positive() && onlyOwasp.negative() && onlyOwasp.boundary() && onlyOwasp.security()).isTrue();
        assertThat(CoverageScope.of(Endpoint.builder().scopeOwasp(false).build()).owasp()).isFalse();
    }
}
