package com.aiapitesting.backend.dto.request;

import com.aiapitesting.backend.entity.TargetAuthType;

public record TargetAuthUpdateRequest(
        TargetAuthType authType,
        String authValue
) {
}
