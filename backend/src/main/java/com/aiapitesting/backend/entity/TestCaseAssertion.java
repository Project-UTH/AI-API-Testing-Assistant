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
 * Assertion kiểm tra 1 field cụ thể trong response (Module 9b) - do người dùng tự thêm hoặc AI tự
 * đề xuất kèm test case. TestExecutionRunner chấm điểm PASSED chỉ khi status code lẫn mọi assertion
 * của test case đều đúng.
 */
@Entity
@Table(name = "test_case_assertions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestCaseAssertion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_case_id", nullable = false)
    private TestCase testCase;

    @Column(name = "json_path", nullable = false)
    private String jsonPath;

    // columnDefinition VARCHAR thay vì để Hibernate suy ra MySQL ENUM(...) - tránh lặp lại bug
    // "Data truncated" đã gặp thật ở cột TestCase.source khi enum Java có thêm giá trị mới sau này.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20)")
    private AssertionOperator operator;

    /** Bắt buộc trừ khi operator = EXISTS (validate ở TestCaseService.saveAssertions()). */
    @Column(name = "expected_value", columnDefinition = "TEXT")
    private String expectedValue;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
