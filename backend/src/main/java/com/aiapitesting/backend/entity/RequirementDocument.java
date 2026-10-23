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
 * 17q G3.3 - tài liệu nghiệp vụ (.md/.txt) người dùng tải lên để AI TRÍCH luật có trích dẫn. content đã chuẩn hoá (UTF-8 nghiêm ngặt, bỏ BOM,
 * CRLF→LF, NFC) và là mốc DUY NHẤT để kiểm chứng đoạn trích + tính quoteOffset. Xoá tài liệu: bỏ content, giữ dòng (hiện tên) - luật đã tạo
 * không đổi vì đoạn trích đã lưu riêng ở BusinessRule.quote. Nội dung tài liệu KHÔNG được ghi log.
 */
@Entity
@Table(name = "requirement_documents", indexes = @Index(name = "idx_requirement_documents_project", columnList = "project_id, active"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RequirementDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 200)
    private String fileName;

    @Column(nullable = false)
    private long sizeBytes;

    /** sha256 của bản ĐÃ chuẩn hoá (không phải byte thô). */
    @Column(nullable = false, length = 64)
    private String sha256;

    /** Null khi tài liệu đã bị xoá. */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;

    private UUID uploadedBy;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
