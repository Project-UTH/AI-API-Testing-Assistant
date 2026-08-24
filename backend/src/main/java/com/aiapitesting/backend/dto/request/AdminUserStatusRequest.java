package com.aiapitesting.backend.dto.request;

/** Khoá/mở tài khoản 1 user (Module 11) - tách riêng khỏi mọi form khác, chỉ 1 field duy nhất. */
public record AdminUserStatusRequest(boolean enabled) {
}
