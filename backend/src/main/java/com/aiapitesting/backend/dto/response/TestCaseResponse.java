package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.entity.TestCaseReliability;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
        String rationale,
        TestCaseReliability reliabilityStatus,
        List<String> reliabilityNotes,
        Integer expectedStatus,
        String resolvedPath,
        String pathParamFallbacks,
        TestCaseSource source,
        TestCaseAuthOverride authOverride,
        boolean locked,
        Instant createdAt,
        List<TestCaseDependencyResponse> dependencies,
        List<TestCaseAssertionResponse> assertions
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static TestCaseResponse from(TestCase testCase) {
        return from(testCase, List.of(), List.of());
    }

    public static TestCaseResponse from(TestCase testCase, List<TestCaseDependencyResponse> dependencies) {
        return from(testCase, dependencies, List.of());
    }

    public static TestCaseResponse from(
            TestCase testCase, List<TestCaseDependencyResponse> dependencies, List<TestCaseAssertionResponse> assertions
    ) {
        return new TestCaseResponse(
                testCase.getId(),
                testCase.getEndpoint().getId(),
                testCase.getEndpoint().getPath(),
                testCase.getEndpoint().getMethod(),
                testCase.getName(),
                testCase.getDescription(),
                testCase.getRequestHeaders(),
                testCase.getRequestBody(),
                testCase.getRationale(),
                testCase.getReliabilityStatus(),
                parseReliabilityNotes(testCase.getReliabilityNotes()),
                testCase.getExpectedStatus(),
                testCase.getResolvedPath(),
                testCase.getPathParamFallbacks(),
                testCase.getSource(),
                testCase.getAuthOverride(),
                testCase.isLocked(),
                testCase.getCreatedAt(),
                dependencies,
                assertions
        );
    }

    private static List<String> parseReliabilityNotes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
