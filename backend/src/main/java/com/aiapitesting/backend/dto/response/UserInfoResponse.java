package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.entity.UserRole;

/**
 * Trả về bởi GET /auth/me - dùng để frontend biết role hiện tại (VD hiện/ẩn mục "Quản trị" ở
 * Sidebar) mà không cần giải mã JWT (JWT không mang role - xem CustomUserDetailsService, role
 * luôn đọc lại từ DB để cấp/thu quyền ADMIN qua SQL có hiệu lực ngay, không cần đăng nhập lại).
 */
public record UserInfoResponse(String email, UserRole role) {
    public static UserInfoResponse from(User user) {
        return new UserInfoResponse(user.getEmail(), user.getRole());
    }
}
