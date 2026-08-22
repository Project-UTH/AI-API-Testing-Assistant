package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.AiUsageDailyPoint;
import com.aiapitesting.backend.dto.response.AiUsageResponse;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository.UsagePoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Biểu đồ usage token AI theo ngày (Module 11) - dùng chung cho trang Tổng quan của user (chỉ dữ
 * liệu của chính họ) VÀ trang Admin (1 user cụ thể hoặc toàn hệ thống). Luôn trả về ĐỦ mọi ngày
 * trong cửa sổ {@link #WINDOW_DAYS} ngày gần nhất, kể cả ngày không có lượt gọi AI nào (token=0) -
 * để biểu đồ frontend không bị đứt quãng.
 */
@Service
@RequiredArgsConstructor
public class AiUsageService {

    private static final int WINDOW_DAYS = 90;

    private final TestGenerationEventRepository testGenerationEventRepository;
    private final CurrentUserService currentUserService;

    public AiUsageResponse getMyUsage() {
        return getUsageForOwner(currentUserService.getCurrentUser());
    }

    /** Package-private - gọi từ AdminUserDataService với User đã resolve theo owner CHỈ ĐỊNH. */
    AiUsageResponse getUsageForOwner(User owner) {
        return bucketByDay(testGenerationEventRepository.findUsagePointsByOwnerSince(owner, windowStart()));
    }

    /** Package-private - gọi từ AdminDashboardService, gộp MỌI user. */
    AiUsageResponse getSystemUsage() {
        return bucketByDay(testGenerationEventRepository.findAllUsagePointsSince(windowStart()));
    }

    private Instant windowStart() {
        return Instant.now().truncatedTo(ChronoUnit.DAYS).minus(WINDOW_DAYS - 1L, ChronoUnit.DAYS);
    }

    private AiUsageResponse bucketByDay(List<UsagePoint> points) {
        // LinkedHashMap giữ đúng thứ tự chèn (từ xa nhất -> hôm nay) - khởi tạo đủ mọi ngày trước,
        // rồi mới cộng dồn dữ liệu thật vào - đảm bảo không thiếu ngày nào dù ngày đó không có event.
        Map<LocalDate, long[]> byDate = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int i = WINDOW_DAYS - 1; i >= 0; i--) {
            byDate.put(today.minusDays(i), new long[]{0L, 0L});
        }

        for (UsagePoint point : points) {
            LocalDate date = point.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
            long[] slot = byDate.get(date);
            if (slot == null) {
                continue; // Phòng hờ lệch múi giờ ở rìa cửa sổ - bỏ qua thay vì NPE, không quan trọng cho biểu đồ.
            }
            slot[0] += point.getTotalTokens() == null ? 0L : point.getTotalTokens();
            slot[1] += 1;
        }

        List<AiUsageDailyPoint> daily = new ArrayList<>(byDate.size());
        byDate.forEach((date, slot) -> daily.add(new AiUsageDailyPoint(date, slot[0], slot[1])));
        return new AiUsageResponse(daily);
    }
}
