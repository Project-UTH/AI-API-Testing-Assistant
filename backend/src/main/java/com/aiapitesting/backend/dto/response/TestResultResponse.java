package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.BugReport;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

public record TestResultResponse(
        UUID id,
        UUID testCaseId,
        String testCaseName,
        UUID endpointId,
        TestResultStatus status,
        Integer expectedStatus,
        Integer responseStatus,
        String responseBody,
        String errorMessage,
        List<AssertionResultResponse> assertionResults,
        /** Bug Report đã tồn tại từ lần chạy này (nếu có) - null nếu chưa ai tạo. Cho phép frontend
         *  (trang Kết quả thực thi) hiện rõ dòng nào đã có bug rồi thay vì để người dùng tick lại
         *  vô ích, và cho bấm thẳng tới đúng bug đó (deep-link ?bugReportId=). */
        UUID existingBugReportId,
        String existingBugId
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static TestResultResponse from(TestResult result, BugReport existingBug) {
        return new TestResultResponse(
                result.getId(),
                result.getTestCase().getId(),
                result.getTestCase().getName(),
                result.getTestCase().getEndpoint().getId(),
                result.getStatus(),
                result.getTestCase().getExpectedStatus(),
                result.getResponseStatus(),
                result.getResponseBody(),
                result.getErrorMessage(),
                parseAssertionResults(result.getAssertionResultsJson()),
                existingBug == null ? null : existingBug.getId(),
                existingBug == null ? null : existingBug.getBugId()
        );
    }

    private static List<AssertionResultResponse> parseAssertionResults(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<AssertionResultResponse>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
