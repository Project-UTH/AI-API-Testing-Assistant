package com.aiapitesting.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// currentPassword KHÔNG @NotBlank - tài khoản chưa từng có mật khẩu thật (User.passwordSet=false,
// vd mới tạo qua Google) gửi rỗng/null cũng hợp lệ, AuthService.changePassword() tự quyết định có
// bắt buộc so khớp hay không dựa vào passwordSet.
public record ChangePasswordRequest(
        String currentPassword,
        @NotBlank @Size(min = 8, message = "Mật khẩu mới phải có ít nhất 8 ký tự") String newPassword
) {
}
