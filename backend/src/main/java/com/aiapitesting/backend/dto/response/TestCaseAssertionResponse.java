package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.AssertionOperator;
import com.aiapitesting.backend.entity.TestCaseAssertion;

public record TestCaseAssertionResponse(
        String jsonPath,
        AssertionOperator operator,
        String expectedValue
) {
    public static TestCaseAssertionResponse from(TestCaseAssertion assertion) {
        return new TestCaseAssertionResponse(
                assertion.getJsonPath(),
                assertion.getOperator(),
                assertion.getExpectedValue()
        );
    }
}
