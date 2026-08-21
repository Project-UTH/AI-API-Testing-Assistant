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
 * Sự kiện lịch sử "tạo/xoá Bug Report" (Module 8 - Lịch sử tổng) - 1 dòng cho mỗi lần
 * BugReportService.create()/delete() thành công, giống hệt pattern {@link TestGenerationEvent}.
 * Bắt buộc phải có bảng sự kiện RIÊNG (không suy ra từ bảng bug_reports sống): xoá bug report là
 * xoá hẳn dòng đó khỏi bug_reports, nếu không có snapshot riêng thì sự kiện "Xoá Bug Report" sẽ
 * không còn gì để đọc lại sau khi xoá xong. bugId/summary lưu dạng chuỗi snapshot TẠI THỜI ĐIỂM đó,
 * không phải FK tới BugReport (BugReport có thể đã không còn tồn tại).
 *
 * bugReportId CỐ Ý là cột UUID trần (không phải @ManyToOne/@JoinColumn) - nếu là FK thật, event
 * "CREATED" sẽ giữ 1 tham chiếu khoá ngoại tới đúng dòng bug_reports đó, và khi người dùng xoá bug
 * report sau này, MySQL sẽ chặn luôn thao tác xoá vì event cũ vẫn còn trỏ tới (khoá ngoại 1451) -
 * đúng lớp lỗi đã lặp lại nhiều lần trong dự án. Để trần: dùng để deep-link mở dialog sửa bug khi
 * bug CÒN tồn tại (sự kiện CREATED), frontend tự xử lý "không tìm thấy" khi bug đã bị xoá sau đó.
 */
@Entity
@Table(name = "bug_report_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BugReportEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private Endpoint endpoint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    @Column(name = "bug_id", nullable = false)
    private String bugId;

    @Column(name = "bug_report_id")
    private UUID bugReportId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, columnDefinition = "VARCHAR(20)")
    private BugReportEventType eventType;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    @PrePersist
    protected void onCreate() {
        this.occurredAt = Instant.now();
    }
}
