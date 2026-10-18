package com.aiapitesting.backend.entity;

/**
 * Module 16 giai đoạn 2 - mức ổn định kết quả của 1 test case, tính từ lịch sử các lần chạy CÙNG phiên bản test
 * (cùng fingerprint). Chỉ là nhận định về tính tất định của KẾT QUẢ: kiểm thử black-box không biết nguyên nhân nằm ở
 * chính test (phụ thuộc dữ liệu/thời gian) hay ở API đích hành xử ngẫu nhiên.
 */
public enum StabilityState {
    /** Đổi Pass/Fail xen kẽ từ 2 lần trở lên trong đủ số lần chạy tối thiểu - không tin 1 lần chạy nào của test này. */
    FLAKY,
    /** Đã thấy dấu hiệu không nhất quán nhưng chưa đủ lần chạy để kết luận - nên chạy lại để có thêm bằng chứng. */
    NEEDS_MORE_RUNS,
    /** Không có dấu hiệu bất ổn đáng nói (kết quả nhất quán hoặc đã đổi 1 lần rồi giữ nguyên). */
    STABLE,
    /** Chưa đủ lần chạy cùng phiên bản để nói gì. */
    INSUFFICIENT
}
