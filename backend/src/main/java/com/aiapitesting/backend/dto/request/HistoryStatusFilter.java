package com.aiapitesting.backend.dto.request;

/** Lọc trạng thái ở trang Lịch sử tổng (Module 8) - chỉ áp dụng cho sự kiện "Chạy test". */
public enum HistoryStatusFilter {
    ALL,
    HAS_FAIL,
    ALL_PASS
}
