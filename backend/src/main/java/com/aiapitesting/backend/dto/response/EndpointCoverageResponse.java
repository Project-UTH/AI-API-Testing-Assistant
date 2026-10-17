package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Coverage Matrix + Risk Engine của 1 endpoint (Module 15c). {@code riskScore}/{@code riskLevel}
 * tính tất định (method/auth/dependency/security exposure/lịch sử fail) - không gọi AI, xem
 * {@code EndpointCoverageService}. {@code coveragePercent} chỉ tính trên dimension COVERED/MISSING
 * (loại NOT_APPLICABLE khỏi mẫu số).
 *
 * @param testCaseCount   Module 15j - tổng test case hiện có của endpoint (0 = endpoint chưa có test nào)
 * @param executedPercent Module 15j - % dimension vừa COVERED vừa đã chạy ra kết quả thật (runStatus PASSED
 *                        hoặc FAILED), cùng mẫu số với {@code coveragePercent} - luôn nhỏ hơn hoặc bằng nó
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
        int riskScore,
        RiskLevel riskLevel,
        List<String> riskReasons,
        List<CoverageDimensionResponse> dimensions
) {
}
