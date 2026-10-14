package com.aiapitesting.backend.dto.ai;

import com.aiapitesting.backend.entity.TestCaseAuthOverride;

import java.util.List;
import java.util.Map;

/**
 * Structured-output type từ AI khi sinh test case. Cũng dùng làm shape lưu snapshot lịch sử
 * (TestGenerationEvent.snapshotJson) - serialize thẳng ra JSON tại thời điểm sinh.
 *
 * authOverride chỉ có ý nghĩa cho case nhóm Security - null nghĩa là DEFAULT. assertions chỉ có ý
 * nghĩa khi includeAssertions=true - null/rỗng nghĩa là không có assertion nào.
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
        List<GeneratedAssertion> assertions,
        /** Module 15a (Explainable AI) - câu giải thích ngắn chỉ rõ rule/field nào của spec kích hoạt case này. Chỉ phục vụ con người đọc hiểu - KHÔNG dùng để tính Coverage Matrix (xem EndpointCoverageService). */
        String rationale
) {
}
