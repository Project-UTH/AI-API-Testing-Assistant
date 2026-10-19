package com.aiapitesting.backend.dto.response;

/**
 * Nhóm kết quả khi so sánh 2 lượt chạy (Module 17 - 17c). Thứ tự khai báo = thứ tự ưu tiên hiển thị (hồi quy trước hết).
 * Không lưu DB nên dùng enum thường.
 */
public enum ExecutionDiffCategory {
    /** Pass → Fail do API (nguyên nhân không phải môi trường/token/cấu hình test): hồi quy thật. */
    REGRESSED,
    /** Fail → Pass. */
    FIXED,
    /** Fail ở cả 2 lượt. */
    STILL_FAILING,
    /** Test case đã được SỬA giữa 2 lượt (fingerprint khác/thiếu) - lệch kết quả có thể do sửa test, không do API. */
    CHANGED_TEST,
    /** Kết quả không đủ tin cậy ở 1 trong 2 lượt: test không ổn định (FLAKY) - không báo hồi quy/được sửa oan. */
    UNRELIABLE,
    /** Không kết luận được: BLOCKED/SKIPPED/ERROR hoặc Fail do môi trường/token/cấu hình test ở 1 trong 2 lượt. */
    INCONCLUSIVE,
    ONLY_IN_TARGET,
    ONLY_IN_BASE,
    /** Pass ở cả 2 lượt - chỉ đếm, không liệt kê từng dòng (tránh hàng nghìn dòng vô ích). */
    STILL_PASSING
}
