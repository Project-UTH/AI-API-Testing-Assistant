package com.aiapitesting.backend.service.execution;

/**
 * Module 17 (G5, P1-4) - đăng nhập tự động lấy token thất bại. Thông báo CHỈ nêu lý do chung (mã trạng thái, thiếu token): không bao
 * giờ chứa body gửi đi (có tài khoản/mật khẩu) hay body trả về (có thể chứa token).
 *
 * configProblem = true khi do cấu hình đăng nhập sai (đường dẫn/tài khoản/JSONPath) -> xếp vào lỗi thiết lập test;
 * false khi do lỗi mạng (phân loại môi trường theo exception gốc).
 */
public class AuthLoginException extends RuntimeException {

    private final boolean configProblem;

    public AuthLoginException(String message, boolean configProblem, Throwable cause) {
        super(message, cause);
        this.configProblem = configProblem;
    }

    public boolean isConfigProblem() {
        return configProblem;
    }
}
