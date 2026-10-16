package com.aiapitesting.backend.dto.ai;

/**
 * Structured-output type từ AI khi ĐỘC LẬP tự xây dựng lại 1 test case đã có, CHỈ dựa vào tên/mô tả
 * (ý định) + schema endpoint - KHÔNG được xem dữ liệu cụ thể bản gốc (Module 15e, Tier 3). Dùng để
 * so sánh với bản đã lưu, đo độ đồng thuận.
 */
public record RegeneratedScenario(Integer expectedStatus, String requestBody, String note) {
}
