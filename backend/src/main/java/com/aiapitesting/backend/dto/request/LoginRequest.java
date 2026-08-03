package com.aiapitesting.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Email(message = "Email không đúng định dạng") String email,
        @NotBlank String password
) {
}
