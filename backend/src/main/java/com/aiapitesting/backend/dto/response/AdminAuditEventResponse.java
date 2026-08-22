package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.AdminAuditAction;
import com.aiapitesting.backend.entity.AdminAuditEvent;

import java.time.Instant;
import java.util.UUID;

public record AdminAuditEventResponse(
        UUID id, String adminEmail, String targetEmail, AdminAuditAction action, String detail, Instant createdAt
) {
    public static AdminAuditEventResponse from(AdminAuditEvent event) {
        return new AdminAuditEventResponse(
                event.getId(), event.getAdminEmail(), event.getTargetEmail(), event.getAction(),
                event.getDetail(), event.getCreatedAt());
    }
}
