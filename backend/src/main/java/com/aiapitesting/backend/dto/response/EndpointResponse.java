package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.Endpoint;

import java.time.Instant;
import java.util.UUID;

public record EndpointResponse(UUID id, String path, String method, String summary, Instant createdAt) {
    public static EndpointResponse from(Endpoint endpoint) {
        return new EndpointResponse(
                endpoint.getId(),
                endpoint.getPath(),
                endpoint.getMethod(),
                endpoint.getSummary(),
                endpoint.getCreatedAt()
        );
    }
}
