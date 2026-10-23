package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.ApproveRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.CreateRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.RejectRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.RuleResponse;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.UpdateRequest;
import com.aiapitesting.backend.entity.BusinessRule;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RuleRejectReason;
import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.RuleStatus;
import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.BusinessRuleException;
import com.aiapitesting.backend.repository.BusinessRuleRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.service.CurrentUserService;
import com.aiapitesting.backend.service.ProjectService;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.InjectMocks;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
class BusinessRuleServiceTest {

    @Mock
    private ProjectService projectService;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private EndpointRepository endpointRepository;
    @Mock
    private BusinessRuleRepository ruleRepository;
    @Mock
    private TestCaseRepository testCaseRepository;
    @Spy
    private TestCaseChecklistPlanner planner = new TestCaseChecklistPlanner();
    @InjectMocks
    private BusinessRuleService service;

    private Project project;
    private Endpoint endpoint;
    private final User user = User.builder().id(UUID.randomUUID()).email("qa@x.com").build();

    /** Body {salaryMin, salaryMax: integer; years: integer 0..50}. */
    private String schema() {
        try {
            var props = new java.util.LinkedHashMap<String, Object>();
            props.put("salaryMin", Map.of("type", "integer"));
            props.put("salaryMax", Map.of("type", "integer"));
            props.put("years", Map.of("type", "integer", "minimum", 0, "maximum", 50));
            var body = Map.of("content", Map.of("application/json", Map.of("schema", Map.of("type", "object", "properties", props))));
            return new ObjectMapper().writeValueAsString(Map.of("requestBody", body, "responseStatusCodes", List.of("201")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        project = Project.builder().id(UUID.randomUUID()).build();
        endpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/jobs").schema(schema()).build();
        lenient().when(projectService.getOwnedProject(project.getId())).thenReturn(project);
        lenient().when(currentUserService.getCurrentUser()).thenReturn(user);
        lenient().when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(Optional.of(endpoint));
        lenient().when(ruleRepository.save(any(BusinessRule.class))).thenAnswer(i -> i.getArgument(0));
    }

    private RuleParams compare() {
        return new RuleParams("salaryMin", "lte", "salaryMax", null, null, null, null, null, null, null, null, null, null, null);
    }

    private BusinessRule stored(RuleStatus status, RuleSource source) {
        BusinessRule rule = BusinessRule.builder().id(UUID.randomUUID()).project(project).endpointKey("POST /jobs").endpointId(endpoint.getId())
                .template(RuleTemplate.COMPARE_FIELDS).paramsJson("{\"left\":\"salaryMin\",\"op\":\"LTE\",\"right\":\"salaryMax\"}")
                .status(status).source(source).fingerprint("f").build();
        when(ruleRepository.findByIdAndProject(rule.getId(), project)).thenReturn(Optional.of(rule));
        return rule;
    }

    @Test
    void create_validRule_isProposedWithSentenceAndStableFingerprint() {
        RuleResponse response = service.create(project.getId(), new CreateRequest(endpoint.getId(), RuleTemplate.COMPARE_FIELDS, compare(), null, "lương", null));

        assertThat(response.status()).isEqualTo(RuleStatus.PROPOSED);
        assertThat(response.source()).isEqualTo(RuleSource.USER);
        assertThat(response.sentence()).isEqualTo("salaryMin ≤ salaryMax");
        assertThat(response.endpointKey()).isEqualTo("POST /jobs");
    }

    @Test
    void create_withApprove_marksApprovedByCurrentUser() {
        RuleResponse response = service.create(project.getId(), new CreateRequest(endpoint.getId(), RuleTemplate.COMPARE_FIELDS, compare(), 422, null, true));
        assertThat(response.status()).isEqualTo(RuleStatus.APPROVED);
        assertThat(response.reviewedBy()).isEqualTo(user.getId());
        assertThat(response.expectedStatus()).isEqualTo(422);
    }

    @Test
    void create_userRuleThatRepeatsSpecIsRejectedWith409_notSilentlyStored() {
        RuleParams same = new RuleParams(null, null, null, "years", null, null, null, null, BigDecimal.ZERO, new BigDecimal(50), null, null, null, null);
        assertThatThrownBy(() -> service.create(project.getId(), new CreateRequest(endpoint.getId(), RuleTemplate.VALUE_RANGE, same, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("BUSINESS_RULE_DUPLICATES_SPEC");
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void create_sameRuleTwice_isAlreadyExists_andExpectedStatusMustBe4xx() {
        when(ruleRepository.existsByProjectAndFingerprint(any(), any())).thenReturn(true);
        assertThatThrownBy(() -> service.create(project.getId(), new CreateRequest(endpoint.getId(), RuleTemplate.COMPARE_FIELDS, compare(), null, null, null)))
                .extracting("code").isEqualTo("BUSINESS_RULE_ALREADY_EXISTS");
        assertThatThrownBy(() -> service.create(project.getId(), new CreateRequest(endpoint.getId(), RuleTemplate.COMPARE_FIELDS, compare(), 200, null, null)))
                .extracting("code").isEqualTo("BUSINESS_RULE_INVALID");
    }

    @Test
    void approve_inferredRuleNeedsAnExplicitExpectedStatus_citedRuleDoesNot() {
        BusinessRule guess = stored(RuleStatus.PROPOSED, RuleSource.INFERRED);
        assertThatThrownBy(() -> service.approve(project.getId(), guess.getId(), null)).extracting("code").isEqualTo("BUSINESS_RULE_STATE_INVALID");
        assertThatThrownBy(() -> service.approve(project.getId(), guess.getId(), new ApproveRequest(null, null)))
                .extracting("code").isEqualTo("BUSINESS_RULE_STATE_INVALID");
        assertThat(service.approve(project.getId(), guess.getId(), new ApproveRequest(422, null)).status()).isEqualTo(RuleStatus.APPROVED);

        BusinessRule cited = stored(RuleStatus.PROPOSED, RuleSource.DOC);
        assertThat(service.approve(project.getId(), cited.getId(), null).status()).isEqualTo(RuleStatus.APPROVED);
    }

    @Test
    void approveBatch_approvesOnlyCitedProposedRules_andExplainsTheRest() {
        BusinessRule cited = stored(RuleStatus.PROPOSED, RuleSource.DOC);
        cited.setQuote("đoạn trích nguyên văn đủ dài");
        BusinessRule noQuote = stored(RuleStatus.PROPOSED, RuleSource.DOC);
        BusinessRule guess = stored(RuleStatus.PROPOSED, RuleSource.INFERRED);
        guess.setQuote("có trích dẫn nhưng là luật đoán");
        BusinessRule conflict = stored(RuleStatus.CONFLICT, RuleSource.DOC);
        conflict.setQuote("đoạn trích nguyên văn đủ dài");
        UUID unknown = UUID.randomUUID();

        var response = service.approveBatch(project.getId(), new com.aiapitesting.backend.dto.rule.BusinessRuleDtos.ApproveBatchRequest(
                List.of(cited.getId(), noQuote.getId(), guess.getId(), conflict.getId(), unknown, cited.getId())));

        assertThat(response.approvedIds()).containsExactly(cited.getId());
        assertThat(response.notApproved()).extracting("ruleId").containsExactlyInAnyOrder(noQuote.getId(), guess.getId(), conflict.getId(), unknown);
        assertThat(cited.getStatus()).isEqualTo(RuleStatus.APPROVED);
        assertThat(guess.getStatus()).isEqualTo(RuleStatus.PROPOSED);
        assertThat(conflict.getStatus()).isEqualTo(RuleStatus.CONFLICT);
        assertThatThrownBy(() -> service.approveBatch(project.getId(), null)).isInstanceOf(com.aiapitesting.backend.exception.InvalidRequestException.class);
    }

    @Test
    void approve_conflictRuleNeedsNote_andOnlyProposedOrConflictCanBeApproved() {
        BusinessRule conflict = stored(RuleStatus.CONFLICT, RuleSource.INFERRED);
        assertThatThrownBy(() -> service.approve(project.getId(), conflict.getId(), new ApproveRequest(null, " ")))
                .extracting("code").isEqualTo("BUSINESS_RULE_STATE_INVALID");
        RuleResponse approved = service.approve(project.getId(), conflict.getId(), new ApproveRequest(400, "tài liệu mới hơn spec"));
        assertThat(approved.status()).isEqualTo(RuleStatus.APPROVED);
        assertThat(approved.statusNote()).contains("tài liệu mới hơn spec");

        BusinessRule rejected = stored(RuleStatus.REJECTED, RuleSource.USER);
        assertThatThrownBy(() -> service.approve(project.getId(), rejected.getId(), null)).extracting("code").isEqualTo("BUSINESS_RULE_STATE_INVALID");
    }

    @Test
    void reject_requiresReason_keepsTheRule_andRecordsReviewer() {
        BusinessRule rule = stored(RuleStatus.PROPOSED, RuleSource.SYSTEM_DETECTED);
        assertThatThrownBy(() -> service.reject(project.getId(), rule.getId(), new RejectRequest(null, null))).hasMessageContaining("lý do");
        assertThatThrownBy(() -> service.reject(project.getId(), rule.getId(), new RejectRequest(RuleRejectReason.DUPLICATE_OF_SPEC, null)))
                .hasMessageContaining("lý do");

        RuleResponse response = service.reject(project.getId(), rule.getId(), new RejectRequest(RuleRejectReason.WRONG_MEANING, "sai nghĩa"));
        assertThat(response.status()).isEqualTo(RuleStatus.REJECTED);
        assertThat(response.rejectReason()).isEqualTo(RuleRejectReason.WRONG_MEANING);
        assertThat(response.reviewedBy()).isEqualTo(user.getId());
        verify(ruleRepository, never()).delete(any(BusinessRule.class));
    }

    @Test
    void delete_onlyUserRulesThatWereNeverReviewed() {
        BusinessRule fresh = stored(RuleStatus.PROPOSED, RuleSource.USER);
        service.delete(project.getId(), fresh.getId());
        verify(ruleRepository).delete(fresh);

        BusinessRule detected = stored(RuleStatus.PROPOSED, RuleSource.SYSTEM_DETECTED);
        assertThatThrownBy(() -> service.delete(project.getId(), detected.getId())).extracting("code").isEqualTo("BUSINESS_RULE_NOT_DELETABLE");
        BusinessRule reviewed = stored(RuleStatus.APPROVED, RuleSource.USER);
        reviewed.setReviewedAt(java.time.Instant.now());
        assertThatThrownBy(() -> service.delete(project.getId(), reviewed.getId())).extracting("code").isEqualTo("BUSINESS_RULE_NOT_DELETABLE");
    }

    @Test
    void update_sendsApprovedRuleBackToProposed_andClearsReview() {
        BusinessRule rule = stored(RuleStatus.APPROVED, RuleSource.USER);
        rule.setReviewedBy(user.getId());
        rule.setReviewedAt(java.time.Instant.now());
        RuleParams changed = new RuleParams("salaryMin", "LT", "salaryMax", null, null, null, null, null, null, null, null, null, null, null);

        RuleResponse response = service.update(project.getId(), rule.getId(), new UpdateRequest(changed, null, "đổi sang <"));

        assertThat(response.status()).isEqualTo(RuleStatus.PROPOSED);
        assertThat(response.reviewedAt()).isNull();
        assertThat(response.sentence()).isEqualTo("salaryMin < salaryMax");
    }

    @Test
    void detect_proposesOneCompareRuleForEachMinMaxPair_andRerunCreatesNoDuplicates() {
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));

        var first = service.detect(project.getId());

        assertThat(first.proposedCount()).isEqualTo(1);
        org.mockito.ArgumentCaptor<BusinessRule> saved = org.mockito.ArgumentCaptor.forClass(BusinessRule.class);
        verify(ruleRepository).save(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo(RuleSource.SYSTEM_DETECTED);
        assertThat(saved.getValue().getStatus()).isEqualTo(RuleStatus.PROPOSED);
        assertThat(saved.getValue().getParamsJson()).contains("salaryMin").contains("salaryMax").contains("LTE");

        when(ruleRepository.existsByProjectAndFingerprint(any(), any())).thenReturn(true);
        var second = service.detect(project.getId());
        assertThat(second.proposedCount()).isZero();
        assertThat(second.skippedExistingCount()).isEqualTo(1);
    }

    @Test
    void fieldsOf_listsSpecFieldsWithRefsForTheForm() {
        var fields = service.fieldsOf(project.getId(), endpoint.getId());
        assertThat(fields).extracting(f -> f.ref()).contains("salaryMin", "salaryMax", "years");
        assertThat(fields).filteredOn(f -> f.ref().equals("years")).singleElement().satisfies(f -> {
            assertThat(f.numeric()).isTrue();
            assertThat(f.maximum()).isEqualByComparingTo("50");
        });
    }

    @Test
    void relinkOnList_reconnectsRuleToNewEndpointIdByKey_andMarksRemovedEndpointStale() {
        BusinessRule moved = BusinessRule.builder().id(UUID.randomUUID()).project(project).endpointKey("POST /jobs").endpointId(UUID.randomUUID())
                .template(RuleTemplate.COMPARE_FIELDS).paramsJson("{}").status(RuleStatus.PROPOSED).source(RuleSource.USER).fingerprint("a").build();
        BusinessRule gone = BusinessRule.builder().id(UUID.randomUUID()).project(project).endpointKey("DELETE /old").endpointId(UUID.randomUUID())
                .template(RuleTemplate.COMPARE_FIELDS).paramsJson("{}").status(RuleStatus.APPROVED).source(RuleSource.USER).fingerprint("b").build();
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(ruleRepository.findAllByProject(project)).thenReturn(List.of(moved, gone));
        when(endpointRepository.findByIdAndProject(any(), any())).thenReturn(Optional.empty());
        when(endpointRepository.findByProjectAndPathAndMethod(project, "/jobs", "POST")).thenReturn(Optional.of(endpoint));
        when(endpointRepository.findByProjectAndPathAndMethod(project, "/old", "DELETE")).thenReturn(Optional.empty());

        service.relink(project);

        assertThat(moved.getEndpointId()).isEqualTo(endpoint.getId());
        assertThat(moved.getStatus()).isEqualTo(RuleStatus.PROPOSED);
        assertThat(gone.getEndpointId()).isNull();
        assertThat(gone.getStatus()).isEqualTo(RuleStatus.STALE);
    }

    @Test
    void unknownRule_is404() {
        assertThatThrownBy(() -> service.get(project.getId(), UUID.randomUUID())).extracting("code").isEqualTo("BUSINESS_RULE_NOT_FOUND");
    }
}
