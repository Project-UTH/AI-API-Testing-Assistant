package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.Endpoint;

import java.util.UUID;

/** "Component" của bug report (Module 10) suy ra động từ Endpoint, không lưu cột riêng trong bug_reports. */
public record ComponentRefResponse(UUID endpointId, String endpointMethod, String endpointPath) {
    public static ComponentRefResponse from(Endpoint endpoint) {
        return new ComponentRefResponse(endpoint.getId(), endpoint.getMethod(), endpoint.getPath());
    }
}
