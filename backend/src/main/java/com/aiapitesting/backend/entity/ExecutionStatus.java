package com.aiapitesting.backend.entity;

/**
 * Trạng thái của cả 1 lần TestExecution - khác với TestResultStatus (trạng thái từng test case
 * bên trong lần chạy đó). COMPLETED nghĩa là đã chạy xong hết danh sách test case, không có nghĩa
 * "toàn bộ PASSED" - từng test case lỗi/fail vẫn nằm trong TestResult riêng, execution vẫn
 * COMPLETED. FAILED chỉ dùng khi có lỗi ở tầng orchestration (vd exception ngoài dự kiến khi lập
 * lịch chạy), không phải khi "có test case nào đó fail".
 */
public enum ExecutionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}
