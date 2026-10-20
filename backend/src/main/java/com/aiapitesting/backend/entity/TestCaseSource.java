package com.aiapitesting.backend.entity;

public enum TestCaseSource {
    AI_GENERATED,
    MANUAL,
    SECURITY,
    /** Đánh giá 2026-10-20 - test NGHIỆP VỤ do AI đoán từ tên field (spec không khai luật): gắn nguồn riêng để sinh lại/xoá độc lập và không tính vào Coverage EP/BVA. */
    BUSINESS
}
