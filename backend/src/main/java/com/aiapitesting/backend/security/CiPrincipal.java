package com.aiapitesting.backend.security;

import java.util.UUID;

/** Danh tính của 1 token API CI sau khi xác thực: chỉ gồm id token và project duy nhất nó được phép đụng tới. */
public record CiPrincipal(UUID tokenId, UUID projectId) {
}
