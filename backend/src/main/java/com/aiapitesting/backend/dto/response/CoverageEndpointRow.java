package com.aiapitesting.backend.dto.response;

import java.util.UUID;

/**
 * Tóm tắt coverage của 1 endpoint (không có từng dòng chi tiết) - dùng cho danh sách phân trang và màn hình tổng quan.
 * Có đủ SỐ ĐẾM (không chỉ %) vì % tổng của project là tổng các mục chứ không phải trung bình % của từng endpoint.
 *
 * @param relevantCount số mục thuộc phạm vi đo (không tính "không áp dụng")
 * @param coveredCount  số mục đã có test
 * @param executedCount số mục đã có test VÀ test đó đã chạy ra kết quả thật
 * @param failingCount  số mục đã có test nhưng lần chạy mới nhất FAILED
 * @param missingCount  số mục còn thiếu
 * @param testState     17q G2 - trạng thái kiểm thử của endpoint (chưa sinh / chưa chạy / có Fail / ổn)
 * @param certainCount  17q G2 - số mục đã chạy có kết luận CHẮC (⊆ executedCount)
 * @param doubtfulCount 17q G2 - số mục đã chạy có kết luận NGHI
 * @param specGapCount  17q G2 - số mục "spec thiếu ràng buộc" (biên ngầm, kỳ vọng theo quy ước)
 * @param baselineVerified 17q G2 - endpoint có test gốc AI đã chạy thật xác nhận (khoá làm nền)
 */
public record CoverageEndpointRow(
        UUID endpointId, String path, String method, boolean requiresAuth, int testCaseCount,
        long relevantCount, long coveredCount, long executedCount, long failingCount, long missingCount,
        double coveragePercent, double executedPercent,
        EndpointTestState testState, long certainCount, long doubtfulCount, long specGapCount, boolean baselineVerified,
        /** 17q G3 - lớp nghiệp vụ; null = endpoint chưa có luật nào. */
        RuleCoverage rules
) {
}
