package com.aiapitesting.backend.dto.response;

import java.util.List;

/**
 * Module 15h - 1 mục checklist và kết quả của nó; reason chỉ có khi NOT_FILLABLE/NOT_APPLICABLE.
 * coveredBy = tên các test case đạt mục này (1 mục có thể có nhiều case, vd 2 case cùng là dữ liệu
 * hợp lệ) - để báo cáo chỉ rõ case nào ứng với mục nào, người đọc tự đối chiếu được tổng số case.
 */
public record ChecklistItemResponse(
        ChecklistItemType type,
        String field,
        String description,
        ChecklistItemStatus status,
        String reason,
        List<String> coveredBy
) {
}
