package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Tầng 3 (Bug Report) - 1 lần chạy của đúng 1 test case, hiện dạng thanh gọn + expand xem chi tiết. */
public record TestResultHistoryItemResponse(
        UUID testResultId,
        UUID executionId,
        Instant occurredAt,
        TestResultStatus status,
        Integer expectedStatus,
        Integer responseStatus,
        String responseBody,
        String errorMessage,
        List<AssertionResultResponse> assertionResults
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static TestResultHistoryItemResponse from(TestResult result) {
        return new TestResultHistoryItemResponse(
                result.getId(),
                result.getExecution().getId(),
                result.getExecution().getStartedAt(),
                result.getStatus(),
                result.getTestCase().getExpectedStatus(),
                result.getResponseStatus(),
                result.getResponseBody(),
                result.getErrorMessage(),
                parseAssertionResults(result.getAssertionResultsJson())
        );
    }

    // Cùng logic với TestResultResponse.parseAssertionResults - Tầng 3 (Bug Report) cần hiện đúng
    // assertion đã chấm giống hệt trang Kết quả thực thi, không lẽ nào 2 nơi lại khác dữ liệu nhau.
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
