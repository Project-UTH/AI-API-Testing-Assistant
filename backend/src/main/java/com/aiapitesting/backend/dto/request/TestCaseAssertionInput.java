package com.aiapitesting.backend.dto.request;

import com.aiapitesting.backend.entity.AssertionOperator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TestCaseAssertionInput(
        @NotBlank(message = "JSONPath không được để trống")
        String jsonPath,

        @NotNull(message = "Phải chọn operator")
        AssertionOperator operator,

        String expectedValue
) {
}
