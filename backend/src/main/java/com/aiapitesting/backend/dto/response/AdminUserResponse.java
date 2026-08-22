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
        long totalBugReports,
        /** Tổng token AI đã dùng HÔM NAY (giờ UTC) - dùng để đối chiếu với quota `ai.quota.daily-token-limit`. */
        long aiTokensToday,
        long aiCallsToday
) {
    public static AdminUserResponse from(
            User user, long totalProjects, long totalTestCases, long totalBugReports,
            long aiTokensToday, long aiCallsToday
    ) {
        return new AdminUserResponse(
                user.getId(), user.getEmail(), user.getRole(), user.isEnabled(), user.getCreatedAt(),
                totalProjects, totalTestCases, totalBugReports, aiTokensToday, aiCallsToday);
    }
}
