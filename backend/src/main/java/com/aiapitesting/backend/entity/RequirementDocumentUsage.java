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
 * Nhật ký "tài liệu X đã được gửi cho AI để lập checklist của endpoint Y trong job Z". Để người dùng biết mỗi tài liệu đã dùng cho endpoint nào.
 * Gắn endpoint theo endpointKey (METHOD path) như BusinessRule vì import REPLACE xoá endpoint; documentId là UUID thường (tài liệu xoá/thay bản vẫn
 * giữ dòng để tra cứu lịch sử, nội dung tài liệu KHÔNG nằm ở đây).
 */
@Entity
@Table(name = "requirement_document_usages", indexes = {
        @Index(name = "idx_doc_usage_project_doc", columnList = "project_id, document_id"),
        @Index(name = "idx_doc_usage_project_endpoint", columnList = "project_id, endpoint_key")
}, uniqueConstraints = @UniqueConstraint(name = "uk_doc_usage_job_doc_endpoint", columnNames = {"job_id", "document_id", "endpoint_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RequirementDocumentUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "endpoint_key", nullable = false, length = 600)
    private String endpointKey;

    @Column(name = "job_id")
    private UUID jobId;

    /** Bản chụp tên/sha lúc dùng: tài liệu bị xoá hoặc thay bản (đổi id) vẫn đọc được lịch sử. */
    @Column(length = 200)
    private String docFileName;

    @Column(length = 64)
    private String docSha256;

    @Column(nullable = false)
    private Instant usedAt;

    @PrePersist
    protected void onCreate() {
        if (usedAt == null) {
            usedAt = Instant.now();
        }
    }
}
