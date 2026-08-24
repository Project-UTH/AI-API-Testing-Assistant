package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.UserRole;

public record AuthResponse(String token, String email, UserRole role) {
}
