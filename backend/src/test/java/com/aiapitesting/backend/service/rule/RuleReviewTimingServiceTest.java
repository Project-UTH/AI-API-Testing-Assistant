package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.ReviewTimingRequest;
import com.aiapitesting.backend.entity.BusinessRule;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RuleReviewTiming;
import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.RuleStatus;
import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.BusinessRuleRepository;
import com.aiapitesting.backend.repository.RuleReviewTimingRepository;
import com.aiapitesting.backend.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleReviewTimingServiceTest {

    @Mock
    private ProjectService projectService;
    @Mock
    private BusinessRuleRepository ruleRepository;
    @Mock
    private RuleReviewTimingRepository timingRepository;

    private RuleReviewTimingService service;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new RuleReviewTimingService(projectService, ruleRepository, timingRepository);
        project = Project.builder().id(UUID.randomUUID()).build();
        lenient().when(projectService.getOwnedProject(project.getId())).thenReturn(project);
    }

    private BusinessRule rule(RuleStatus status, String quote) {
        BusinessRule rule = BusinessRule.builder().id(UUID.randomUUID()).project(project).endpointKey("POST /x").template(RuleTemplate.OR)
                .paramsJson("{}").fingerprint("f").status(status).source(RuleSource.DOC).quote(quote).build();
        when(ruleRepository.findByIdAndProject(rule.getId(), project)).thenReturn(Optional.of(rule));
        return rule;
    }

    @Test
    void record_storesOnlyAnonymousFacts_nothingAboutTheUserOrTheRuleText() {
        BusinessRule rule = rule(RuleStatus.APPROVED, "đoạn trích đủ dài để đối chiếu");

        service.record(project.getId(), rule.getId(), new ReviewTimingRequest("approved", 42_000));

        ArgumentCaptor<RuleReviewTiming> saved = ArgumentCaptor.forClass(RuleReviewTiming.class);
        verify(timingRepository).save(saved.capture());
        assertThat(saved.getValue().getDecision()).isEqualTo("APPROVED");
        assertThat(saved.getValue().getFocusedMs()).isEqualTo(42_000);
        assertThat(saved.getValue().isHadQuote()).isTrue();
        assertThat(saved.getValue().getRuleSource()).isEqualTo("DOC");
        assertThat(saved.getValue().getTemplate()).isEqualTo("OR");
    }

    @Test
    void record_rejectsDecisionThatDoesNotMatchTheRuleState_andAbsurdDurations() {
        BusinessRule proposed = rule(RuleStatus.PROPOSED, null);
        assertThatThrownBy(() -> service.record(project.getId(), proposed.getId(), new ReviewTimingRequest("APPROVED", 1000)))
                .extracting("code").isEqualTo("BUSINESS_RULE_STATE_INVALID");
        assertThatThrownBy(() -> service.record(project.getId(), proposed.getId(), new ReviewTimingRequest("EDITED", -1)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.record(project.getId(), proposed.getId(), new ReviewTimingRequest("EDITED", RuleReviewTimingService.MAX_FOCUSED_MS + 1)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.record(project.getId(), proposed.getId(), new ReviewTimingRequest("HACK", 1000)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.record(project.getId(), proposed.getId(), new ReviewTimingRequest(null, null)))
                .isInstanceOf(InvalidRequestException.class);
        verify(timingRepository, never()).save(any());
    }

    @Test
    void record_unknownRuleOrRuleOfAnotherProject_isRefused() {
        assertThatThrownBy(() -> service.record(project.getId(), UUID.randomUUID(), new ReviewTimingRequest("APPROVED", 1000)))
                .extracting("code").isEqualTo("BUSINESS_RULE_INVALID");
        verify(timingRepository, never()).save(any());
    }

    @Test
    void export_hasNoRuleIdAndNoUser() {
        RuleReviewTiming row = RuleReviewTiming.builder().projectId(project.getId()).ruleId(UUID.randomUUID()).ruleSource("INFERRED").template("OR")
                .hadQuote(false).decision("REJECTED").focusedMs(9000).createdAt(java.time.Instant.now()).build();
        when(timingRepository.findAllByProjectIdOrderByCreatedAtAsc(project.getId())).thenReturn(List.of(row));

        var rows = service.export(project.getId());

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.ruleSource()).isEqualTo("INFERRED");
            assertThat(r.hadQuote()).isFalse();
            assertThat(r.focusedMs()).isEqualTo(9000);
        });
        assertThat(rows.get(0).toString()).doesNotContain(row.getRuleId().toString());
    }
}
