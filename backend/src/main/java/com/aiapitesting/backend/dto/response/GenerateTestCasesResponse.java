package com.aiapitesting.backend.dto.response;

import java.util.List;

/** Response của POST .../generate-tests (Module 15h) - planSummary null khi chỉ sinh nhóm Security. */
public record GenerateTestCasesResponse(
        List<TestCaseResponse> testCases,
        GenerationPlanSummary planSummary
) {
}
