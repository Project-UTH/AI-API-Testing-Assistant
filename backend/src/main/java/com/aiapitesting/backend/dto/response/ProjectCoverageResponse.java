package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Coverage toàn project (Module 15c). {@code overallRiskWeightedCoveragePercent} là điểm khác với
 * {@code overallCoveragePercent} phẳng: mỗi endpoint đóng góp theo đúng trọng số risk score của nó
 * (endpoint rủi ro cao mà thiếu coverage sẽ kéo điểm này xuống nhiều hơn endpoint rủi ro thấp) -
 * công thức: Σ(endpoint.riskScore × endpoint.coveragePercent) / Σ(endpoint.riskScore). Endpoint có
 * riskScore = 0 không đóng góp vào cả tử và mẫu (không chia 0, cũng không kéo điểm sai).
 */
public record ProjectCoverageResponse(
        UUID projectId,
        double overallCoveragePercent,
        double overallRiskWeightedCoveragePercent,
        List<EndpointCoverageResponse> endpoints
) {
}
