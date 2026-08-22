package com.aiapitesting.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * Nhật ký hành động nhạy cảm của admin (Module 11 - khoá/mở tài khoản, tương lai có thể thêm loại
 * khác). Cố ý lưu SNAPSHOT email (không phải @ManyToOne tới User) - giống lý do đã ghi ở
 * BugReportEvent.bugReportId: nếu là FK thật, xoá tài khoản admin hoặc tài khoản bị tác động sau
 * này sẽ va khoá ngoại hoặc mất luôn dấu vết trong nhật ký (đúng thứ audit log không được phép mất
 * dù tài khoản liên quan không còn tồn tại).
 */
@Entity
@Table(name = "admin_audit_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String adminEmail;

    @Column(nullable = false)
    private String targetEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(30)")
    private AdminAuditAction action;

    /** Chi tiết bổ sung tuỳ hành động (VD "Đặt giới hạn: 5000 token/ngày") - null với hành động tự giải thích đủ qua action (khoá/mở). */
    private String detail;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
