package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Coverage toàn project (Module 15c).
 *
 * @param overallExecutedPercent Module 15j - % mục (toàn project) đã có test VÀ test đó đã chạy ra kết quả
 *                               thật; khoảng cách với {@code overallCoveragePercent} = phần "mới thiết kế, chưa chạy"
 */
public record ProjectCoverageResponse(
        UUID projectId,
        double overallCoveragePercent,
        double overallExecutedPercent,
        List<EndpointCoverageResponse> endpoints
) {
}
