package com.aiapitesting.backend.dto.response;

public record ApiResponse<T>(T data, Object meta) {
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, null);
    }
}
