package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/** Lịch sử kiểm thử của 1 endpoint (Module 8) - timeline gộp sự kiện sinh test case + chạy test. */
public record EndpointHistoryResponse(
        UUID endpointId,
        String endpointMethod,
        String endpointPath,
        String endpointSummary,
        int currentTestCaseCount,
        int totalRuns,
        List<HistoryEventResponse> events
) {
}
