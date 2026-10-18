package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.FailureCause;

import java.util.List;

/**
 * Module 16 - nguyên nhân NGHI NGỜ của 1 kết quả FAILED/ERROR, chấm tất định lúc chạy (không gọi AI) kèm bằng chứng.
 * Chỉ là nghi ngờ, không khẳng định - kiểm thử black-box không thấy bên trong hệ thống đích.
 *
 * @param code        tên {@link FailureCause}
 * @param label       nhãn hiển thị tiếng Việt
 * @param notApiFault true khi bằng chứng mạnh rằng fail KHÔNG phải do API đang test (môi trường/token/cấu hình test)
 * @param evidence    các câu bằng chứng cụ thể, hiển thị trực tiếp
 */
public record FailureCauseResponse(String code, String label, boolean notApiFault, List<String> evidence) {

    public static FailureCauseResponse of(FailureCause cause, List<String> evidence) {
        return new FailureCauseResponse(cause.name(), cause.label(), cause.isNotApiFault(), evidence);
    }
}
