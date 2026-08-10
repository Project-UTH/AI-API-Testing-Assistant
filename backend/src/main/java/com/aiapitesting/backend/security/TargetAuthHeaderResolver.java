package com.aiapitesting.backend.security;

import com.aiapitesting.backend.entity.TargetAuthType;
import org.springframework.stereotype.Component;

/**
 * Map TargetAuthType sang header HTTP thật sự gửi cho target API - dùng chung giữa
 * EndpointImportService (tải tài liệu OpenAPI bị chặn login) và engine thực thi test (Module 6).
 */
@Component
public class TargetAuthHeaderResolver {

    public record AuthHeader(String name, String value) {
    }

    public AuthHeader resolve(TargetAuthType authType, String rawValue) {
        if (authType == null || authType == TargetAuthType.NONE) {
            return null;
        }
        return switch (authType) {
            case BEARER_TOKEN -> new AuthHeader("Authorization", "Bearer " + rawValue);
            case API_KEY -> new AuthHeader("X-API-Key", rawValue);
            case NONE -> null;
        };
    }
}
