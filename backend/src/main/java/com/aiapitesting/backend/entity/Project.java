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
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_auth_type", nullable = false)
    @Builder.Default
    private TargetAuthType targetAuthType = TargetAuthType.NONE;

    @Column(name = "target_auth_value_encrypted", columnDefinition = "TEXT")
    private String targetAuthValueEncrypted;

    /**
     * Base URL của API thật sẽ gọi lúc thực thi test (Module 6) - khác hoàn toàn với URL người
     * dùng nhập lúc import (đó là vị trí tài liệu OpenAPI, có thể khác domain). Suy ra từ
     * openApi.getServers() lúc import nếu có, hoặc người dùng tự nhập tay.
     */
    @Column(name = "target_base_url")
    private String targetBaseUrl;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
