package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.ExecutionStatus;

import java.time.Instant;
import java.util.UUID;

/** 1 lượt chạy rút gọn (không kèm kết quả từng test) - để chọn lượt cần so sánh (Module 17 - 17c). */
public record ExecutionSummaryResponse(
        UUID id, Instant startedAt, Instant finishedAt, ExecutionStatus status,
        long totalCount, long passedCount, long failedCount
) {
}
