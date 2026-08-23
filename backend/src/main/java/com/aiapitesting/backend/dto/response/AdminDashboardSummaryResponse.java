package com.aiapitesting.backend.dto.response;

public record AdminDashboardSummaryResponse(
        /** Chỉ đếm role USER - tài khoản ADMIN không tính vào đây (xem AdminDashboardService). */
        long totalUsers,
        long totalProjects,
        long totalEndpoints,
        long totalTestCases,
        /** Số test case phân biệt (mọi user) đã chạy ít nhất 1 lần. */
        long executedTestCaseCount,
        /** Tổng số lượt chạy (mọi user), kể cả chạy lại - mẫu số của overallPassRate. */
        long totalTestResults,
        /** Số kết quả test PASS trong totalTestResults - tử số của overallPassRate. */
        long passedTestResults,
        /** Tỷ lệ pass = passedTestResults/totalTestResults * 100 - null nếu chưa có kết quả test nào. */
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
