package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.Endpoint;

import java.time.Instant;
import java.util.UUID;

/**
 * @param requiresAuth       Module 17 (1.3) - giá trị đang dùng (khai báo thủ công nếu có, không thì suy từ spec)
 * @param requiresAuthSource "SPEC" hoặc "MANUAL" - giúp UI nói rõ giá trị đến từ đâu
 * @param schemaChangedAt    Module 17 (17c) - lần import gần nhất đã đổi spec của endpoint (null = chưa đổi)
 * @param missingFromSpec    Module 17 (17c) - endpoint không còn trong spec ở lần import gần nhất
 */
public record EndpointResponse(UUID id, String path, String method, String summary, Instant createdAt, long testCaseCount,
                               boolean requiresAuth, String requiresAuthSource, Instant schemaChangedAt, boolean missingFromSpec) {
    public static EndpointResponse from(Endpoint endpoint) {
        return from(endpoint, 0);
    }

    public static EndpointResponse from(Endpoint endpoint, long testCaseCount) {
        return new EndpointResponse(
                endpoint.getId(),
                endpoint.getPath(),
                endpoint.getMethod(),
                endpoint.getSummary(),
                endpoint.getCreatedAt(),
                testCaseCount,
                endpoint.effectiveRequiresAuth(),
                endpoint.getRequiresAuthManual() != null ? "MANUAL" : "SPEC",
                endpoint.getSchemaChangedAt(),
                Boolean.TRUE.equals(endpoint.getMissingFromSpec())
        );
    }
}
