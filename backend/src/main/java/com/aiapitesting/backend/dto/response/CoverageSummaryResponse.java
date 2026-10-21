package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/** Coverage dạng nhẹ của cả project: số tổng + 1 dòng tóm tắt mỗi endpoint (không có chi tiết từng mục). */
public record CoverageSummaryResponse(
        UUID projectId,
        double overallCoveragePercent,
        double overallExecutedPercent,
        CoverageOverview overview,
        List<CoverageEndpointRow> endpoints
) {
}
