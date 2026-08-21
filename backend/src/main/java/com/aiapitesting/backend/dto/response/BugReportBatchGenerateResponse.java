package com.aiapitesting.backend.dto.response;

import java.util.List;

/** Kết quả sinh Bug Report hàng loạt từ trang Kết quả thực thi (BugReportService.generateForExecution). */
public record BugReportBatchGenerateResponse(
        List<BugReportResponse> created,
        int skippedCount
) {
}
