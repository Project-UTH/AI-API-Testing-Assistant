package com.aiapitesting.backend.dto.response;

import java.util.List;

/**
 * Module 15j - kết quả bù khoảng trống coverage: case đã tạo + mục không bù được kèm lý do (không im lặng bỏ qua).
 *
 * @param baselineTestCaseName case gốc hợp lệ mà ít nhất 1 case bù đã mượn dữ liệu; null khi không case nào mượn
 * @param fromSpecCount        số case dựng thẳng từ đặc tả (endpoint không có đầu vào nào khác cần dữ liệu hợp lệ nên
 *                             không cần case gốc) - nằm trong {@code createdCount}
 */
public record CoverageFillResponse(
        int createdCount,
        List<CoverageDimensionResponse.TestCaseRef> created,
        List<NotFilled> notFilled,
        String baselineTestCaseName,
        int fromSpecCount
) {
    public record NotFilled(String itemKey, String label, String reason) {
    }
}
