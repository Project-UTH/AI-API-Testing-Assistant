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
 * Cạnh phụ thuộc giữa 2 TestCase (Test Data Chaining, Module 7) - testCase (consumer) lấy giá trị
 * thật từ response của dependsOnTestCase (nguồn) theo jsonPath, gán vào token {{placeholderName}}
 * trong resolvedPath/requestBody/requestHeaders của chính testCase.
 */
@Entity
@Table(name = "test_case_dependencies", uniqueConstraints = @UniqueConstraint(
        name = "uk_test_case_dependencies_test_case_placeholder", columnNames = {"test_case_id", "placeholder_name"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestCaseDependency {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_case_id", nullable = false)
    private TestCase testCase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "depends_on_test_case_id", nullable = false)
    private TestCase dependsOnTestCase;

    @Column(name = "json_path", nullable = false)
    private String jsonPath;

    @Column(name = "placeholder_name", nullable = false)
    private String placeholderName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
