package com.aiapitesting.backend.dto.request;

/** requiresAuth = null: bỏ khai báo thủ công, quay về giá trị suy từ spec. */
public record SetRequiresAuthRequest(Boolean requiresAuth) {
}
