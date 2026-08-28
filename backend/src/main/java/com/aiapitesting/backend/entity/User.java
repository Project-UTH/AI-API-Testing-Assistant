package com.aiapitesting.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // VARCHAR không phải SQL ENUM - tránh ddl-auto=update không tự nới cột ENUM khi Java enum thêm
    // hằng số mới. VARCHAR không có default ngầm hợp lệ (implicit default là ''), nên phải khai rõ
    // "DEFAULT 'USER'", nếu không user có sẵn sẽ nhận role rỗng và vỡ @Enumerated(STRING) khi đọc lại.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20) DEFAULT 'USER'")
    @Builder.Default
    private UserRole role = UserRole.USER;

    // Khoá tài khoản (admin chặn user vi phạm) - khác role, không liên quan phân quyền. Cùng lý do
    // trên: DEFAULT 1 bắt buộc khai rõ, nếu không user có sẵn sẽ bị khoá ngầm sau khi ALTER cột.
    @Column(nullable = false, columnDefinition = "TINYINT(1) DEFAULT 1")
    @Builder.Default
    private boolean enabled = true;

    // Ghi đè quota AI/ngày riêng cho user này - null = dùng mặc định hệ thống. Nullable nên không
    // cần columnDefinition DEFAULT như 2 cột trên.
    private Integer aiDailyTokenLimitOverride;

    // Nguồn tạo tài khoản (Module 13, Google login) - chỉ dùng để biết tài khoản Google-only chưa
    // từng có mật khẩu thật (xem passwordSet); KHÔNG dùng field này để rẽ nhánh logic đăng nhập,
    // login() vẫn chạy chung 1 đường cho mọi provider.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20) DEFAULT 'LOCAL'")
    @Builder.Default
    private AuthProvider authProvider = AuthProvider.LOCAL;

    // sub claim cua Google ID token - dung de tra cuu nhanh, khong doi theo thoi gian (khac email
    // co the doi). Nullable + unique: MySQL cho phep nhieu dong NULL nen khong anh huong user cu.
    @Column(unique = true)
    private String googleId;

    // false CHỈ với tài khoản tạo mới qua Google (password lúc đó là UUID ngẫu nhiên không ai biết)
    // - AuthService.changePassword() dựa vào field này để bỏ qua bước bắt nhập mật khẩu cũ. Mọi tài
    // khoản LOCAL (kể cả sau khi tự động liên kết Google) giữ nguyên true vì đã có mật khẩu thật.
    @Column(nullable = false, columnDefinition = "TINYINT(1) DEFAULT 1")
    @Builder.Default
    private boolean passwordSet = true;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
