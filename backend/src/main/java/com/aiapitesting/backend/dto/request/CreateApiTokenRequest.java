package com.aiapitesting.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** expiresInDays = null: không hết hạn. */
public record CreateApiTokenRequest(
        @NotBlank(message = "Tên token không được để trống")
        @Size(max = 100, message = "Tên token tối đa 100 ký tự")
        String name,

        @Min(value = 1, message = "Hạn dùng tối thiểu 1 ngày")
        @Max(value = 365, message = "Hạn dùng tối đa 365 ngày")
        Integer expiresInDays
) {
}
