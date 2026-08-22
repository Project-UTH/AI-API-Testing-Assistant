package com.aiapitesting.backend.dto.response;

public record AdminDashboardSummaryResponse(
        long totalUsers,
        long totalProjects,
        long totalEndpoints,
        long totalTestCases,
        long totalTestResults,
        /** Tỷ lệ pass toàn hệ thống (0-100) - null nếu chưa có kết quả test nào. */
        Integer overallPassRate,
        long totalOpenBugs,
        /** Tổng số lần gọi AI sinh test case (mọi user) - ước lượng mức dùng AI của hệ thống. */
        long totalGenerationEvents
) {
}
