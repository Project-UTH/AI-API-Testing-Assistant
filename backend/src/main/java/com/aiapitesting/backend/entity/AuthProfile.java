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
 * Module 17 (3.1) - 1 "profile xác thực" (vai trò) của target API trong 1 project: HR, Candidate, Admin... Project.targetAuth*
 * vẫn là profile MẶC ĐỊNH (không sao chép sang bảng này - 1 nguồn sự thật); AuthProfile chỉ chứa các vai trò BỔ SUNG, và
 * test case chọn dùng bằng {@code TestCase.authProfileId}. Token mã hoá AES-256 giống Project.targetAuthValueEncrypted,
 * chỉ giải mã đúng lúc gọi target, KHÔNG BAO GIỜ trả ra qua API hay ghi log.
 */
@Entity
@Table(name = "auth_profiles", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "role_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Tên hiển thị, đổi được tự do. */
    @Column(nullable = false, length = 100)
    private String name;

    /** Khoá vai trò chuẩn hoá (HR, CANDIDATE...), duy nhất trong project, BẤT BIẾN sau khi tạo - các mục ma trận phân quyền dựa vào đây. */
    @Column(name = "role_key", nullable = false, length = 40)
    private String roleKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20)")
    private TargetAuthType authType;

    @Column(columnDefinition = "TEXT")
    private String authValueEncrypted;

    @Column
    private Instant authUpdatedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
