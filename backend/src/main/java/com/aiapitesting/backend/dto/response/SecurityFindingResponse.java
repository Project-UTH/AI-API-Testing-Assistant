package com.aiapitesting.backend.dto.response;

/** 1 cảnh báo dữ liệu nhạy cảm (Module 17 - 17b): chỉ đường dẫn JSON + mã quy tắc + mô tả, KHÔNG BAO GIỜ có giá trị thật. */
public record SecurityFindingResponse(String path, String rule, String severity, String detail) {
}
