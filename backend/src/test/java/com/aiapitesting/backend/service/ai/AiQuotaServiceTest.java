package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestGenerationEvent;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.AiQuotaExceededException;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiQuotaServiceTest {

    @Mock
    private TestGenerationEventRepository events;

    private AiQuotaService quota;
    private User owner;
    private Project project;

    @BeforeEach
    void setUp() {
        quota = new AiQuotaService(events);
        ReflectionTestUtils.setField(quota, "dailyTokenLimit", 50_000L);
        owner = User.builder().id(UUID.randomUUID()).email("a@b.com").build();
        project = Project.builder().id(UUID.randomUUID()).owner(owner).build();
    }

    private void used(long tokens) {
        when(events.sumTotalTokensByOwnerAndCreatedAtBetween(eq(owner), any(Instant.class), any(Instant.class))).thenReturn(tokens);
    }

    @Test
    void remaining_isDefaultLimitMinusUsed_neverNegative() {
        used(20_000);
        assertThat(quota.remainingToday(project)).isEqualTo(30_000);
        used(80_000);
        assertThat(quota.remainingToday(project)).isZero();
    }

    @Test
    void adminOverridePerUser_replacesTheSystemDefault() {
        owner.setAiDailyTokenLimitOverride(100_000);
        used(60_000);
        assertThat(quota.remainingToday(project)).isEqualTo(40_000);
    }

    @Test
    void ensureWithin_throwsOnlyWhenNothingIsLeft() {
        used(49_999);
        quota.ensureWithin(project);
        used(50_000);
        assertThatThrownBy(() -> quota.ensureWithin(project)).isInstanceOf(AiQuotaExceededException.class).hasMessageContaining("50000");
    }

    @Test
    void ledgerEvent_derivesOwnerAndProjectFromEndpoint_andProjectLevelEventNeedsNoEndpoint() {
        Endpoint endpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).build();
        TestGenerationEvent perEndpoint = TestGenerationEvent.builder().endpoint(endpoint).build();
        ReflectionTestUtils.invokeMethod(perEndpoint, "onCreate");
        assertThat(perEndpoint.getOwnerId()).isEqualTo(owner.getId());
        assertThat(perEndpoint.getProjectId()).isEqualTo(project.getId());

        TestGenerationEvent projectLevel = TestGenerationEvent.builder().ownerId(owner.getId()).projectId(project.getId())
                .eventType("RULE_EXTRACT").build();
        ReflectionTestUtils.invokeMethod(projectLevel, "onCreate");
        assertThat(projectLevel.getEndpoint()).isNull();
        assertThat(projectLevel.getOwnerId()).isEqualTo(owner.getId());
        assertThat(projectLevel.getCreatedAt()).isNotNull();
    }
}
