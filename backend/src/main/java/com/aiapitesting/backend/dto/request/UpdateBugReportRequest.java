package com.aiapitesting.backend.dto.request;

import com.aiapitesting.backend.entity.BugFrequency;
import com.aiapitesting.backend.entity.BugPriority;
import com.aiapitesting.backend.entity.BugSeverity;
import com.aiapitesting.backend.entity.BugStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Sửa bug report - full-replace (như TestCaseRequest), không patch từng phần. */
public record UpdateBugReportRequest(
        @NotNull(message = "Phải chọn Status")
        BugStatus status,

        @NotNull(message = "Phải chọn Severity")
        BugSeverity severity,

        @NotNull(message = "Phải chọn Frequency")
        BugFrequency frequency,

        @NotNull(message = "Phải chọn Priority")
        BugPriority priority,

        @NotBlank(message = "Tiêu đề (summary) không được để trống")
        String summary,

        String testEnvironment,
        String stepsToReproduce,
        String actualResult,
        String expectedResult,
        String attachmentUrl,
        String build,
        String note
) {
}
