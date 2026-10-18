package com.aiapitesting.backend.dto.response;

/**
 * Dữ liệu của project sẽ bị XOÁ khi import lại OpenAPI (xem EndpointImportService.doImport) - để giao diện cảnh báo
 * bằng số cụ thể trước khi người dùng xác nhận. Chỉ đếm, không đổi dữ liệu.
 *
 * @param manualTestCaseCount   test case thêm tay (không sinh lại được bằng AI)
 * @param lockedTestCaseCount   test case đã khoá - vẫn bị xoá khi import (khoá chỉ chặn việc sinh lại, không chặn import)
 * @param openBugReportCount    bug report chưa Closed
 * @param generationEventCount  lịch sử các lần sinh test case bằng AI
 */
public record ImportImpactResponse(
        long endpointCount,
        long testCaseCount,
        long manualTestCaseCount,
        long lockedTestCaseCount,
        long executionCount,
        long testResultCount,
        long bugReportCount,
        long openBugReportCount,
        long generationEventCount
) {
}
