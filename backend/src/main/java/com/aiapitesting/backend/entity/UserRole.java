package com.aiapitesting.backend.entity;

/**
 * Không có API nào trong hệ thống cho phép tự cấp/đổi thành ADMIN - cố ý, tránh tạo bề mặt tấn
 * công leo quyền. Cấp quyền ADMIN chỉ bằng cách chạy trực tiếp SQL trên DB
 * (VD {@code UPDATE users SET role='ADMIN' WHERE email='...'}) bởi người có quyền truy cập DB.
 */
public enum UserRole {
    USER,
    ADMIN
}
