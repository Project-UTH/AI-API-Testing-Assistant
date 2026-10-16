package com.aiapitesting.backend.entity;

/**
 * Module 15h - nguồn gốc 1 test case AI sinh (15i: cả nhóm Security), xác định bằng cách hệ thống
 * CHẤM NỘI DUNG case (không dựa vào lời AI tự khai):
 * AI_CHECKLIST - AI sinh và nội dung thật sự đạt ít nhất 1 mục checklist;
 * AI_EXTRA - AI sinh nhưng không ứng với mục checklist nào (phần AI đề xuất thêm);
 * SYSTEM_FILLED - hệ thống tự tạo bù cho mục checklist AI làm thiếu/sai.
 * null = case MANUAL hoặc dữ liệu tạo trước Module 15h (case Security tạo trước 15i).
 */
public enum TestCaseGenerationOrigin {
    AI_CHECKLIST,
    AI_EXTRA,
    SYSTEM_FILLED
}
