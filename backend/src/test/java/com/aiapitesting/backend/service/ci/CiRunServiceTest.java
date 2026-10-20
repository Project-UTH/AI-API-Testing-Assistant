package com.aiapitesting.backend.service.ci;

import com.aiapitesting.backend.dto.request.CiRunRequest;
import com.aiapitesting.backend.dto.response.CiExecutionResponse;
import com.aiapitesting.backend.dto.response.CiRunResponse;
import com.aiapitesting.backend.dto.response.TestExecutionResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.exception.ExecutionInProgressException;
import com.aiapitesting.backend.exception.ForbiddenException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.TestExecutionNotFoundException;
import com.aiapitesting.backend.exception.TooManyRequestsException;
import com.aiapitesting.backend.repository.ProjectRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.security.CiPrincipal;
import com.aiapitesting.backend.service.execution.TestExecutionService;
import com.aiapitesting.backend.service.report.ExecutionConclusionCalculator.Conclusion;
import com.aiapitesting.backend.service.report.ExecutionConclusionCalculator.FailOn;
import com.aiapitesting.backend.service.report.ExecutionReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CiRunServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private TestCaseRepository testCaseRepository;
    @Mock
    private TestExecutionRepository testExecutionRepository;
    @Mock
    private TestResultRepository testResultRepository;
    @Mock
    private TestExecutionService testExecutionService;
    @Mock
    private ExecutionReportService executionReportService;
    @Mock
    private CiRateLimiter rateLimiter;

    private CiRunService service;
    private Project project;
    private UUID projectId;
    private CiPrincipal principal;

    @BeforeEach
    void setUp() {
        service = new CiRunService(projectRepository, testCaseRepository, testExecutionRepository, testResultRepository,
                testExecutionService, executionReportService, rateLimiter);
        projectId = UUID.randomUUID();
        project = Project.builder().id(projectId).build();
        principal = new CiPrincipal(UUID.randomUUID(), projectId);
        lenient().when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        lenient().when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        lenient().when(testExecutionRepository.existsByProjectAndStatusIn(any(), any())).thenReturn(false);
    }

    private TestCase testCase(boolean endpointMissing) {
        Endpoint endpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/x").missingFromSpec(endpointMissing).build();
        return TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).name("t").expectedStatus(200).build();
    }

    private void stubTrigger() {
        TestExecution started = TestExecution.builder().id(UUID.randomUUID()).status(ExecutionStatus.PENDING).startedAt(Instant.now()).build();
        when(testExecutionService.triggerForProject(any(), anyList(), any(), any()))
                .thenReturn(TestExecutionResponse.pending(started, List.of()));
    }

    // ---- quyền ----

    @Test
    void start_urlProjectDifferentFromTokenProject_forbidden_andNothingRuns() {
        assertThatThrownBy(() -> service.start(principal, UUID.randomUUID(), null)).isInstanceOf(ForbiddenException.class);
        verify(testExecutionService, never()).triggerForProject(any(), anyList(), any(), any());
    }

    @Test
    void status_executionOfAnotherProject_isNotFound_notForbidden() {
        UUID executionId = UUID.randomUUID();
        when(testExecutionRepository.findByIdAndProjectId(executionId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.status(principal, projectId, executionId, null)).isInstanceOf(TestExecutionNotFoundException.class);
    }

    @Test
    void status_projectInUrlDifferentFromToken_forbidden() {
        assertThatThrownBy(() -> service.status(principal, UUID.randomUUID(), UUID.randomUUID(), null)).isInstanceOf(ForbiddenException.class);
    }

    // ---- khởi chạy ----

    @Test
    void start_defaultScopeRunsEveryTestCaseExceptThoseOfRemovedEndpoints_andRecordsTokenAndFailOn() {
        TestCase kept = testCase(false);
        TestCase removed = testCase(true);
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(kept, removed));
        stubTrigger();

        CiRunResponse response = service.start(principal, projectId, null);

        ArgumentCaptor<List<UUID>> ids = ArgumentCaptor.forClass(List.class);
        verify(testExecutionService).triggerForProject(eq(project), ids.capture(), eq(principal.tokenId()), eq("API_FAIL"));
        assertThat(ids.getValue()).containsExactly(kept.getId());
        assertThat(response.selectedTestCaseCount()).isEqualTo(1);
        assertThat(response.excludedTestCaseCount()).isEqualTo(1);
        assertThat(response.statusUrl()).isEqualTo("/api/v1/ci/executions/" + response.executionId());
        assertThat(response.reportUrl()).endsWith("/report");
    }

    @Test
    void start_endpointsScope_selectsOnlyTheirTestCases() {
        TestCase a = testCase(false);
        TestCase b = testCase(false);
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(a, b));
        stubTrigger();

        service.start(principal, projectId, new CiRunRequest(CiRunRequest.Scope.ENDPOINTS, null, List.of(a.getEndpoint().getId()), FailOn.ANY_FAIL));

        ArgumentCaptor<List<UUID>> ids = ArgumentCaptor.forClass(List.class);
        verify(testExecutionService).triggerForProject(eq(project), ids.capture(), any(), eq("ANY_FAIL"));
        assertThat(ids.getValue()).containsExactly(a.getId());
    }

    @Test
    void start_scopesNeedTheirIds_andEmptySuiteIsRejected() {
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of());

        assertThatThrownBy(() -> service.start(principal, projectId, new CiRunRequest(CiRunRequest.Scope.TEST_CASES, List.of(), null, null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.start(principal, projectId, new CiRunRequest(CiRunRequest.Scope.ENDPOINTS, null, null, null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.start(principal, projectId, null)).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Không có test case");
    }

    @Test
    void start_whileAnotherRunIsInProgress_conflict() {
        when(testExecutionRepository.existsByProjectAndStatusIn(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.start(principal, projectId, null)).isInstanceOf(ExecutionInProgressException.class);
        verify(testExecutionService, never()).triggerForProject(any(), anyList(), any(), any());
    }

    @Test
    void start_overRateLimit_isRejected() {
        when(rateLimiter.tryAcquire(eq("ci-run:" + principal.tokenId()), anyInt())).thenReturn(false);

        assertThatThrownBy(() -> service.start(principal, projectId, null)).isInstanceOf(TooManyRequestsException.class);
    }

    // ---- trạng thái / kết luận ----

    @Test
    void status_usesTheFailOnChosenAtStart_andAllowsOverride() {
        UUID executionId = UUID.randomUUID();
        TestExecution execution = TestExecution.builder().id(executionId).project(project).status(ExecutionStatus.COMPLETED)
                .startedAt(Instant.now()).ciFailOn("ANY_FAIL").build();
        when(testExecutionRepository.findByIdAndProjectId(executionId, projectId)).thenReturn(Optional.of(execution));
        TestResult tokenFail = reportResult(TestResultStatus.FAILED, FailureCause.AUTH_CONFIG);
        when(testResultRepository.findAllByExecutionOrderByTestCaseCreatedAt(execution)).thenReturn(List.of(tokenFail));

        CiExecutionResponse strict = service.status(principal, projectId, executionId, null);
        CiExecutionResponse lenient = service.status(principal, projectId, executionId, FailOn.API_FAIL);

        assertThat(strict.failOn()).isEqualTo(FailOn.ANY_FAIL);
        assertThat(strict.conclusion()).isEqualTo(Conclusion.FAILED);
        assertThat(lenient.conclusion()).isEqualTo(Conclusion.PASSED);
        assertThat(lenient.warnings()).isNotEmpty();
    }

    private TestResult reportResult(TestResultStatus status, FailureCause cause) {
        TestCase tc = testCase(false);
        return TestResult.builder().id(UUID.randomUUID()).testCase(tc).status(status).failureCause(cause).build();
    }
}
