package com.aiapitesting.backend.dto.response;

/** Phản hồi DUY NHẤT chứa token thô - hiển thị đúng 1 lần lúc tạo, không có cách lấy lại. */
public record CreatedApiTokenResponse(ApiTokenResponse token, String value) {
}
