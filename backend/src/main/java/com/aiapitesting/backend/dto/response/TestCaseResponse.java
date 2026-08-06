package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.TestCase;

import java.time.Instant;
import java.util.UUID;

public record TestCaseResponse(
        UUID id,
        UUID endpointId,
        String name,
        String description,
        String requestHeaders,
        String requestBody,
        Integer expectedStatus,
        Instant createdAt
) {
    public static TestCaseResponse from(TestCase testCase) {
        return new TestCaseResponse(
                testCase.getId(),
                testCase.getEndpoint().getId(),
                testCase.getName(),
                testCase.getDescription(),
                testCase.getRequestHeaders(),
                testCase.getRequestBody(),
                testCase.getExpectedStatus(),
                testCase.getCreatedAt()
        );
    }
}
