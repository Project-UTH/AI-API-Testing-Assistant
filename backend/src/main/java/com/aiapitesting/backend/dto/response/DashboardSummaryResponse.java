package com.aiapitesting.backend.dto.response;

/** Số liệu tổng quan trang Tổng quan (Module 8/11) - toàn bộ project user hiện tại sở hữu. */
public record DashboardSummaryResponse(
        long totalProjects,
        long totalEndpoints,
        long totalTestCases,
        long totalTestResults,
        /** Tỷ lệ pass toàn thời gian (0-100) - null nếu chưa từng chạy test nào. */
        Integer overallPassRate,
        /** Bug Report đang mở (khác CLOSED) trên toàn bộ project - cùng định nghĩa "open" với
         *  trang Bug Report từng project (BugDashboardSummaryResponse.openCount). */
        long totalOpenBugs
) {
}
