package com.aiapitesting.backend.entity;

/** 17q G3 - nguồn của luật nghiệp vụ. G3.1 dùng USER + SYSTEM_DETECTED; DOC/INSTRUCTION/INFERRED dành cho AI trích luật (G3.3). */
public enum RuleSource {
    /** Người dùng tự nhập. */
    USER,
    /** Hệ thống tự phát hiện từ spec (vd cặp xxxMin/xxxMax), 0 token. */
    SYSTEM_DETECTED,
    /** AI trích từ tài liệu nghiệp vụ, có đoạn trích nguyên văn đã kiểm chứng. */
    DOC,
    /** AI trích từ ô chỉ dẫn của người dùng, có đoạn trích nguyên văn đã kiểm chứng. */
    INSTRUCTION,
    /** AI suy đoán (không có trích dẫn hợp lệ) - GIẢ ĐỊNH. */
    INFERRED
}
