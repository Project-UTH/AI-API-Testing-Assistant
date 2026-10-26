package com.aiapitesting.backend.dto.response;

/**
 * 17q G2 - trạng thái kiểm thử của 1 endpoint, tính theo TEST (không tính test nghiệp vụ GIẢ ĐỊNH):
 * - NOT_GENERATED: chưa có test nào → không hiện "0%" và không tô đỏ (chưa làm, không phải làm sai).
 * - NOT_RUN: có test nhưng chưa test nào chạy ra kết luận (chưa chạy, hoặc chỉ ERROR/BLOCKED/môi trường/không ổn định).
 * - TESTED_FAILING: có ít nhất 1 test Fail với kết luận CHẮC là lỗi API (đỏ).
 * - TESTED_DOUBTFUL: chỉ có Fail mà kết luận còn NGHI (nguyên nhân chưa rõ, kỳ vọng do AI viết/quy ước, xung đột trạng thái...) - cần
 *   người xem, không tô đỏ vì chưa chắc là lỗi API (kiểm định 2026-10-23).
 * - TESTED_OK: đã chạy ra kết luận và không có Fail nào.
 */
public enum EndpointTestState {
    NOT_GENERATED,
    NOT_RUN,
    TESTED_FAILING,
    TESTED_DOUBTFUL,
    TESTED_OK
}
