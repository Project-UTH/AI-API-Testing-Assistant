package com.aiapitesting.backend.dto.request;

/** Phạm vi đo coverage của 1 endpoint - phải chọn ít nhất 1 loại. */
public record UpdateCoverageScopeRequest(boolean positive, boolean negative, boolean boundary, boolean security, Boolean owasp) {
    /** Dạng 4 loại (không đổi cờ OWASP) - giữ cho nơi gọi cũ. */
    public UpdateCoverageScopeRequest(boolean positive, boolean negative, boolean boundary, boolean security) {
        this(positive, negative, boundary, security, null);
    }
}
