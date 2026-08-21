package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.BugPriority;

import java.util.List;
import java.util.Map;

/** Khối Dashboard tổng hợp đầu trang Bug Report (Module 10). */
public record BugDashboardSummaryResponse(
        Map<BugPriority, Long> countByPriority,
        List<ComponentBugCountResponse> countByComponent,
        long openCount,
        long closedCount,
        long totalCount
) {
}
