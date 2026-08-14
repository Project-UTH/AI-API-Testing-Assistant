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
 * Sự kiện lịch sử "sinh test case" (Module 8) - 1 dòng cho mỗi lần gọi
 * TestCaseGenerationService.generate() thành công. snapshotJson lưu lại đúng nội dung AI sinh ra
 * TẠI THỜI ĐIỂM ĐÓ (JSON của List<GeneratedTestCase>), tách biệt hoàn toàn với bảng test_cases sống
 * - vì regenerate sẽ xoá hẳn bộ TestCase AI_GENERATED cũ, nếu không snapshot riêng thì xem lại lịch
 * sử cũ sẽ mất hoặc sai dữ liệu.
 */
@Entity
@Table(name = "test_generation_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestGenerationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private Endpoint endpoint;

    @Column(name = "test_case_count", nullable = false)
    private int testCaseCount;

    @Column(name = "snapshot_json", columnDefinition = "TEXT", nullable = false)
    private String snapshotJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
