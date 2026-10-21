package com.aiapitesting.backend.dto.response;

/**
 * Số đo coverage của TOÀN project (không bị thu hẹp bởi bộ lọc/trang).
 *
 * @param endpointsWithGaps số endpoint còn ít nhất 1 mục thiếu
 * @param endpointsFailing  số endpoint có ít nhất 1 mục mà test mới nhất FAILED
 */
public record CoverageOverview(
        int endpointCount, int endpointsWithGaps, int endpointsFailing,
        long relevantCount, long coveredCount, long executedCount
) {
}
