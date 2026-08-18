package com.aiapitesting.backend.dto.request;

/**
 * Bật/tắt khoá 1 test case (Module 9) - tách riêng khỏi TestCaseRequest (form sửa đầy đủ) vì đây
 * là thao tác nhanh 1 field duy nhất, không cần gửi lại toàn bộ dữ liệu test case.
 */
public record SetTestCaseLockedRequest(boolean locked) {
}
