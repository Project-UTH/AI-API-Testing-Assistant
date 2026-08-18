package com.aiapitesting.backend.dto.ai;

import com.aiapitesting.backend.entity.TestCaseAuthOverride;

import java.util.List;
import java.util.Map;

/**
 * Structured-output type từ AI khi sinh test case (TestCaseGenerationService.generate()). Cũng
 * dùng làm shape lưu snapshot lịch sử (TestGenerationEvent.snapshotJson, Module 8) - serialize
 * thẳng List<GeneratedTestCase> ra JSON tại thời điểm sinh, đọc lại ở TestHistoryService.
 *
 * authOverride chỉ có ý nghĩa cho case nhóm Security (Module 9a) - null/không có nghĩa là DEFAULT,
 * lần sinh nhóm Cơ bản không nhắc AI về field này nên thường sẽ null.
 *
 * assertions chỉ có ý nghĩa khi includeAssertions=true (Module 9b) - null/rỗng nghĩa là không có
 * assertion nào cho test case này, không bắt buộc AI phải trả về khi không bật tuỳ chọn này.
 */
public record GeneratedTestCase(
        String name,
        String description,
        Map<String, String> requestHeaders,
        String requestBody,
        Integer expectedStatus,
        String resolvedPath,
        Map<String, String> pathParamFallbacks,
        TestCaseAuthOverride authOverride,
        List<GeneratedAssertion> assertions
) {
}
