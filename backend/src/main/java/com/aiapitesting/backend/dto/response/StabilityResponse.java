package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.StabilityState;
import com.aiapitesting.backend.service.execution.TestStabilityAnalyzer;

import java.util.Locale;
import java.util.Set;

/**
 * Module 16 giai đoạn 2 - độ ổn định KẾT QUẢ của test case theo lịch sử các lần chạy CÙNG phiên bản test, tính lúc đọc
 * (phản ánh tình trạng HIỆN TẠI của test, kể cả khi xem 1 lượt chạy cũ). Chỉ trả khi đáng chú ý: {@code FLAKY} hoặc
 * {@code NEEDS_MORE_RUNS}. Là nhận định về tính tất định của kết quả, không khẳng định nguyên nhân (black-box).
 *
 * @param passRate     tỷ lệ Pass (0-1) trong các lần chạy tính được
 * @param flips        số lần kết quả đổi giữa Pass và Fail
 * @param rerunReason  chỉ có khi {@code NEEDS_MORE_RUNS}: vì sao nên chạy lại
 * @param mutatesData  method của endpoint có thể tạo/sửa/xoá dữ liệu thật của hệ thống đích (không phải GET/HEAD/OPTIONS)
 *                     - chạy lại cần xác nhận
 */
public record StabilityResponse(
        StabilityState state, int runs, int passes, int fails, int flips, double passRate,
        String rerunReason, boolean mutatesData
) {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    public static StabilityResponse of(TestStabilityAnalyzer.Assessment assessment, String httpMethod) {
        boolean mutates = httpMethod == null || !SAFE_METHODS.contains(httpMethod.toUpperCase(Locale.ROOT));
        return new StabilityResponse(assessment.state(), assessment.runs(), assessment.passes(), assessment.fails(),
                assessment.flips(), Math.round(assessment.passRate() * 1000) / 1000.0, assessment.rerunReason(), mutates);
    }
}
