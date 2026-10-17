package com.aiapitesting.backend.dto.response;

import java.util.List;

/**
 * Module 15j - kết quả bù khoảng trống coverage: case đã tạo + mục không bù được kèm lý do (không im lặng bỏ qua).
 *
 * @param baselineTestCaseName case gốc hợp lệ đã dùng để tạo case bù; null khi không tìm được case gốc nào
 */
public record CoverageFillResponse(
        int createdCount,
        List<CoverageDimensionResponse.TestCaseRef> created,
        List<NotFilled> notFilled,
        String baselineTestCaseName
) {
    public record NotFilled(String itemKey, String label, String reason) {
    }
}
