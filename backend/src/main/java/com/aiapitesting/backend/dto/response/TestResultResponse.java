package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

public record TestResultResponse(
        UUID testCaseId,
        String testCaseName,
        UUID endpointId,
        TestResultStatus status,
        Integer expectedStatus,
        Integer responseStatus,
        String responseBody,
        String errorMessage,
        List<AssertionResultResponse> assertionResults
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static TestResultResponse from(TestResult result) {
        return new TestResultResponse(
                result.getTestCase().getId(),
                result.getTestCase().getName(),
                result.getTestCase().getEndpoint().getId(),
                result.getStatus(),
                result.getTestCase().getExpectedStatus(),
                result.getResponseStatus(),
                result.getResponseBody(),
                result.getErrorMessage(),
                parseAssertionResults(result.getAssertionResultsJson())
        );
    }

    private static List<AssertionResultResponse> parseAssertionResults(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<AssertionResultResponse>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
