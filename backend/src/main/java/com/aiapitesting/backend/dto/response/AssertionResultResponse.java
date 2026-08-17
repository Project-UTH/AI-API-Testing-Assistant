package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.AssertionOperator;

/**
 * Kết quả chấm 1 assertion sau khi gọi target API thật (TestExecutionRunner, Module 9b) - serialize
 * thẳng thành JSON lưu vào TestResult.assertionResultsJson, đọc lại nguyên hình dạng này ở
 * TestResultResponse.from() - không cần record trung gian nào khác.
 */
public record AssertionResultResponse(
        String jsonPath,
        AssertionOperator operator,
        String expectedValue,
        String actualValue,
        boolean passed
) {
}
