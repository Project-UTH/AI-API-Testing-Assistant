package com.aiapitesting.backend.dto.response;

/** Mức rủi ro suy ra tất định từ {@code riskScore} (0-100) ở Risk Engine (Module 15c), không do AI quyết định. */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
