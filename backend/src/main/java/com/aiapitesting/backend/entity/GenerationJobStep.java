package com.aiapitesting.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 1 bước trong nhật ký của {@link GenerationJob} (chỉ-ghi-thêm): chèn khi bắt đầu (RUNNING), cập nhật đúng 1 lần khi kết thúc. Người dùng xem
 * các bước này để giám sát việc HỆ THỐNG và AI đang làm gì. Không bao giờ chứa dữ liệu nhạy cảm: nội dung phản hồi chỉ ở dạng đã che.
 */
@Entity
@Table(name = "generation_job_steps",
        indexes = @Index(name = "idx_generation_job_steps_job", columnList = "job_id,seq"),
        uniqueConstraints = @UniqueConstraint(name = "uk_generation_job_steps_job_seq", columnNames = {"job_id", "seq"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerationJobStep {

    public static final String ACTOR_SYSTEM = "SYSTEM";
    public static final String ACTOR_AI = "AI";

    public static final String RUNNING = "RUNNING";
    public static final String OK = "OK";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(nullable = false)
    private int seq;

    @Column(name = "endpoint_id")
    private UUID endpointId;

    /** "POST /api/x" - ghi sẵn để hiển thị không cần join. */
    @Column(length = 200)
    private String endpointLabel;

    /** PRECHECK, AI_BASELINE, RUN, DIAGNOSE, LOCK, FILL, AI_GAPS, SUMMARY... */
    @Column(nullable = false, length = 20)
    private String phase;

    @Column(nullable = false, length = 10)
    private String actor;

    @Column(nullable = false, length = 10)
    private String status;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String detail;

    private Integer httpStatus;

    private Integer tokens;

    private Instant startedAt;

    private Instant finishedAt;
}
