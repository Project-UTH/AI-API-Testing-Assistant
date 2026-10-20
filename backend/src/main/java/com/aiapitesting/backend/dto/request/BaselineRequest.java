package com.aiapitesting.backend.dto.request;

import java.util.Map;

/**
 * Yêu cầu 'AI sinh test gốc + chạy thử xác nhận' cho 1 endpoint.
 *
 * @param confirmWrite    bắt buộc true với method ghi (POST/PUT/PATCH/DELETE): test gốc được CHẠY THẬT trên API đích và có thể tạo/sửa dữ liệu
 * @param maxRepairRounds số vòng AI sửa tối đa sau lần sinh đầu (mặc định 2, tối đa 3)
 * @param pathParamValues giá trị thật cho tham số đường dẫn (vd id) - luôn thắng giá trị AI đoán
 * @param fillCoverage    sau khi gốc được xác nhận, tự bổ sung toàn bộ checklist (0 token). Mặc định true
 * @param force           vẫn sinh gốc mới dù endpoint đã có test gốc đã Pass
 */
public record BaselineRequest(
        boolean confirmWrite, Integer maxRepairRounds, Map<String, String> pathParamValues, Boolean fillCoverage, boolean force
) {
}
