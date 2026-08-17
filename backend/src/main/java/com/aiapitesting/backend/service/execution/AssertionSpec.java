package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.AssertionOperator;

/**
 * Bản ghi nhẹ, tách khỏi entity Hibernate, mang thông tin 1 assertion (Module 9b) qua ranh giới
 * @Async - cùng lý do với DependencyEdge (tránh LazyInitializationException khi entity bị truyền
 * sang luồng khác sau khi session gốc đã đóng). AssertionOperator là enum thường (không phải
 * Hibernate proxy) nên an toàn để mang thẳng qua ranh giới này.
 */
public record AssertionSpec(String jsonPath, AssertionOperator operator, String expectedValue) {
}
