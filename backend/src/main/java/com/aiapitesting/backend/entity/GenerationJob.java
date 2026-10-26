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
 * Job sinh test chạy nền (xem GenerationJobService): 1 dòng cho mỗi lần người dùng bấm "Sinh". Nhật ký từng bước nằm ở {@link GenerationJobStep}.
 * Cột kiểu/trạng thái là VARCHAR (không enum MySQL) để thêm giá trị mới không cần migration. project/owner lưu dạng UUID thường (không khoá ngoại)
 * để lịch sử job không cản việc xoá project; ProjectService.delete dọn job của project.
 */
@Entity
@Table(name = "generation_jobs", indexes = {
        @Index(name = "idx_generation_jobs_project_status", columnList = "project_id,status"),
        @Index(name = "idx_generation_jobs_owner_created", columnList = "owner_id,created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerationJob {

    /** FULL (AI sinh gốc + hệ thống bổ sung), SYSTEM (chỉ hệ thống, 0 token), LEGACY (nút Sinh Test Case cũ). */
    public static final String TYPE_FULL = "FULL";
    public static final String TYPE_SYSTEM = "SYSTEM";
    public static final String TYPE_LEGACY = "LEGACY";
    public static final String TYPE_RULES = "RULES";

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String CANCELLING = "CANCELLING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String NEEDS_HUMAN = "NEEDS_HUMAN";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String INTERRUPTED = "INTERRUPTED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private boolean dryRun;

    @Column(columnDefinition = "TEXT")
    private String paramsJson;

    @Column(nullable = false)
    private boolean cancelRequested;

    /** Trần token AI cho cả job (0 = dùng mặc định của hệ thống); tokensUsed cộng dồn từ từng lần gọi AI. */
    @Column(nullable = false)
    private int tokenCap;

    @Column(nullable = false)
    private int tokensUsed;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** JSON kết quả từng endpoint [{endpointId,label,status,reason,message,tokens,filled}]. */
    @Column(columnDefinition = "TEXT")
    private String resultJson;

    /** JSON {@code RulesJobResult} của pha trích checklist nghiệp vụ chạy trong job FULL/SYSTEM (job RULES cũ vẫn để ở resultJson). */
    @Column(columnDefinition = "TEXT")
    private String rulesResultJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Instant startedAt;

    private Instant finishedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isActive() {
        return QUEUED.equals(status) || RUNNING.equals(status) || CANCELLING.equals(status);
    }
}
