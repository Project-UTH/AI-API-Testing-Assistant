package com.aiapitesting.backend.dto.response;

/**
 * 1 dòng trong Coverage Matrix (Module 15c) - tính tất định từ {@code Endpoint.schema} đối chiếu
 * với {@code TestCase} hiện có, KHÔNG dùng AI/rationale để suy ra trạng thái.
 *
 * @param category nhóm chiều (vd "REQUIRED_FIELD", "BOUNDARY", "STATUS_CODE", "AUTH")
 * @param label    nhãn hiển thị tiếng Việt, đủ cụ thể để người dùng biết thiếu gì (vd "Trường bắt buộc: email")
 * @param status   COVERED/MISSING/NOT_APPLICABLE
 * @param detail   giải thích thêm, bắt buộc có giá trị khi status = NOT_APPLICABLE (lý do không áp dụng)
 */
public record CoverageDimensionResponse(String category, String label, CoverageDimensionStatus status, String detail) {
}
