package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.ExecutionStatus;

import java.util.UUID;

/** Kết quả khởi chạy từ CI (HTTP 202): CI dùng statusUrl để poll, reportUrl để lấy báo cáo. */
public record CiRunResponse(
        UUID executionId, ExecutionStatus status, String statusUrl, String reportUrl,
        int selectedTestCaseCount, int excludedTestCaseCount
) {
}
