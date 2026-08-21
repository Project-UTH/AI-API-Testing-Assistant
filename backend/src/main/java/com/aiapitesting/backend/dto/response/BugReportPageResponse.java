package com.aiapitesting.backend.dto.response;

import java.util.List;

/** Payload gộp cho GET .../bug-reports - 1 lần gọi phục vụ cả Dashboard lẫn cấu trúc lồng 3 tầng. */
public record BugReportPageResponse(BugDashboardSummaryResponse summary, List<EndpointBugSummaryResponse> endpoints) {
}
