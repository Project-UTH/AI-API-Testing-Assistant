package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.GenerateTestsRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.GenerateTestsResponse;
import com.aiapitesting.backend.entity.BusinessRule;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.RuleStatus;
import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.BusinessRuleRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseAssertionRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.service.ProjectService;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner;
import com.aiapitesting.backend.service.TestCasePathValidator;
import com.aiapitesting.backend.service.TestCaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleTestServiceTest {

    @Mock
    private ProjectService projectService;
    @Mock
    private BusinessRuleRepository ruleRepository;
    @Mock
    private BusinessRuleService ruleService;
    @Mock
    private EndpointRepository endpointRepository;
    @Mock
    private TestCaseRepository testCaseRepository;
    @Mock
    private TestCaseDependencyRepository dependencyRepository;
    @Mock
    private TestCaseAssertionRepository assertionRepository;
    @Mock
    private TestResultRepository testResultRepository;
    @Mock
    private BugReportRepository bugReportRepository;
    @Mock
    private TestCaseService testCaseService;

    private final TestCaseChecklistPlanner planner = new TestCaseChecklistPlanner();
    @Mock
    private com.aiapitesting.backend.repository.GenerationJobRepository generationJobRepository;
    private RuleTestService service;
    private Project project;
    private Endpoint endpoint;
    private TestCase baseline;
    private BusinessRule rule;

    private String schema() throws Exception {
        var props = new java.util.LinkedHashMap<String, Object>();
        props.put("salaryMin", Map.of("type", "integer"));
        props.put("salaryMax", Map.of("type", "integer"));
        var body = Map.of("content", Map.of("application/json", Map.of("schema", Map.of("type", "object", "properties", props))));
        return new ObjectMapper().writeValueAsString(Map.of("requestBody", body, "responseStatusCodes", List.of("201")));
    }

    @BeforeEach
    void setUp() throws Exception {
        project = Project.builder().id(UUID.randomUUID()).build();
        endpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/jobs").schema(schema()).build();
        baseline = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).name("Test gốc").source(TestCaseSource.AI_GENERATED)
                .generationOrigin(TestCaseGenerationOrigin.AI_BASELINE).locked(true).expectedStatus(201).resolvedPath("/jobs")
                .requestBody("{\"salaryMin\":10,\"salaryMax\":20}").requestHeaders("{}").pathParamFallbacks("{}")
                .createdAt(Instant.now()).build();
        rule = BusinessRule.builder().id(UUID.randomUUID()).project(project).endpointKey("POST /jobs").endpointId(endpoint.getId())
                .template(RuleTemplate.COMPARE_FIELDS).paramsJson("{}").status(RuleStatus.APPROVED).source(RuleSource.USER)
                .fingerprint("f").createdAt(Instant.now()).build();
        RuleParams params = new RuleParams("salaryMin", "LTE", "salaryMax", null, null, null, null, null, null, null, null, null, null, null);

        lenient().when(projectService.getOwnedProject(project.getId())).thenReturn(project);
        lenient().when(ruleRepository.findAllByProject(project)).thenReturn(List.of(rule));
        lenient().when(ruleService.read(any())).thenReturn(params);
        lenient().when(ruleService.resolveEndpoint(project, rule)).thenReturn(java.util.Optional.of(endpoint));
        lenient().when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        lenient().when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of(baseline));
        lenient().when(testCaseRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(testResultRepository.findLatestResultsByProject(project)).thenReturn(List.of(latest(baseline.getId(), TestResultStatus.PASSED)));
        service = new RuleTestService(projectService, ruleRepository, ruleService, endpointRepository, testCaseRepository, dependencyRepository,
                assertionRepository, testResultRepository, bugReportRepository, testCaseService, planner,
                new RuleTestGenerator(planner), new TestCasePathValidator(), generationJobRepository);
    }

    private TestResultRepository.TestCaseLatestResult latest(UUID id, TestResultStatus status) {
        return new TestResultRepository.TestCaseLatestResult() {
            public UUID getTestCaseId() {
                return id;
            }

            public TestResultStatus getStatus() {
                return status;
            }

            public com.aiapitesting.backend.entity.FailureCause getFailureCause() {
                return null;
            }

            public Boolean getUnstable() {
                return null;
            }

            public String getRejectionExcerpt() {
                return null;
            }

            public Boolean getSpecDeviation() {
                return null;
            }
        };
    }

    @Test
    void generate_isRefusedWhileABackgroundJobIsActive() {
        when(generationJobRepository.existsByProjectIdAndStatusIn(org.mockito.ArgumentMatchers.eq(project.getId()), any())).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.generate(project.getId(), null))
                .isInstanceOf(com.aiapitesting.backend.exception.ExecutionInProgressException.class);
        verify(testCaseRepository, org.mockito.Mockito.never()).saveAll(any());
    }

    @Test
    void approvedRule_generatesViolationRejectedClassAndBoundary_linkedToRule() {
        GenerateTestsResponse response = service.generate(project.getId(), null);

        assertThat(response.createdCount()).isEqualTo(2);
        ArgumentCaptor<List<TestCase>> captor = ArgumentCaptor.forClass(List.class);
        verify(testCaseRepository).saveAll(captor.capture());
        TestCase violation = captor.getValue().stream().filter(t -> "REJECTED".equals(t.getExpectedStatusClass())).findFirst().orElseThrow();
        assertThat(violation.getExpectedStatus()).isEqualTo(400);
        assertThat(violation.getRequestBody()).contains("\"salaryMin\":21");
        assertThat(violation.getSource()).isEqualTo(TestCaseSource.BUSINESS);
        assertThat(violation.getGenerationOrigin()).isEqualTo(TestCaseGenerationOrigin.RULE_TEMPLATE);
        assertThat(violation.getRuleId()).isEqualTo(rule.getId());
        TestCase boundary = captor.getValue().stream().filter(t -> t.getExpectedStatusClass() == null).findFirst().orElseThrow();
        assertThat(boundary.getExpectedStatus()).isEqualTo(201);
    }

    @Test
    void ruleWithSpecificExpectedStatus_usesThatCodeInsteadOfRejectedClass() {
        rule.setExpectedStatus(422);
        service.generate(project.getId(), null);
        ArgumentCaptor<List<TestCase>> captor = ArgumentCaptor.forClass(List.class);
        verify(testCaseRepository).saveAll(captor.capture());
        TestCase violation = captor.getValue().stream().filter(t -> t.getRequestBody().contains("21")).findFirst().orElseThrow();
        assertThat(violation.getExpectedStatus()).isEqualTo(422);
        assertThat(violation.getExpectedStatusClass()).isNull();
    }

    @Test
    void baselineThatHasNotPassed_isNotUsed_andReasonIsExplained() {
        when(testResultRepository.findLatestResultsByProject(project)).thenReturn(List.of(latest(baseline.getId(), TestResultStatus.FAILED)));

        GenerateTestsResponse response = service.generate(project.getId(), null);

        assertThat(response.createdCount()).isZero();
        assertThat(response.notGenerated()).singleElement().satisfies(n -> assertThat(n.reason()).contains("test gốc"));
        verify(testCaseRepository, never()).saveAll(any());
    }

    @Test
    void unapprovedRule_isSkipped_andReportedOnlyWhenExplicitlyRequested() {
        rule.setStatus(RuleStatus.PROPOSED);
        assertThat(service.generate(project.getId(), null).notGenerated()).isEmpty();
        assertThat(service.generate(project.getId(), new GenerateTestsRequest(List.of(rule.getId()), null)).notGenerated())
                .singleElement().satisfies(n -> assertThat(n.reason()).contains("chưa được duyệt"));
        verify(testCaseRepository, never()).saveAll(any());
    }

    @Test
    void regenerating_deletesOnlyUnlockedOldTestsOfThatRule() {
        TestCase oldOne = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).ruleId(rule.getId()).build();
        TestCase lockedOne = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).ruleId(rule.getId()).locked(true).build();
        when(testCaseRepository.findAllByRuleId(rule.getId())).thenReturn(List.of(oldOne, lockedOne));

        service.generate(project.getId(), null);

        verify(testCaseRepository).deleteAll(List.of(oldOne));
        verify(testResultRepository).deleteAllByTestCaseIn(List.of(oldOne));
    }

    @Test
    void uniqueTokens_becomeNamedSoBothSendsUseTheSameValue() {
        assertThat(RuleTestService.nameUniqueTokens("{\"email\":\"{{uuid}}@x.com\",\"n\":\"{{rand}}\",\"k\":\"{{uuid:a}}\"}"))
                .isEqualTo("{\"email\":\"{{uuid:dup}}@x.com\",\"n\":\"{{rand:dup}}\",\"k\":\"{{uuid:a}}\"}");
    }
}
