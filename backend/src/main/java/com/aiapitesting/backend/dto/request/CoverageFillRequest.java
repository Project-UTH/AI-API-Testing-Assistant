package com.aiapitesting.backend.dto.request;

import java.util.List;

/**
 * Module 15j - bù khoảng trống coverage của 1 endpoint.
 *
 * @param itemKeys khoá các mục checklist cần bù (lấy từ {@code CoverageDimensionResponse.itemKey}); null/rỗng
 *                 = bù TẤT CẢ mục còn thiếu mà hệ thống tự bù được. Khoá không khớp mục nào (spec đã đổi) bị bỏ qua.
 */
public record CoverageFillRequest(List<String> itemKeys) {
}
