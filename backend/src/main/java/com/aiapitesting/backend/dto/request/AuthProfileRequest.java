package com.aiapitesting.backend.dto.request;

import com.aiapitesting.backend.entity.TargetAuthType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param roleKey   chỉ dùng lúc tạo (null = suy từ tên); bất biến sau đó
 * @param authValue lúc sửa: null/rỗng = giữ token cũ
 */
public record AuthProfileRequest(
        @NotBlank(message = "Tên profile không được để trống")
        @Size(max = 100, message = "Tên profile tối đa 100 ký tự")
        String name,

        @Size(max = 40, message = "Khoá vai trò tối đa 40 ký tự")
        String roleKey,

        @NotNull(message = "Phải chọn loại xác thực")
        TargetAuthType authType,

        String authValue
) {
}
