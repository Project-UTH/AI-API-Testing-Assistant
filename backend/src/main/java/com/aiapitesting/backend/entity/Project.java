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
    @Column(name = "target_auth_type", nullable = false, columnDefinition = "VARCHAR(20)")
    @Builder.Default
    private TargetAuthType targetAuthType = TargetAuthType.NONE;

    @Column(name = "target_auth_value_encrypted", columnDefinition = "TEXT")
    private String targetAuthValueEncrypted;

    /**
     * Base URL của API thật sẽ gọi lúc thực thi - khác URL nhập lúc import (vị trí tài liệu
     * OpenAPI). Suy ra từ openApi.getServers() nếu có, hoặc người dùng tự nhập.
     */
    @Column(name = "target_base_url")
    private String targetBaseUrl;

    /**
     * Quy ước của API đích về mã trả khi THIẾU/SAI token: null = 401 (chuẩn). Nhiều API (vd Spring Security mặc định) trả 403. Người dùng khai rõ
     * để test Security 'thiếu token/token sai' không bị Fail giả chỉ vì khác quy ước; chỉ áp dụng cho test cố ý bỏ/làm sai token (authOverride NONE/INVALID).
     */
    @Column(name = "unauthenticated_status")
    private Integer unauthenticatedStatus;

    /**
     * Module 16 - thời điểm token đích được đặt/đổi/xoá gần nhất. CHỈ lưu thời điểm (không lưu/hash token - quy tắc
     * bảo mật CLAUDE.md), dùng làm bằng chứng "token đổi sau lần Pass gần nhất" khi nghi token hết hạn.
     */
    @Column(name = "target_auth_updated_at")
    private Instant targetAuthUpdatedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Số thứ tự Project dùng để sinh bugId dạng "B{n}_{seq}" - đánh trong phạm vi owner hiện tại
     * (không phải toàn hệ thống). Null = project chưa có bug report nào.
     */
    @Column(name = "bug_report_project_seq")
    private Integer bugReportProjectSeq;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
