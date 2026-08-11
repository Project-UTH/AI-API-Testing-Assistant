package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id, String name, String description, Instant createdAt,
        String targetBaseUrl,
        /** Chỉ loại xác thực - không bao giờ trả giá trị thật (encrypted) ra ngoài. */
        TargetAuthType targetAuthType
) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getCreatedAt(),
                project.getTargetBaseUrl(),
                project.getTargetAuthType()
        );
    }
}
