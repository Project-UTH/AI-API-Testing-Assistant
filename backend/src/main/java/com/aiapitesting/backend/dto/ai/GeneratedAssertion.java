package com.aiapitesting.backend.dto.ai;

import com.aiapitesting.backend.entity.AssertionOperator;

/** Structured-output type cho 1 assertion AI tự đề xuất kèm test case (Module 9b, includeAssertions=true). */
public record GeneratedAssertion(
        String jsonPath,
        AssertionOperator operator,
        String expectedValue
) {
}
