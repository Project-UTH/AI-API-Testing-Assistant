package com.aiapitesting.backend.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record TestExecutionRequest(
        @NotEmpty(message = "Phải chọn ít nhất 1 test case để chạy")
        List<UUID> testCaseIds
) {
}
