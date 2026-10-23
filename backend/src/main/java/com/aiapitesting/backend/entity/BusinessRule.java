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
 * 17q G3 - 1 luật nghiệp vụ của endpoint ("luật trước, test sau"): thuộc 1 mẫu {@link RuleTemplate}, tham số ở paramsJson; người
 * duyệt chịu trách nhiệm kỳ vọng (human oracle) rồi hệ thống sinh test TẤT ĐỊNH từ mẫu.
 *
 * Gắn endpoint theo {@code endpointKey} ("POST /api/jobs") vì import REPLACE xoá endpoint - endpointId chỉ là UUID thường (không FK),
 * nối lại sau mỗi lần import. Luật bị loại KHÔNG xoá (đo precision trích luật). fingerprint (endpointKey + mẫu + tham số chuẩn hoá) là
 * duy nhất theo project: chạy lại phát hiện tự động không tạo trùng, luật đã loại không bị đề xuất lại.
 */
@Entity
@Table(name = "business_rules",
        indexes = {
                @Index(name = "idx_business_rules_project_status", columnList = "project_id, status"),
                @Index(name = "idx_business_rules_project_endpoint", columnList = "project_id, endpoint_key")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_business_rules_fingerprint", columnNames = {"project_id", "fingerprint"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusinessRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "endpoint_key", nullable = false, length = 600)
    private String endpointKey;

    /** Endpoint hiện khớp endpointKey; null khi endpoint đã bị gỡ khỏi spec (luật STALE). */
    @Column(name = "endpoint_id")
    private UUID endpointId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(30)")
    private RuleTemplate template;

    @Column(name = "params_json", nullable = false, columnDefinition = "TEXT")
    private String paramsJson;

    /** Mã lỗi cụ thể người duyệt/tài liệu chỉ định; null = "bị từ chối" (4xx nghiệp vụ bất kỳ). */
    private Integer expectedStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20)")
    @Builder.Default
    private RuleStatus status = RuleStatus.PROPOSED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20)")
    private RuleSource source;

    @Column(columnDefinition = "TEXT")
    private String rationale;

    /** G3.3 - đoạn trích nguyên văn trong tài liệu/chỉ dẫn (đã kiểm chứng là chuỗi con); null với USER/SYSTEM_DETECTED. */
    @Column(columnDefinition = "TEXT")
    private String quote;

    private Integer quoteOffset;

    private UUID documentId;

    private UUID extractionJobId;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(30)")
    private RuleRejectReason rejectReason;

    /** Lý do STALE/CONFLICT, ghi chú khi loại, hoặc ghi chú bắt buộc khi duyệt luật CONFLICT. */
    @Column(length = 500)
    private String statusNote;

    private UUID reviewedBy;

    private Instant reviewedAt;

    /** sha256 của Endpoint.schema lúc kiểm luật lần cuối - spec đổi thì kiểm lại STALE/CONFLICT. */
    @Column(length = 64)
    private String specHash;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
