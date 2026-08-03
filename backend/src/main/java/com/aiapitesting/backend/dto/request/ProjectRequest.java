package com.aiapitesting.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectRequest(
        @NotBlank(message = "Tên project không được để trống")
        @Size(max = 150, message = "Tên project tối đa 150 ký tự")
        String name,

        @Size(max = 2000, message = "Mô tả tối đa 2000 ký tự")
        String description
) {
}
