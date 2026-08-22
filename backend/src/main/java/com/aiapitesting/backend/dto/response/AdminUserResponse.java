package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.entity.UserRole;

import java.time.Instant;
import java.util.UUID;

public record AdminUserResponse(
        UUID id,
        String email,
        UserRole role,
        boolean enabled,
        Instant createdAt,
        long totalProjects,
        long totalTestCases,
        long totalBugReports
) {
    public static AdminUserResponse from(User user, long totalProjects, long totalTestCases, long totalBugReports) {
        return new AdminUserResponse(
                user.getId(), user.getEmail(), user.getRole(), user.isEnabled(), user.getCreatedAt(),
                totalProjects, totalTestCases, totalBugReports);
    }
}
