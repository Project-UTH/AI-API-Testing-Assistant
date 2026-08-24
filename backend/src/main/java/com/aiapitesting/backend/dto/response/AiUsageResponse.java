package com.aiapitesting.backend.dto.response;

import java.util.List;

/**
 * Bucket sẵn theo NGÀY (mỗi phần tử = 1 ngày, đủ mọi ngày trong khoảng, không thiếu ngày nào) -
 * gộp thành tuần/tháng là việc của frontend (cộng dồn các điểm ngày liên tiếp), backend không tính
 * sẵn 3 bản riêng để tránh 3 query/3 vòng lặp cho cùng 1 dữ liệu nguồn.
 */
public record AiUsageResponse(List<AiUsageDailyPoint> daily) {
}
