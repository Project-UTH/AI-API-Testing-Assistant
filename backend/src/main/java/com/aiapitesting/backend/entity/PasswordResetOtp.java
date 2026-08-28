package com.aiapitesting.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Mã OTP xác nhận "Quên mật khẩu" (Module 12). otpHash lưu qua BCrypt (tái dùng PasswordEncoder có
 * sẵn) - không lưu mã gốc, để lộ DB cũng không đoán ngược được mã. Mỗi lần yêu cầu OTP mới sẽ xoá
 * hết các dòng cũ CHƯA DÙNG của user đó (AuthService.forgotPassword) - tại 1 thời điểm chỉ có tối đa
 * 1 mã hợp lệ cho mỗi user, tránh nhầm lẫn mã nào còn hiệu lực.
 */
@Entity
@Table(name = "password_reset_otps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String otpHash;

    @Column(nullable = false)
    private Instant expiresAt;

    // Chặn brute-force mã 6 số (1 triệu khả năng) - vượt quá số lần cho phép thì mã bị vô hiệu
    // ngay cả khi chưa hết hạn, phải xin cấp mã mới.
    @Column(nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean used = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
