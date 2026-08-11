package com.aiapitesting.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TestCaseDependencyInput(
        @NotNull(message = "Phải chọn test case nguồn")
        UUID dependsOnTestCaseId,

        @NotBlank(message = "JSONPath không được để trống")
        String jsonPath,

        @NotBlank(message = "Tên placeholder không được để trống")
        String placeholderName
) {
}
