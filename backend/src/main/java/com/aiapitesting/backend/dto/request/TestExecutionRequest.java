package com.aiapitesting.backend.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record TestExecutionRequest(
        @NotEmpty(message = "Phải chọn ít nhất 1 test case để chạy")
        List<UUID> testCaseIds,
        /** Tuỳ chọn: sau khi chạy, xoá các tài nguyên cha do CHÍNH lần chạy này tạo (cần có endpoint DELETE tương ứng). Bỏ trống = không xoá. */
        Boolean cleanupCreated
) {
    public TestExecutionRequest(List<UUID> testCaseIds) {
        this(testCaseIds, null);
    }
}
