package com.aiapitesting.backend.dto.response;

import java.util.UUID;

/** Gợi ý tự sinh khi bấm badge "Fail" để tạo bug report (Module 10) - người dùng sửa tay trước khi gửi. */
public record BugReportDraftResponse(
        UUID testCaseId,
        String testCaseName,
        UUID sourceTestResultId,
        ComponentRefResponse component,
        String summary,
        String stepsToReproduce,
        String actualResult,
        String expectedResult,
        String defaultBuild
) {
}
