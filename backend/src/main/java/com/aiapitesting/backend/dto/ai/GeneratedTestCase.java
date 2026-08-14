package com.aiapitesting.backend.dto.ai;

import java.util.Map;

/**
 * Structured-output type từ AI khi sinh test case (TestCaseGenerationService.generate()). Cũng
 * dùng làm shape lưu snapshot lịch sử (TestGenerationEvent.snapshotJson, Module 8) - serialize
 * thẳng List<GeneratedTestCase> ra JSON tại thời điểm sinh, đọc lại ở TestHistoryService.
 */
public record GeneratedTestCase(
        String name,
        String description,
        Map<String, String> requestHeaders,
        String requestBody,
        Integer expectedStatus,
        String resolvedPath,
        Map<String, String> pathParamFallbacks
) {
}
