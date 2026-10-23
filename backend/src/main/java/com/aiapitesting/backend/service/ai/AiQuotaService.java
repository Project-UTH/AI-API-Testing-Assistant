package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.AiQuotaExceededException;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 17q G3.3 - quota token AI trong ngày của chủ project (mặc định hệ thống hoặc mức Admin đặt riêng cho user). Dùng cho tính năng mới (trích luật);
 * các service cũ vẫn có bản riêng để không đổi hành vi/test ổn định. Tính theo {@code ownerId} của sổ token nên sự kiện cấp project cũng được tính.
 */
@Service
@RequiredArgsConstructor
public class AiQuotaService {

    private final TestGenerationEventRepository testGenerationEventRepository;

    @Value("${ai.quota.daily-token-limit}")
    private long dailyTokenLimit;

    /** Token còn được dùng hôm nay (>= 0). */
    public long remainingToday(Project project) {
        return remainingToday(project.getOwner());
    }

    /** Dùng được ngoài phiên Hibernate (luồng job): truyền User đã tải đầy đủ. */
    public long remainingToday(User owner) {
        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        long used = testGenerationEventRepository.sumTotalTokensByOwnerAndCreatedAtBetween(
                owner, startOfToday, startOfToday.plus(1, ChronoUnit.DAYS));
        Integer override = owner.getAiDailyTokenLimitOverride();
        long limit = override != null ? override : dailyTokenLimit;
        return Math.max(0, limit - used);
    }

    /** Ném {@link AiQuotaExceededException} khi đã hết quota hôm nay. */
    public void ensureWithin(Project project) {
        if (remainingToday(project) <= 0) {
            Integer override = project.getOwner().getAiDailyTokenLimitOverride();
            throw new AiQuotaExceededException("Đã đạt giới hạn " + (override != null ? override : dailyTokenLimit)
                    + " token AI hôm nay, vui lòng thử lại vào ngày mai");
        }
    }
}
