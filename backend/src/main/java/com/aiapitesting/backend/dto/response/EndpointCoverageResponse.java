package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Coverage Matrix + Risk Engine của 1 endpoint (Module 15c). {@code riskScore}/{@code riskLevel}
 * tính tất định (method/auth/dependency/security exposure/lịch sử fail) - không gọi AI, xem
 * {@code EndpointCoverageService}. {@code coveragePercent} chỉ tính trên dimension COVERED/MISSING
 * (loại NOT_APPLICABLE khỏi mẫu số).
 */
public record EndpointCoverageResponse(
        UUID endpointId,
        String path,
        String method,
        double coveragePercent,
        int riskScore,
        RiskLevel riskLevel,
        List<String> riskReasons,
        List<CoverageDimensionResponse> dimensions
) {
}
