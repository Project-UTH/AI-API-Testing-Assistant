package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Coverage Matrix + Risk Engine của 1 endpoint (Module 15c). {@code riskScore}/{@code riskLevel}
 * tính tất định (method/auth/dependency/security exposure/lịch sử fail) - không gọi AI, xem
 * {@code EndpointCoverageService}. {@code coveragePercent} chỉ tính trên dimension COVERED/MISSING
 * (loại NOT_APPLICABLE khỏi mẫu số).
 *
 * @param schemaReadable Module 15f - false khi {@code Endpoint.schema} không đọc được (JSON hỏng) -
 *                        lúc đó {@code coveragePercent}/{@code dimensions} KHÔNG đáng tin (thiếu hẳn
 *                        REQUIRED_FIELD/BOUNDARY/STATUS_CODE, không phải "không có gì để kiểm"), FE
 *                        phải cảnh báo rõ thay vì ngầm hiểu số % hiện có là đầy đủ.
 */
public record EndpointCoverageResponse(
        UUID endpointId,
        String path,
        String method,
        double coveragePercent,
        boolean schemaReadable,
        int riskScore,
        RiskLevel riskLevel,
        List<String> riskReasons,
        List<CoverageDimensionResponse> dimensions
) {
}
