package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.service.report.ExecutionConclusionCalculator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Trạng thái + kết luận 1 lượt chạy cho CI (gọn, không có kết quả từng test - dùng báo cáo JUnit/HTML cho chi tiết). */
public record CiExecutionResponse(
        UUID executionId, ExecutionStatus status, ExecutionConclusionCalculator.Conclusion conclusion,
        ExecutionConclusionCalculator.FailOn failOn, Instant startedAt, Instant finishedAt,
        ExecutionConclusionCalculator.Summary summary, List<String> warnings
) {
}
