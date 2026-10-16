package com.aiapitesting.backend.entity;

/**
 * Module 15b - kết quả đối chiếu tất định (Tier 1, không gọi AI) giữa giá trị test case AI sinh với
 * ràng buộc khai báo trong OpenAPI spec (required/enum/min-max). Null = không áp dụng (case MANUAL,
 * hoặc dữ liệu cũ tạo trước khi có field này).
 */
public enum TestCaseReliability {
    RELIABLE,
    NEEDS_REVIEW
}
