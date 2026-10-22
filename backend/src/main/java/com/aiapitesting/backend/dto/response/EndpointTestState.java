package com.aiapitesting.backend.dto.response;

/**
 * 17q G2 - trạng thái kiểm thử của 1 endpoint, tính theo TEST (không tính test nghiệp vụ GIẢ ĐỊNH):
 * - NOT_GENERATED: chưa có test nào → không hiện "0%" và không tô đỏ (chưa làm, không phải làm sai).
 * - NOT_RUN: có test nhưng chưa test nào chạy ra kết luận (chưa chạy, hoặc chỉ ERROR/BLOCKED/môi trường/không ổn định).
 * - TESTED_FAILING: có ít nhất 1 test Fail mà kết luận được (không phải do môi trường/token/không ổn định).
 * - TESTED_OK: đã chạy ra kết luận và không có Fail nào như trên.
 */
public enum EndpointTestState {
    NOT_GENERATED,
    NOT_RUN,
    TESTED_FAILING,
    TESTED_OK
}
