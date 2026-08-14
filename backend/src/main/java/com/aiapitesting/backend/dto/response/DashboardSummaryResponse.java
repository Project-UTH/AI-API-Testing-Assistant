package com.aiapitesting.backend.dto.response;

/** Số liệu tổng quan trang Tổng quan (Module 8) - toàn bộ project user hiện tại sở hữu. */
public record DashboardSummaryResponse(
        long totalProjects,
        long totalEndpoints,
        long totalTestCases,
        long totalTestResults,
        /** Tỷ lệ pass toàn thời gian (0-100) - null nếu chưa từng chạy test nào. */
        Integer overallPassRate
) {
}
