package com.aiapitesting.backend.dto.response;

/**
 * Module 15h - kết quả của 1 mục checklist sau 1 lần sinh:
 * ALREADY_COVERED - đã có case khoá/tự thêm từ trước đạt mục này, không giao AI nữa;
 * AI_SATISFIED - AI sinh case đạt mục này (hệ thống chấm theo nội dung);
 * SYSTEM_FILLED - AI làm thiếu/sai, hệ thống tự tạo case bù;
 * NOT_FILLABLE - AI làm thiếu và hệ thống không bù được (vd không có case hợp lệ làm gốc);
 * NOT_APPLICABLE - không áp dụng (vd requestBody không phải JSON).
 */
public enum ChecklistItemStatus {
    ALREADY_COVERED,
    AI_SATISFIED,
    SYSTEM_FILLED,
    NOT_FILLABLE,
    NOT_APPLICABLE
}
