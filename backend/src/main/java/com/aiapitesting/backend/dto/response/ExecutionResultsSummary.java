package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.TestResultStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tổng hợp trên TOÀN BỘ kết quả của 1 lần chạy (không bị thu hẹp theo bộ lọc/trang) - giao diện cần nó cho biểu đồ, banner
 * nguyên nhân Fail và nút "Sinh Bug Report" vì khi phân trang không còn tính được từ danh sách đang hiển thị.
 *
 * @param failureCauseCounts      số kết quả theo nguyên nhân Fail (chỉ những kết quả đã được phân loại)
 * @param failedWithoutBugCount   số kết quả FAILED chưa có Bug Report
 * @param bugResultIds            id các kết quả của lượt chạy này đã có Bug Report (trang chỉ tải từng phần nên cần biết trước
 *                                để hiện đúng ô "Bug Report đã sinh")
 */
public record ExecutionResultsSummary(
        long total,
        Map<TestResultStatus, Long> statusCounts,
        Map<FailureCause, Long> failureCauseCounts,
        long unstableCount,
        long failedWithoutBugCount,
        List<UUID> bugResultIds
) {
}
