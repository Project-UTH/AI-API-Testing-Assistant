package com.aiapitesting.backend.dto.request;

/**
 * Ghi đè quota AI/ngày (token) riêng cho 1 user (Module 11) - tách khỏi AdminUserStatusRequest vì
 * khác hẳn nghiệp vụ (khoá tài khoản vs. chỉnh mức dùng AI). dailyTokenLimit null = xoá ghi đè,
 * quay lại dùng mặc định hệ thống (`ai.quota.daily-token-limit`).
 */
public record AdminUserAiQuotaRequest(Integer dailyTokenLimit) {
}
