package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.AuthProfile;
import com.aiapitesting.backend.entity.TargetAuthType;

import java.time.Instant;
import java.util.UUID;

/** KHÔNG BAO GIỜ chứa giá trị token (kể cả dạng che) - chỉ cho biết đã có giá trị hay chưa. */
public record AuthProfileResponse(
        UUID id, String name, String roleKey, TargetAuthType authType, boolean hasValue,
        Instant authUpdatedAt, Instant createdAt, long testCaseCount,
        boolean loginMode, String loginPath, String tokenJsonPath
) {
    public static AuthProfileResponse from(AuthProfile profile, long testCaseCount) {
        return new AuthProfileResponse(
                profile.getId(), profile.getName(), profile.getRoleKey(), profile.getAuthType(),
                profile.isLoginMode()
                        ? profile.getLoginBodyEncrypted() != null && !profile.getLoginBodyEncrypted().isBlank()
                        : profile.getAuthValueEncrypted() != null && !profile.getAuthValueEncrypted().isBlank(),
                profile.getAuthUpdatedAt(), profile.getCreatedAt(), testCaseCount,
                profile.isLoginMode(), profile.getLoginPath(), profile.getTokenJsonPath());
    }
}
