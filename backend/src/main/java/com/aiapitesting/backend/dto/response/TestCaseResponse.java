package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseSource;

import java.time.Instant;
import java.util.List;
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
        String resolvedPath,
        String pathParamFallbacks,
        TestCaseSource source,
        Instant createdAt,
        List<TestCaseDependencyResponse> dependencies
) {
    public static TestCaseResponse from(TestCase testCase) {
        return from(testCase, List.of());
    }

    public static TestCaseResponse from(TestCase testCase, List<TestCaseDependencyResponse> dependencies) {
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
                testCase.getResolvedPath(),
                testCase.getPathParamFallbacks(),
                testCase.getSource(),
                testCase.getCreatedAt(),
                dependencies
        );
    }
}
