package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Tầng 2 (Bug Report) - 1 test case ĐÃ TỪNG CHẠY của endpoint (test case chưa chạy lần nào bị lọc
 * bỏ hẳn ở BugReportService.getBugReports() - không có gì để xem ở Tầng 3 nên không hiện).
 * generatableResultIds: id các lần chạy Fail CHƯA có bug của test case này - cho frontend hiện
 * checkbox "Sinh Bug Report tuỳ chọn" ở cấp Endpoint/Test Case (Tầng 1/2) mà không cần mở Tầng 3
 * (Tầng 3 tải lười, chỉ có khi người dùng bấm mở "Các test đã chạy").
 */
public record TestCaseBugSummaryResponse(
        UUID testCaseId, String testCaseName, List<BugReportResponse> bugs, List<UUID> generatableResultIds
) {
}
