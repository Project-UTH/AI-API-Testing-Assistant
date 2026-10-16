package com.aiapitesting.backend.dto.response;

import java.util.List;

/**
 * Module 15h - báo cáo 1 lần sinh nhóm Basic: checklistTotal = N tính theo công thức từ spec;
 * aiAskedCount = số mục thật sự giao cho AI (trừ mục đã có sẵn/không áp dụng, tối đa 15/lần);
 * aiSatisfiedCount = số mục AI làm đạt (chấm theo nội dung); aiExtraCount = số case AI đề xuất thêm
 * ngoài checklist. checklistCaseCount/extraCaseNames chụp lại bộ case lúc sinh để báo cáo đọc lại
 * được sau khi tải lại trang mà không phụ thuộc bộ test case đang sống (có thể đã bị sửa/xoá).
 */
public record GenerationPlanSummary(
        int checklistTotal,
        int alreadyCoveredCount,
        int aiAskedCount,
        int aiSatisfiedCount,
        int systemFilledCount,
        int notFillableCount,
        int notApplicableCount,
        int aiExtraCount,
        int checklistCaseCount,
        List<String> extraCaseNames,
        List<ChecklistItemResponse> items
) {
}
