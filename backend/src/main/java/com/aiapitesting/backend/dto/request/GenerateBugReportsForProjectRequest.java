package com.aiapitesting.backend.dto.request;

import java.util.List;
import java.util.UUID;

/**
 * Sinh Bug Report hàng loạt từ trang Bug Report (toàn project, không giới hạn 1 execution).
 * testResultIds null/rỗng = xét TOÀN BỘ kết quả Fail của project ("Sinh tất cả"); có truyền = chỉ
 * xét đúng các dòng đã tick ("Sinh theo lựa chọn").
 */
public record GenerateBugReportsForProjectRequest(
        List<UUID> testResultIds
) {
}
