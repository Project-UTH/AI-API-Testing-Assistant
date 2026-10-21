package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.ExecutionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lần chạy với kết quả theo TRANG ({@code GET /executions/{id}?page=...}). Dạng cũ {@link TestExecutionResponse} (toàn bộ
 * kết quả trong 1 mảng) giữ nguyên cho nơi cần đủ danh sách.
 */
public record TestExecutionPageResponse(
        UUID id,
        ExecutionStatus status,
        Instant startedAt,
        Instant finishedAt,
        PageResponse<TestResultResponse> results,
        ExecutionResultsSummary summary,
        /** Test case tự động chạy kèm do dependency (Module 7) - tính trên toàn bộ lần chạy, không chỉ trang này. */
        List<UUID> autoIncludedTestCaseIds
) {
}
