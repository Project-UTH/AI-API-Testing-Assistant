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

    // VARCHAR (không phải SQL ENUM) - cùng lý do đã rút ra ở TestCase.source/authOverride (tránh
    // ddl-auto=update không tự nới cột ENUM khi Java enum thêm hằng số mới). KHÁC BIỆT QUAN TRỌNG
    // với ENUM: cột ENUM có default ngầm là "giá trị khai báo đầu tiên" khi thêm cột mới vào bảng đã
    // có dữ liệu và sql_mode không bật strict (đã xác nhận thật ở Module 5) - còn VARCHAR không có
    // default ngầm hợp lệ nào (implicit default là '""'), nên PHẢI khai báo rõ "DEFAULT 'USER'" ở
    // columnDefinition, nếu không mọi user có sẵn trong DB sẽ nhận role rỗng, vỡ @Enumerated(STRING)
    // ngay khi đọc lại (IllegalArgumentException: No enum constant).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20) DEFAULT 'USER'")
    @Builder.Default
    private UserRole role = UserRole.USER;

    // Khoá tài khoản (admin dùng để chặn 1 user vi phạm) - KHÁC role, không liên quan phân quyền.
    // Cùng lý do trên: TINYINT(1) DEFAULT 1 bắt buộc phải khai rõ, nếu không mọi user có sẵn sẽ bị
    // khoá ngầm (default 0/false) ngay sau khi cột mới được ALTER vào bảng đã có dữ liệu thật.
    @Column(nullable = false, columnDefinition = "TINYINT(1) DEFAULT 1")
    @Builder.Default
    private boolean enabled = true;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
