package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Coverage Matrix của 1 endpoint (Module 15c), tính tất định - không gọi AI, xem {@code EndpointCoverageService}.
 * {@code coveragePercent} chỉ tính trên dimension COVERED/MISSING (loại NOT_APPLICABLE khỏi mẫu số).
 *
 * @param testCaseCount   Module 15j - tổng test case hiện có của endpoint (0 = endpoint chưa có test nào)
 * @param executedPercent Module 15j - % dimension vừa COVERED vừa đã chạy ra kết quả thật (runStatus PASSED
 *                        hoặc FAILED), cùng mẫu số với {@code coveragePercent} - luôn nhỏ hơn hoặc bằng nó
 * @param scope           phạm vi đo: chỉ loại trong phạm vi có dòng trong {@code dimensions} và tính vào %.
 *                        Dòng STATUS_CODE/RELATION (độ phủ theo spec, không thuộc loại nào) vẫn trả về nhưng
 *                        KHÔNG tính vào {@code coveragePercent}/{@code executedPercent}.
 * @param schemaReadable  Module 15f - false khi {@code Endpoint.schema} không đọc được (JSON hỏng) -
 *                        lúc đó {@code coveragePercent}/{@code dimensions} KHÔNG đáng tin, FE phải cảnh báo
 *                        rõ thay vì ngầm hiểu số % hiện có là đầy đủ.
 */
public record EndpointCoverageResponse(
        UUID endpointId,
        String path,
        String method,
        int testCaseCount,
        double coveragePercent,
        double executedPercent,
        boolean schemaReadable,
        CoverageScope scope,
        boolean requiresAuth,
        String requiresAuthSource,
        List<CoverageDimensionResponse> dimensions
) {
}
