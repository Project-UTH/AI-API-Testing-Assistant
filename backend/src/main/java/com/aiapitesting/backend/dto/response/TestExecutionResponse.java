package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.BugReport;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TestExecutionResponse(
        UUID id,
        ExecutionStatus status,
        Instant startedAt,
        Instant finishedAt,
        List<TestResultResponse> results,
        /** Test case tự động chạy kèm do dependency (Module 7) - rỗng nếu không có chaining liên quan. */
        List<UUID> autoIncludedTestCaseIds
) {
    public static TestExecutionResponse pending(TestExecution execution, List<UUID> autoIncludedTestCaseIds) {
        return new TestExecutionResponse(
                execution.getId(), execution.getStatus(), execution.getStartedAt(), execution.getFinishedAt(),
                List.of(), autoIncludedTestCaseIds);
    }

    public static TestExecutionResponse from(
            TestExecution execution, List<TestResult> results, Map<UUID, BugReport> existingBugByResultId
    ) {
        List<UUID> autoIncludedTestCaseIds = results.stream()
                .filter(TestResult::isAutoIncluded)
                .map(r -> r.getTestCase().getId())
                .toList();
        return new TestExecutionResponse(
                execution.getId(), execution.getStatus(), execution.getStartedAt(), execution.getFinishedAt(),
                results.stream()
                        .map(r -> TestResultResponse.from(r, existingBugByResultId.get(r.getId())))
                        .toList(),
                autoIncludedTestCaseIds);
    }
}
