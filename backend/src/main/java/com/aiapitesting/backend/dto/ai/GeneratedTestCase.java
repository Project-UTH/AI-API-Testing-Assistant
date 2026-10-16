package com.aiapitesting.backend.dto.ai;

import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Structured-output type từ AI khi sinh test case. Cũng dùng làm shape lưu snapshot lịch sử
 * (TestGenerationEvent.snapshotJson) - serialize thẳng ra JSON tại thời điểm sinh.
 *
 * authOverride chỉ có ý nghĩa cho case nhóm Security - null nghĩa là DEFAULT.
 *
 * ignoreUnknown: snapshot lịch sử là dữ liệu lưu vĩnh viễn, có thể chứa field mà record này đã bỏ
 * (vd "assertions" trước Module 9b, khi còn AI tự sinh assertion) - không bỏ qua thì
 * TestHistoryService/HistoryFeedService.parseSnapshot() ném lỗi, cả 2 trang Lịch sử trả 500.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeneratedTestCase(
        String name,
        String description,
        Map<String, String> requestHeaders,
        String requestBody,
        Integer expectedStatus,
        String resolvedPath,
        Map<String, String> pathParamFallbacks,
        TestCaseAuthOverride authOverride,
        /** Module 15a (Explainable AI) - câu giải thích ngắn chỉ rõ rule/field nào của spec kích hoạt case này. Chỉ phục vụ con người đọc hiểu - KHÔNG dùng để tính Coverage Matrix (xem EndpointCoverageService). */
        String rationale
) {
}
