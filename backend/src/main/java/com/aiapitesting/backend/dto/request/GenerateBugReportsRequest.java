package com.aiapitesting.backend.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Sinh Bug Report hàng loạt từ trang Kết quả thực thi. testResultIds null/rỗng = xét TOÀN BỘ kết
 * quả Fail của execution ("Sinh tất cả"); có truyền = chỉ xét đúng các dòng đã tick.
 */
public record GenerateBugReportsRequest(
        @NotNull(message = "Thiếu id lần thực thi (executionId)")
        UUID executionId,

        List<UUID> testResultIds
) {
}
