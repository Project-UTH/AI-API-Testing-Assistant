package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.AiUsageDailyPoint;
import com.aiapitesting.backend.dto.response.AiUsageResponse;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository.UsagePoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiUsageServiceTest {

    @Mock
    private TestGenerationEventRepository testGenerationEventRepository;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private AiUsageService aiUsageService;

    private static UsagePoint pointOf(Instant createdAt, Integer totalTokens) {
        return new UsagePoint() {
            @Override
            public Instant getCreatedAt() {
                return createdAt;
            }

            @Override
            public Integer getTotalTokens() {
                return totalTokens;
            }
        };
    }

    @Test
    void getMyUsage_returns90ContiguousDays_withZeroForDaysWithoutEvents() {
        User owner = User.builder().id(UUID.randomUUID()).email("u@test.com").build();
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        when(testGenerationEventRepository.findUsagePointsByOwnerSince(eq(owner), any()))
                .thenReturn(List.of());

        AiUsageResponse response = aiUsageService.getMyUsage();

        assertThat(response.daily()).hasSize(90);
        assertThat(response.daily()).allMatch(p -> p.totalTokens() == 0 && p.callCount() == 0);
        // Ngay cuoi cung phai la hom nay (UTC), ngay dau tien la 89 ngay truoc - lien tuc khong thieu ngay nao.
        assertThat(response.daily().get(89).date()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        assertThat(response.daily().get(0).date()).isEqualTo(LocalDate.now(ZoneOffset.UTC).minusDays(89));
    }

    @Test
    void getMyUsage_sumsMultipleEventsOnSameDay_intoOneBucket() {
        User owner = User.builder().id(UUID.randomUUID()).email("u@test.com").build();
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        Instant today9am = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).plusSeconds(9 * 3600);
        Instant today3pm = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).plusSeconds(15 * 3600);
        when(testGenerationEventRepository.findUsagePointsByOwnerSince(eq(owner), any()))
                .thenReturn(List.of(pointOf(today9am, 100), pointOf(today3pm, 250)));

        AiUsageResponse response = aiUsageService.getMyUsage();

        AiUsageDailyPoint todayPoint = response.daily().get(response.daily().size() - 1);
        assertThat(todayPoint.date()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        assertThat(todayPoint.totalTokens()).isEqualTo(350);
        assertThat(todayPoint.callCount()).isEqualTo(2);
    }

    @Test
    void getMyUsage_nullTotalTokens_countsCallButAddsZeroTokens() {
        User owner = User.builder().id(UUID.randomUUID()).email("u@test.com").build();
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        Instant today = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).plusSeconds(3600);
        when(testGenerationEventRepository.findUsagePointsByOwnerSince(eq(owner), any()))
                .thenReturn(List.of(pointOf(today, null)));

        AiUsageResponse response = aiUsageService.getMyUsage();

        AiUsageDailyPoint todayPoint = response.daily().get(response.daily().size() - 1);
        assertThat(todayPoint.totalTokens()).isZero();
        assertThat(todayPoint.callCount()).isEqualTo(1);
    }
}
