package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseSource;

import java.time.Instant;
import java.util.UUID;

public record TestCaseResponse(
        UUID id,
        UUID endpointId,
        String endpointPath,
        String endpointMethod,
        String name,
        String description,
        String requestHeaders,
        String requestBody,
        Integer expectedStatus,
        TestCaseSource source,
        Instant createdAt
) {
    public static TestCaseResponse from(TestCase testCase) {
        return new TestCaseResponse(
                testCase.getId(),
                testCase.getEndpoint().getId(),
                testCase.getEndpoint().getPath(),
                testCase.getEndpoint().getMethod(),
                testCase.getName(),
                testCase.getDescription(),
                testCase.getRequestHeaders(),
                testCase.getRequestBody(),
                testCase.getExpectedStatus(),
                testCase.getSource(),
                testCase.getCreatedAt()
        );
    }
}
