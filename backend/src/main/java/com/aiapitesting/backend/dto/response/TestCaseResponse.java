package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
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
        Boolean reliabilityAiConfirmed,
        String reliabilityAiNote,
        Integer expectedStatus,
        String resolvedPath,
        String pathParamFallbacks,
        TestCaseSource source,
        TestCaseAuthOverride authOverride,
        UUID authProfileId,
        TestCaseGenerationOrigin generationOrigin,
        String checklistItem,
        boolean locked,
        Instant createdAt,
        List<TestCaseDependencyResponse> dependencies,
        List<TestCaseAssertionResponse> assertions,
        /** Module 17 (17b-2) - loại test bảo mật tự sinh ("MASS_ASSIGNMENT") hoặc null; probeFields = tên field đã tiêm (chỉ đọc). */
        String probeKind,
        List<String> probeFields,
        /** 17q G1 - kỳ vọng ban đầu trước khi hệ thống tự đổi sau lần chạy thật test gốc (null = chưa đổi) + ghi chú lý do. */
        Integer originalExpectedStatus,
        String expectationNote
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
                testCase.getReliabilityAiConfirmed(),
                testCase.getReliabilityAiNote(),
                testCase.getExpectedStatus(),
                testCase.getResolvedPath(),
                testCase.getPathParamFallbacks(),
                testCase.getSource(),
                testCase.getAuthOverride(),
                testCase.getAuthProfileId(),
                testCase.getGenerationOrigin(),
                testCase.getChecklistItem(),
                testCase.isLocked(),
                testCase.getCreatedAt(),
                dependencies,
                assertions,
                testCase.getProbeKind(),
                parseProbeFieldNames(testCase.getProbeFieldsJson()),
                testCase.getOriginalExpectedStatus(),
                testCase.getExpectationNote()
        );
    }

    private static List<String> parseProbeFieldNames(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<java.util.Map<String, Object>> fields = OBJECT_MAPPER.readValue(json, new TypeReference<List<java.util.Map<String, Object>>>() {
            });
            return fields.stream().map(f -> String.valueOf(f.get("name"))).toList();
        } catch (Exception e) {
            return List.of();
        }
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
