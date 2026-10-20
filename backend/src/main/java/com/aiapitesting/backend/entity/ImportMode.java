package com.aiapitesting.backend.entity;

/** Module 17 (17c) - cách áp spec mới lên project đã có endpoint. */
public enum ImportMode {
    /** Giữ endpoint không đổi (cùng test case, lịch sử, ma trận phân quyền); chỉ thêm mới/cập nhật endpoint đổi spec/đánh dấu endpoint không còn trong spec. */
    DIFF,
    /** Hành vi cũ: xoá toàn bộ endpoint, test case, lịch sử chạy, bug report rồi tạo lại. */
    REPLACE
}
