package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * 1 dòng trong Coverage Matrix (Module 15c) - tính tất định từ {@code Endpoint.schema} đối chiếu
 * với {@code TestCase} hiện có, KHÔNG dùng AI/rationale để suy ra trạng thái.
 *
 * @param category         nhóm chiều (vd "REQUIRED_FIELD", "BOUNDARY", "STATUS_CODE", "AUTH")
 * @param label            nhãn hiển thị tiếng Việt, đủ cụ thể để người dùng biết thiếu gì (vd "Trường bắt buộc: email")
 * @param status           COVERED/MISSING/NOT_APPLICABLE
 * @param detail           giải thích thêm, bắt buộc có giá trị khi status = NOT_APPLICABLE (lý do không áp dụng)
 * @param coveringTestCases Module 15f - (các) test case CHÍNH XÁC khiến dimension này COVERED, rỗng
 *                           khi MISSING/NOT_APPLICABLE - cho biết được bằng chứng cụ thể, không chỉ
 *                           "có hay không"
 */
public record CoverageDimensionResponse(
        String category, String label, CoverageDimensionStatus status, String detail,
        List<TestCaseRef> coveringTestCases
) {
    public record TestCaseRef(UUID id, String name) {
    }
}
