package com.aiapitesting.backend.dto.response;

import java.time.Instant;

public record ApiErrorResponse(String code, String message, Instant timestamp) {
    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(code, message, Instant.now());
    }
}
