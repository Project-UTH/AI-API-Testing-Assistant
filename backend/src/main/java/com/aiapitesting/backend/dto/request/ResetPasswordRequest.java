package com.aiapitesting.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank @Email(message = "Email không đúng định dạng") String email,
        @NotBlank(message = "Vui lòng nhập mã xác nhận") String otp,
        @NotBlank @Size(min = 8, message = "Mật khẩu mới phải có ít nhất 8 ký tự") String newPassword
) {
}
