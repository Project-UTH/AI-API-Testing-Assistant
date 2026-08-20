package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/** Tầng 1 (Bug Report) - 1 endpoint đang có ít nhất 1 test case có bug report. */
public record EndpointBugSummaryResponse(
        UUID endpointId,
        String endpointMethod,
        String endpointPath,
        String endpointSummary,
        List<TestCaseBugSummaryResponse> testCases
) {
}
