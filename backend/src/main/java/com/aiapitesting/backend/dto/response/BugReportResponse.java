package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.BugFrequency;
import com.aiapitesting.backend.entity.BugPriority;
import com.aiapitesting.backend.entity.BugReport;
import com.aiapitesting.backend.entity.BugSeverity;
import com.aiapitesting.backend.entity.BugStatus;

import java.time.Instant;
import java.util.UUID;

public record BugReportResponse(
        UUID id,
        String bugId,
        BugStatus status,
        BugSeverity severity,
        BugPriority priority,
        BugFrequency frequency,
        String summary,
        String testEnvironment,
        String stepsToReproduce,
        String actualResult,
        String expectedResult,
        String attachmentUrl,
        String build,
        ComponentRefResponse component,
        UUID testCaseId,
        String testCaseName,
        UUID sourceTestResultId,
        Instant sourceOccurredAt,
        String reporterEmail,
        boolean pendingCloseSuggestion,
        String note,
        Instant createdAt,
        Instant updatedAt
) {
    public static BugReportResponse from(BugReport bug) {
        return new BugReportResponse(
                bug.getId(),
                bug.getBugId(),
                bug.getStatus(),
                bug.getSeverity(),
                bug.getPriority(),
                bug.getFrequency(),
                bug.getSummary(),
                bug.getTestEnvironment(),
                bug.getStepsToReproduce(),
                bug.getActualResult(),
                bug.getExpectedResult(),
                bug.getAttachmentUrl(),
                bug.getBuild(),
                ComponentRefResponse.from(bug.getEndpoint()),
                bug.getTestCase().getId(),
                bug.getTestCase().getName(),
                bug.getSourceTestResult().getId(),
                bug.getSourceTestResult().getExecution().getStartedAt(),
                bug.getReporter().getEmail(),
                bug.isPendingCloseSuggestion(),
                bug.getNote(),
                bug.getCreatedAt(),
                bug.getUpdatedAt()
        );
    }
}
