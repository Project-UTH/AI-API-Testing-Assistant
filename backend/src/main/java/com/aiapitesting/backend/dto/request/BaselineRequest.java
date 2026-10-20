package com.aiapitesting.backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * Yêu cầu AI sinh test gốc + chạy thử xác nhận cho 1 endpoint. Là lớp thường (không phải record) để client gửi THIẾU trường vẫn hợp lệ
 * (record bị Jackson bắt buộc đủ mọi trường).
 * confirmWrite: bắt buộc true với method ghi vì test gốc được CHẠY THẬT trên API đích; maxRepairRounds: số vòng AI sửa (mặc định 2, tối đa 3);
 * pathParamValues: giá trị thật cho tham số đường dẫn, luôn thắng giá trị AI đoán; fillCoverage: sau khi xác nhận tự bổ sung checklist
 * (mặc định true); force: vẫn sinh gốc mới dù đã có test gốc đã Pass.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BaselineRequest {
    private Boolean confirmWrite;
    private Integer maxRepairRounds;
    private Map<String, String> pathParamValues;
    private Boolean fillCoverage;
    private Boolean force;
}
