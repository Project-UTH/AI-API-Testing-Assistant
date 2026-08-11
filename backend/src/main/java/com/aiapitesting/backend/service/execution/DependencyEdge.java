package com.aiapitesting.backend.service.execution;

import java.util.UUID;

/**
 * Bản ghi nhẹ, tách khỏi entity Hibernate, mang thông tin 1 cạnh phụ thuộc (Test Data Chaining)
 * qua ranh giới @Async - tránh mọi rủi ro LazyInitializationException khi entity bị truyền sang
 * luồng khác sau khi session gốc đã đóng.
 */
public record DependencyEdge(UUID sourceTestCaseId, String sourceTestCaseName, String jsonPath, String placeholderName) {
}
