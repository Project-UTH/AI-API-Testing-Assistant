package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Tầng 2 (Bug Report) - 1 test case ĐÃ TỪNG CHẠY của endpoint (test case chưa chạy lần nào bị lọc
 * bỏ hẳn ở BugReportService.getBugReports() - không có gì để xem ở Tầng 3 nên không hiện).
 */
public record TestCaseBugSummaryResponse(UUID testCaseId, String testCaseName, List<BugReportResponse> bugs) {
}
