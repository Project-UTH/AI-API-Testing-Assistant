package com.aiapitesting.backend.dto.request;

import com.aiapitesting.backend.service.report.ExecutionConclusionCalculator.FailOn;

import java.util.List;
import java.util.UUID;

/**
 * Yêu cầu chạy suite từ CI. scope mặc định ALL = mọi test case của project (trừ test case của endpoint đã không còn trong spec);
 * TEST_CASES cần testCaseIds; ENDPOINTS cần endpointIds. failOn mặc định API_FAIL (xem ExecutionConclusionCalculator).
 */
public record CiRunRequest(Scope scope, List<UUID> testCaseIds, List<UUID> endpointIds, FailOn failOn) {

    public enum Scope {
        ALL, TEST_CASES, ENDPOINTS
    }

    public Scope scopeOrDefault() {
        return scope == null ? Scope.ALL : scope;
    }

    public FailOn failOnOrDefault() {
        return failOn == null ? FailOn.API_FAIL : failOn;
    }
}
