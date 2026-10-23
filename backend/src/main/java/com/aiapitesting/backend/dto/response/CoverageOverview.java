package com.aiapitesting.backend.dto.response;

/**
 * Số đo coverage của TOÀN project (không bị thu hẹp bởi bộ lọc/trang).
 *
 * @param endpointsWithGaps số endpoint còn ít nhất 1 mục thiếu
 * @param endpointsFailing  số endpoint có ít nhất 1 mục mà test mới nhất FAILED
 * @param testedEndpoints   17q G2 - số endpoint đã chạy ra kết luận (TESTED_FAILING + TESTED_DOUBTFUL + TESTED_OK) - số chính của cấp project
 * @param stateCounts       17q G2 - phân bố endpoint theo {@link EndpointTestState} (đủ mọi khoá)
 * @param generatedRelevantCount 17q G2 - số mục của các endpoint ĐÃ SINH test (mẫu số cho % chỉ tính trên endpoint đã sinh)
 * @param generatedCoveredCount  17q G2 - số mục có test trong các endpoint đã sinh
 * @param generatedExecutedCount 17q G2 - số mục đã chạy trong các endpoint đã sinh
 * @param certainCount      17q G2 - số mục có kết luận CHẮC (toàn project)
 * @param doubtfulCount     17q G2 - số mục có kết luận NGHI (toàn project)
 */
public record CoverageOverview(
        int endpointCount, int endpointsWithGaps, int endpointsFailing,
        long relevantCount, long coveredCount, long executedCount,
        int testedEndpoints, java.util.Map<EndpointTestState, Integer> stateCounts,
        long generatedRelevantCount, long generatedCoveredCount, long generatedExecutedCount,
        long certainCount, long doubtfulCount
) {
}
