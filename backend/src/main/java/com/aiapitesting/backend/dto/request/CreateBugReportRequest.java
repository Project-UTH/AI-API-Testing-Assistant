package com.aiapitesting.backend.dto.request;

import com.aiapitesting.backend.entity.BugFrequency;
import com.aiapitesting.backend.entity.BugPriority;
import com.aiapitesting.backend.entity.BugSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Tạo bug report mới từ 1 TestResult có status FAILED (Module 10). 4 field mô tả
 * (testEnvironment/stepsToReproduce/actualResult/expectedResult) cho phép null - frontend luôn
 * gửi kèm giá trị gợi ý đã lấy từ GET .../bug-reports/draft (người dùng có thể sửa trước khi gửi),
 * nhưng không bắt buộc để backend không phụ thuộc hoàn toàn vào việc frontend đã gọi draft trước.
 */
public record CreateBugReportRequest(
        @NotNull(message = "Thiếu id lần chạy nguồn (sourceTestResultId)")
        UUID sourceTestResultId,

        @NotBlank(message = "Tiêu đề (summary) không được để trống")
        String summary,

        String testEnvironment,
        String stepsToReproduce,
        String actualResult,
        String expectedResult,

        @NotNull(message = "Phải chọn Severity")
        BugSeverity severity,

        @NotNull(message = "Phải chọn Frequency")
        BugFrequency frequency,

        @NotNull(message = "Phải chọn Priority")
        BugPriority priority,

        String attachmentUrl,
        String build
) {
}
