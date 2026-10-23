package com.aiapitesting.backend.entity;

/**
 * 17q G3 - trạng thái luật nghiệp vụ. Trạng thái "chờ duyệt" nằm trên TỪNG LUẬT (không trên job) để huỷ job/khởi động lại server
 * không làm mất nghĩa. Luật bị loại KHÔNG xoá (cần để đo precision trích luật).
 */
public enum RuleStatus {
    /** Đề xuất (hệ thống/AI) hoặc vừa bị sửa - chờ người duyệt; không sinh test. */
    PROPOSED,
    /** Người đã duyệt - kỳ vọng do người chịu trách nhiệm (human oracle); được sinh test. */
    APPROVED,
    /** Người loại (kèm lý do) hoặc hệ thống loại vì trùng ràng buộc spec. */
    REJECTED,
    /** Field/endpoint của luật không còn trong spec sau khi import lại. */
    STALE,
    /** Luật mâu thuẫn spec (vd tài liệu ghi tối đa 100, spec ghi 50) - là 1 phát hiện "spec lệch tài liệu"; duyệt phải kèm ghi chú. */
    CONFLICT
}
