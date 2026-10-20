package com.aiapitesting.backend.dto.response;

import java.util.List;

/**
 * Kết quả so sánh spec mới với các endpoint hiện có (Module 17 - 17c), tính TRƯỚC khi import để người dùng biết import sẽ làm gì.
 *
 * @param affectedTestCaseCount số test case của các endpoint ĐÃ ĐỔI spec (có thể lỗi thời, cần xem lại)
 * @param changedEndpoints      tên "METHOD path" các endpoint đã đổi (rút gọn)
 * @param removedEndpoints      tên "METHOD path" các endpoint không còn trong spec
 */
public record ImportDiffResponse(
        int addedCount, int changedCount, int removedCount, int unchangedCount, long affectedTestCaseCount,
        List<String> changedEndpoints, List<String> removedEndpoints, List<String> addedEndpoints
) {
}
