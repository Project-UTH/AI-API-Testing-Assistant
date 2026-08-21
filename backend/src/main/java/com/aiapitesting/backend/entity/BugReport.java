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
 * Bug report theo quy trình QA chuẩn (Module 10) - tạo từ 1 TestResult có status FAILED
 * (sourceTestResult), không phải từ cả TestExecution (1 execution có thể gồm nhiều test case,
 * nhưng bug report cần đúng cấp độ chi tiết "kỳ vọng ↔ thực tế của 1 lần chạy cụ thể").
 * project/endpoint denormalized từ testCase để ownership check và group-theo-endpoint không phải
 * luôn nhảy qua testCase.getEndpoint().getProject().
 */
@Entity
@Table(name = "bug_reports", uniqueConstraints = @UniqueConstraint(
        name = "uk_bug_reports_project_seq", columnNames = {"project_id", "seq_in_project"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BugReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private Endpoint endpoint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_case_id", nullable = false)
    private TestCase testCase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_test_result_id", nullable = false)
    private TestResult sourceTestResult;

    /**
     * Mã hiển thị dạng B{projectSeq}_{seqInProject} (VD "B1_001") - xem BugReportService.create().
     * CHỈ để hiển thị (tên file export, nhãn UI) - KHÔNG dùng làm khoá tra cứu ở đâu (mọi query thật
     * đều qua id UUID hoặc sourceTestResultId). KHÔNG unique toàn cục trong DB - projectSeq chỉ đảm
     * bảo duy nhất TRONG PHẠM VI 1 owner (findMaxBugReportProjectSeqByOwner), nên 2 user khác nhau
     * hoàn toàn có thể cùng ra "B1_001" cho bug đầu tiên của họ. Uniqueness THẬT SỰ cần (không trùng
     * số trong cùng 1 project) đã có sẵn qua unique constraint (project_id, seq_in_project) ở dưới -
     * đủ để tránh double-submit, không cần global unique ở cột này.
     */
    @Column(name = "bug_id", nullable = false)
    private String bugId;

    @Column(name = "project_seq", nullable = false)
    private int projectSeq;

    @Column(name = "seq_in_project", nullable = false)
    private int seqInProject;

    // columnDefinition = VARCHAR (không để Hibernate tự suy MySQL ENUM) - theo đúng convention đã
    // hình thành trong dự án (xem TestCase.source/authOverride) để tránh lớp lỗi "Data truncated"
    // nếu sau này có thêm giá trị enum mới trên DB đã có dữ liệu thật. Bảng này mới hoàn toàn nên
    // rủi ro hiện tại = 0, áp dụng để nhất quán code-style và phòng trước cho tương lai.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20)")
    @Builder.Default
    private BugStatus status = BugStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20)")
    @Builder.Default
    private BugSeverity severity = BugSeverity.MINOR;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20)")
    @Builder.Default
    private BugPriority priority = BugPriority.TRIVIAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20)")
    @Builder.Default
    private BugFrequency frequency = BugFrequency.SELDOM;

    @Column(nullable = false)
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String testEnvironment;

    @Column(columnDefinition = "TEXT")
    private String stepsToReproduce;

    @Column(columnDefinition = "TEXT")
    private String actualResult;

    @Column(columnDefinition = "TEXT")
    private String expectedResult;

    /** Chỉ text URL người dùng tự dán - không upload file thật (đã chốt phạm vi). */
    @Column(columnDefinition = "TEXT")
    private String attachmentUrl;

    /** Mặc định = tên Project lúc tạo (dự án chưa có khái niệm build/release riêng), sửa tay được sau. */
    private String build;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    /**
     * Cờ "đang gợi ý đóng, chờ người dùng xác nhận" (BugReportStatusService, khi có lần chạy mới
     * PASSED trong lúc status đang PENDING/POSTED/REOPENED) - KHÔNG tự đổi status ngầm, chỉ đặt cờ
     * này để frontend hiện banner xác nhận riêng.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean pendingCloseSuggestion = false;

    /** Nhật ký ghi chú - vừa do người dùng tự gõ, vừa tự động nối thêm dòng khi hệ thống đổi status. */
    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
