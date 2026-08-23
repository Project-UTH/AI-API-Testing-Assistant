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
 * Sự kiện lịch sử "tạo/xoá Bug Report" - 1 dòng cho mỗi lần BugReportService.create()/delete()
 * thành công, cùng pattern {@link TestGenerationEvent}. bugId/summary lưu snapshot chuỗi, không
 * phải FK tới BugReport vì bug có thể đã bị xoá.
 *
 * bugReportId cố ý là cột UUID trần (không @ManyToOne/@JoinColumn) - nếu là FK thật, xoá bug report
 * sau này sẽ bị MySQL chặn vì event cũ còn trỏ tới. Để trần: dùng deep-link mở dialog sửa bug khi
 * bug còn tồn tại, frontend tự xử lý "không tìm thấy" khi bug đã bị xoá.
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
