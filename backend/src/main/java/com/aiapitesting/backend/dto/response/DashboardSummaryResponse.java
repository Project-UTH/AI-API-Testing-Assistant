package com.aiapitesting.backend.dto.response;

/** Số liệu tổng quan trang Tổng quan (Module 8/11) - toàn bộ project user hiện tại sở hữu. */
public record DashboardSummaryResponse(
        long totalProjects,
        long totalEndpoints,
        long totalTestCases,
        /** Số test case phân biệt đã chạy ít nhất 1 lần (khác totalTestResults - test case chạy
         *  nhiều lần vẫn chỉ tính 1). */
        long executedTestCaseCount,
        /** Tổng số lượt chạy, kể cả chạy lại - mẫu số của overallPassRate. */
        long totalTestResults,
        /** Số kết quả test PASS trong totalTestResults - tử số của overallPassRate. */
        long passedTestResults,
        /** Tỷ lệ pass = passedTestResults/totalTestResults * 100 - null nếu chưa từng chạy test nào. */
        Integer overallPassRate,
        /** Bug Report đang mở (khác CLOSED) trên toàn bộ project - cùng định nghĩa "open" với
         *  trang Bug Report từng project (BugDashboardSummaryResponse.openCount). */
        long totalOpenBugs,
        /** Token AI CHÍNH user này đã dùng hôm nay (giờ UTC). */
        long aiTokensToday,
        /** Giới hạn token/ngày HIỆU LỰC cho CHÍNH user này - đã resolve sẵn ở server (ghi đè riêng
         *  nếu admin có đặt, không thì mặc định hệ thống) để frontend không cần biết khái niệm
         *  ghi đè, chỉ cần so aiTokensToday với đúng 1 con số này. */
        long aiDailyTokenLimit
) {
}
