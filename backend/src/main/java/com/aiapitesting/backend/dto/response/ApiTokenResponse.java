package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.ApiToken;

import java.time.Instant;
import java.util.UUID;

/** KHÔNG bao giờ chứa token (kể cả băm) - chỉ metadata để quản lý. */
public record ApiTokenResponse(
        UUID id, String name, String prefix, Instant createdAt, Instant expiresAt, Instant lastUsedAt, Instant revokedAt, String status
) {
    public static ApiTokenResponse from(ApiToken token, Instant now) {
        String status = token.getRevokedAt() != null ? "REVOKED"
                : token.getExpiresAt() != null && !token.getExpiresAt().isAfter(now) ? "EXPIRED" : "ACTIVE";
        return new ApiTokenResponse(token.getId(), token.getName(), token.getPrefix(), token.getCreatedAt(), token.getExpiresAt(),
                token.getLastUsedAt(), token.getRevokedAt(), status);
    }
}
