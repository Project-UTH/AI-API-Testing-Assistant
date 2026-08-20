package com.aiapitesting.backend.entity;

/**
 * Trạng thái xử lý bug report theo quy trình QA chuẩn (Module 10) - NEW đứng đầu để khớp mặc định
 * lúc tạo (@Builder.Default). Chuyển trạng thái tự động chỉ xảy ra ở 2 điểm hẹp (xem
 * BugReportStatusService): FAILED khi đang CLOSED -> REOPENED tự động; PASSED khi đang
 * PENDING/POSTED/REOPENED -> chỉ đặt cờ gợi ý đóng (BugReport.pendingCloseSuggestion), không tự
 * đổi status, chờ người dùng xác nhận qua BugReportService.confirmClose().
 */
public enum BugStatus {
    NEW,
    NEED_REVISE,
    READY_TO_SUBMIT,
    POSTED,
    PENDING,
    CANNOT_REPRODUCE,
    REOPENED,
    CLOSED
}
