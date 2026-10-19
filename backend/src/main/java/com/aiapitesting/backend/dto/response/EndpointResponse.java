package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.Endpoint;

import java.time.Instant;
import java.util.UUID;

/**
 * @param requiresAuth       Module 17 (1.3) - giá trị đang dùng (khai báo thủ công nếu có, không thì suy từ spec)
 * @param requiresAuthSource "SPEC" hoặc "MANUAL" - giúp UI nói rõ giá trị đến từ đâu
 */
public record EndpointResponse(UUID id, String path, String method, String summary, Instant createdAt, long testCaseCount,
                               boolean requiresAuth, String requiresAuthSource) {
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
                endpoint.getRequiresAuthManual() != null ? "MANUAL" : "SPEC"
        );
    }
}
