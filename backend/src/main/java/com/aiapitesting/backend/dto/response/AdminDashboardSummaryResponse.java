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
        long totalGenerationEvents,
        /** Tổng token AI đã dùng HÔM NAY (mọi user, giờ UTC). */
        long totalAiTokensToday,
        /** Giới hạn token/ngày mỗi user (`ai.quota.daily-token-limit`) - để frontend hiện "X / limit". */
        long aiDailyTokenLimit
) {
}
