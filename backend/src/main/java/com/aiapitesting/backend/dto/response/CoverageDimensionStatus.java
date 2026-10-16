package com.aiapitesting.backend.dto.response;

/** Trạng thái 1 chiều (dimension) trong Coverage Matrix (Module 15c) - xem {@link CoverageDimensionResponse}. */
public enum CoverageDimensionStatus {
    COVERED,
    MISSING,
    /** Chiều này không áp dụng cho endpoint (vd endpoint không yêu cầu auth, hoặc 403 - hệ thống chưa hỗ trợ mô hình vai trò/scope). */
    NOT_APPLICABLE
}
