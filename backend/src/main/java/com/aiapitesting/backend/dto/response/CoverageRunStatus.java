package com.aiapitesting.backend.dto.response;

/**
 * Module 15j - 1 mục coverage ĐÃ CÓ test case thì các case đó đã chạy ra kết quả chưa (lấy lần chạy mới
 * nhất của từng case phủ mục). "Có test" chỉ là đã thiết kế; chạy ra PASSED/FAILED mới là đã kiểm thật.
 */
public enum CoverageRunStatus {
    /** Chưa case nào phủ mục này từng được chạy. */
    NOT_RUN,
    /** Đã chạy nhưng chưa có kết luận - lần mới nhất là ERROR/BLOCKED/SKIPPED (không gọi được target API, bị chặn bởi chuỗi phụ thuộc). */
    NO_VERDICT,
    /** Ít nhất 1 case phủ mục này PASSED ở lần mới nhất, không case nào FAILED. */
    PASSED,
    /** Ít nhất 1 case phủ mục này FAILED ở lần mới nhất - đã kiểm và phát hiện API lệch kỳ vọng. */
    FAILED
}
