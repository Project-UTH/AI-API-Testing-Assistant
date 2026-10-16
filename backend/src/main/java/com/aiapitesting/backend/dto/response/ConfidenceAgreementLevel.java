package com.aiapitesting.backend.dto.response;

/**
 * Module 15e (Tier 3) - mức đồng thuận giữa test case đã lưu và bản AI ĐỘC LẬP tự xây dựng lại
 * (chỉ dựa vào tên/mô tả, không xem dữ liệu gốc). Tính tất định từ việc so sánh 2 bản - KHÔNG phải
 * AI tự chấm điểm chính nó.
 */
public enum ConfidenceAgreementLevel {
    /** Cả expectedStatus lẫn tập field requestBody đều khớp. */
    HIGH,
    /** Chỉ 1 trong 2 khớp. */
    PARTIAL,
    /** Không khớp cả 2. */
    LOW
}
