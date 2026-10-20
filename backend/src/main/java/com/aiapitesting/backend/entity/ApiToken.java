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
 * Module 17 (17d) - token API cho CI/CD: thuộc ĐÚNG 1 project, chỉ gọi được các endpoint {@code /api/v1/ci/**} (không phải JWT đăng
 * nhập). Chỉ lưu HASH SHA-256 của token (token thô 256-bit ngẫu nhiên, hiển thị đúng 1 lần lúc tạo) - mất token thì thu hồi rồi tạo
 * mới, không có cách xem lại.
 */
@Entity
@Table(name = "api_tokens", indexes = @Index(name = "idx_api_tokens_project", columnList = "project_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 100)
    private String name;

    /** Vài ký tự đầu của token để nhận diện trong danh sách (không phải bí mật). */
    @Column(nullable = false, length = 16)
    private String prefix;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column
    private Instant expiresAt;

    @Column
    private Instant revokedAt;

    @Column
    private Instant lastUsedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
