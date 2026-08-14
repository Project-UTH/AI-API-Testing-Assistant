package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;

import java.util.UUID;

public record TestResultResponse(
        UUID testCaseId,
        String testCaseName,
        UUID endpointId,
        TestResultStatus status,
        Integer expectedStatus,
        Integer responseStatus,
        String responseBody,
        String errorMessage
) {
    public static TestResultResponse from(TestResult result) {
        return new TestResultResponse(
                result.getTestCase().getId(),
                result.getTestCase().getName(),
                result.getTestCase().getEndpoint().getId(),
                result.getStatus(),
                result.getTestCase().getExpectedStatus(),
                result.getResponseStatus(),
                result.getResponseBody(),
                result.getErrorMessage()
        );
    }
}
