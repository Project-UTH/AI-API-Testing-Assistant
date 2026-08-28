package com.aiapitesting.backend.dto.request;

import jakarta.validation.constraints.NotBlank;

public record GoogleAuthRequest(
        @NotBlank String idToken
) {
}
