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
