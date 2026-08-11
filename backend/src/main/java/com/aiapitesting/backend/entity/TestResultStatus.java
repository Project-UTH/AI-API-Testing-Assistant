package com.aiapitesting.backend.entity;

/**
 * Trạng thái của 1 TestCase trong 1 lần TestExecution - tách biệt với ExecutionStatus (trạng thái
 * của cả lần chạy). Xem giải thích ở skill api-contract mục 4.
 */
public enum TestResultStatus {
    /** Gọi được target API, status thật khớp expectedStatus. */
    PASSED,
    /** Gọi được target API, status thật không khớp expectedStatus. */
    FAILED,
    /** Lỗi hạ tầng khi gọi target API (network, timeout, không đọc được response). */
    ERROR,
    /** Phụ thuộc (Module 7) vào 1 test case khác không PASSED, hoặc không trích được giá trị JSONPath. */
    BLOCKED,
    /** Dự phòng cho trường hợp huỷ thủ công sau này - chưa dùng ở bản này. */
    SKIPPED
}
