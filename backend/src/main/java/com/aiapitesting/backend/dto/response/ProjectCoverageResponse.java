package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Coverage toàn project (Module 15c).
 *
 * @param overallExecutedPercent Module 15j - % mục (toàn project) đã có test VÀ test đó đã chạy ra kết quả
 *                               thật; khoảng cách với {@code overallCoveragePercent} = phần "mới thiết kế, chưa chạy"
 * @param endpointsPage          Module 17 (G4) - chỉ có khi gọi theo trang: thông tin trang của danh sách {@code endpoints}
 *                               (đã lọc). Khi có, {@code endpoints} chỉ gồm các endpoint của trang (đủ chi tiết) còn
 *                               {@code overallCoveragePercent}/{@code overallExecutedPercent}/{@code overview} luôn tính trên TOÀN project.
 * @param overview               Module 17 (G4) - chỉ có khi gọi theo trang
 */
public record ProjectCoverageResponse(
        UUID projectId,
        double overallCoveragePercent,
        double overallExecutedPercent,
        List<EndpointCoverageResponse> endpoints,
        PageInfo endpointsPage,
        CoverageOverview overview
) {
    /** Dạng cũ (không phân trang): hai trường mới để null. */
    public ProjectCoverageResponse(
            UUID projectId, double overallCoveragePercent, double overallExecutedPercent, List<EndpointCoverageResponse> endpoints
    ) {
        this(projectId, overallCoveragePercent, overallExecutedPercent, endpoints, null, null);
    }

    public record PageInfo(int page, int size, long totalElements, int totalPages) {
    }
}
