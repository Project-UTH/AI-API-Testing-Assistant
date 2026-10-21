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

        String authValue,

        /** Chế độ đăng nhập tự động (G5/P1-4): có loginPath thì token do hệ thống tự lấy, bỏ qua authValue. */
        @Size(max = 500, message = "Đường dẫn đăng nhập tối đa 500 ký tự")
        String loginPath,

        /** Lúc sửa: null/rỗng = giữ body đăng nhập cũ. */
        @Size(max = 5000, message = "Body đăng nhập tối đa 5000 ký tự")
        String loginBody,

        @Size(max = 200, message = "JSONPath token tối đa 200 ký tự")
        String tokenJsonPath
) {
    public AuthProfileRequest(String name, String roleKey, TargetAuthType authType, String authValue) {
        this(name, roleKey, authType, authValue, null, null, null);
    }
}
