package com.aiapitesting.backend.entity;

/** 17q G3 - lý do loại luật (bắt buộc khi loại; luật loại vẫn giữ để đo precision trích luật). */
public enum RuleRejectReason {
    /** Hiểu sai nghĩa nghiệp vụ. */
    WRONG_MEANING,
    /** Trùng 1 luật khác. */
    DUPLICATE,
    /** Không áp dụng cho endpoint này. */
    NOT_APPLICABLE,
    /** Hệ thống tự loại: trùng ràng buộc đã khai trong spec (checklist EP/BVA đã kiểm). */
    DUPLICATE_OF_SPEC
}
