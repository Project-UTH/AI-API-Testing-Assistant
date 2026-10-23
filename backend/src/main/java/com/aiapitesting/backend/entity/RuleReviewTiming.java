package com.aiapitesting.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 17q G3.5 - thời gian người dùng bỏ ra để duyệt 1 luật (thực nghiệm E2). CHỈ ghi khi người dùng chủ động bật đo: thời gian có focus (đã trừ lúc
 * tab bị ẩn), quyết định, và đặc điểm của luật (nguồn, mẫu, có trích dẫn không). KHÔNG ghi thao tác chuột/bàn phím, không ghi nội dung luật.
 * Không có khoá ngoại: bản ghi đo sống độc lập với luật; xoá project thì xoá theo.
 */
@Entity
@Table(name = "rule_review_timings", indexes = @Index(name = "idx_rule_review_timing_project", columnList = "project_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleReviewTiming {

    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String EDITED = "EDITED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(nullable = false, length = 20)
    private String ruleSource;

    @Column(nullable = false, length = 30)
    private String template;

    /** Luật có đoạn trích để đối chiếu hay không: biến số chính của E2 (duyệt có trích dẫn nhanh hơn bao nhiêu). */
    @Column(nullable = false)
    private boolean hadQuote;

    @Column(nullable = false, length = 10)
    private String decision;

    @Column(nullable = false)
    private int focusedMs;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
