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
@Table(name = "test_results", uniqueConstraints = @UniqueConstraint(
        name = "uk_test_results_execution_test_case", columnNames = {"execution_id", "test_case_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_case_id", nullable = false)
    private TestCase testCase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false)
    private TestExecution execution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TestResultStatus status;

    private Integer responseStatus;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * JSON list các AssertionResultResponse (Module 9b) - chỉ có giá trị khi thật sự gọi được
     * target API (có response để chấm assertion); null cho case ERROR/BLOCKED hoặc test case
     * không có assertion nào.
     */
    @Column(columnDefinition = "TEXT")
    private String assertionResultsJson;

    /** Module 16 - thời gian gọi target API (ms); null cho case không gọi target (ERROR cấu hình, BLOCKED) và dữ liệu cũ. */
    private Long durationMs;

    /** Module 16 - loại lỗi hạ tầng khi gọi target (chỉ ERROR do gọi target thất bại); null với mọi trường hợp khác. */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)")
    private ErrorKind errorKind;

    /**
     * Module 16 - "dấu vân tay" phiên bản test lúc chạy (hash nội dung gửi đi + kỳ vọng + assertion + liên kết dữ
     * liệu). Chỉ so lịch sử giữa các lần chạy CÙNG fingerprint - sửa test case giữa 2 lần chạy thì đó là 2 phiên
     * bản khác nhau, không phải "không ổn định". Null với dữ liệu cũ (không đưa vào phân tích theo lịch sử).
     */
    @Column(length = 64)
    private String testCaseFingerprint;

    /** Module 16 - nguyên nhân nghi ngờ của kết quả FAILED/ERROR, chấm lúc chạy (xem FailureCause). Null = PASSED/BLOCKED/dữ liệu cũ. */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(30)")
    private FailureCause failureCause;

    /** Module 16 - JSON array các câu bằng chứng cho {@link #failureCause}. */
    @Column(columnDefinition = "TEXT")
    private String failureEvidence;

    /** True nếu test case này không nằm trong lựa chọn ban đầu, được kéo theo qua Test Data Chaining (Module 7). */
    @Column(nullable = false)
    @Builder.Default
    private boolean autoIncluded = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
