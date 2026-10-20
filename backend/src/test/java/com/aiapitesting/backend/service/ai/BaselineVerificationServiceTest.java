package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.request.BaselineRequest;
import com.aiapitesting.backend.dto.request.CoverageFillRequest;
import com.aiapitesting.backend.dto.response.BaselineResponse;
import com.aiapitesting.backend.dto.response.CoverageFillResponse;
import com.aiapitesting.backend.dto.response.TestExecutionResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.service.CoverageGapFillService;
import com.aiapitesting.backend.service.ProjectService;
import com.aiapitesting.backend.service.TestCaseService;
import com.aiapitesting.backend.service.ai.BaselineVerificationService.Decision;
import com.aiapitesting.backend.service.ai.BaselineVerificationService.RunOutcome;
import com.aiapitesting.backend.service.execution.TestExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI sinh test gốc + chạy thử xác nhận: mô phỏng AI và runner, kiểm tra vòng sửa, các điểm dừng và an toàn. */
class BaselineVerificationServiceTest {

    private final ProjectService projectService = mock(ProjectService.class);
    private final EndpointRepository endpointRepository = mock(EndpointRepository.class);
    private final TestCaseGenerationService generation = mock(TestCaseGenerationService.class);
    private final CoverageGapFillService gapFill = mock(CoverageGapFillService.class);
    private final TestExecutionService executionService = mock(TestExecutionService.class);
    private final TestExecutionRepository executionRepository = mock(TestExecutionRepository.class);
    private final TestResultRepository resultRepository = mock(TestResultRepository.class);
    private final TestCaseService testCaseService = mock(TestCaseService.class);
    private final com.aiapitesting.backend.service.DependencySuggestionService dependencySuggestion = mock(com.aiapitesting.backend.service.DependencySuggestionService.class);
    private BaselineVerificationService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID endpointId = UUID.randomUUID();
    private final UUID testId = UUID.randomUUID();
    private Endpoint endpoint;
    private int askCount;
    private String proposedPath = "/jobs";

    private static GeneratedTestCase test(String path) {
        return new GeneratedTestCase("Positive - Test gốc hợp lệ", "d", Map.of(), "{\"a\":1}", 200, path, Map.of("id", "1"), null, "r");
    }

    @BeforeEach
    void setUp() {
        service = new BaselineVerificationService(projectService, endpointRepository, generation, gapFill, executionService,
                executionRepository, resultRepository, testCaseService, dependencySuggestion, 1000, 1);
        Project project = Project.builder().id(projectId).build();
        endpoint = Endpoint.builder().id(endpointId).method("POST").path("/jobs").build();
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        when(gapFill.hasConfirmedBaseline(projectId, endpointId)).thenReturn(false);
        when(gapFill.fill(eq(projectId), eq(endpointId), any(CoverageFillRequest.class)))
                .thenReturn(new CoverageFillResponse(18, List.of(), List.of(), null, 0));
        when(generation.saveBaselineTest(any(), any(), any(), any())).thenReturn(testId);
        when(generation.askBaseline(any(), any(), any())).thenAnswer(inv -> {
            askCount++;
            return new TestCaseGenerationService.BaselineProposal(test(proposedPath), 5000);
        });
        TestExecution execution = TestExecution.builder().id(UUID.randomUUID()).status(ExecutionStatus.COMPLETED).build();
        when(executionService.triggerForProject(any(), any(), any(), any()))
                .thenReturn(new TestExecutionResponse(execution.getId(), ExecutionStatus.PENDING, null, null, List.of(), List.of()));
        when(executionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
    }

    private void results(TestResultStatus status, Integer http, String body) {
        TestResult r = TestResult.builder().testCase(TestCase.builder().id(testId).build()).status(status).responseStatus(http).responseBody(body).build();
        when(resultRepository.findAllByExecutionOrderByTestCaseCreatedAt(any())).thenReturn(List.of(r));
    }

    private BaselineRequest confirmed(Integer rounds) {
        return new BaselineRequest(true, rounds, null, true, false);
    }

    @Test
    void passOnFirstAttempt_confirmsLocksAndFillsChecklist_withOneAiCall() {
        results(TestResultStatus.PASSED, 201, "{\"id\":1}");

        BaselineResponse response = service.execute(projectId, endpointId, confirmed(2));

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.baselineTestCaseId()).isEqualTo(testId);
        assertThat(response.filledCount()).isEqualTo(18);
        assertThat(response.tokensUsed()).isEqualTo(5000);
        assertThat(askCount).isEqualTo(1);
        verify(testCaseService).setLocked(projectId, endpointId, testId, true);
    }

    @Test
    void runWithAutoIncludedDependency_gradesTheBaselineOwnResult_notTheFirstResult() {
        // Lần chạy có kèm POST tạo tài nguyên chạy trước (Passed 201): kết quả đầu tiên KHÔNG phải của test gốc. Test gốc thật sự trả 404.
        TestResult dependency = TestResult.builder().testCase(TestCase.builder().id(UUID.randomUUID()).build())
                .status(TestResultStatus.PASSED).responseStatus(201).responseBody("{}").build();
        TestResult own = TestResult.builder().testCase(TestCase.builder().id(testId).build())
                .status(TestResultStatus.FAILED).responseStatus(404).responseBody("{\"error\":\"NOT_FOUND\"}").build();
        when(resultRepository.findAllByExecutionOrderByTestCaseCreatedAt(any())).thenReturn(List.of(dependency, own));

        BaselineResponse response = service.execute(projectId, endpointId, confirmed(0));

        assertThat(response.status()).isEqualTo("NEEDS_HUMAN");
        assertThat(response.status()).isNotEqualTo("CONFIRMED");
        verify(testCaseService, org.mockito.Mockito.never()).setLocked(projectId, endpointId, testId, true);
    }

    @Test
    void repeated409_stopsAsStateConflict_insteadOfBurningAllRepairRounds() {
        results(TestResultStatus.FAILED, 409, "{\"error\":\"COMPANY_ALREADY_EXISTS\"}");

        BaselineResponse response = service.execute(projectId, endpointId, confirmed(3));

        assertThat(response.status()).isEqualTo("NEEDS_HUMAN");
        assertThat(response.reason()).isEqualTo("STATE_CONFLICT");
        assertThat(askCount).isEqualTo(2); // 1 lần sinh + đúng 1 lần sửa, không đốt hết 3 lần sửa
    }

    @Test
    void failThenPassOnRepair_sendsTheRealApiErrorBackToAi() {
        TestResult bad = TestResult.builder().testCase(TestCase.builder().id(testId).build()).status(TestResultStatus.FAILED).responseStatus(400).responseBody("{\"error\":\"INVALID_CATALOG_CODE\"}").build();
        TestResult good = TestResult.builder().testCase(TestCase.builder().id(testId).build()).status(TestResultStatus.PASSED).responseStatus(201).responseBody("{}").build();
        when(resultRepository.findAllByExecutionOrderByTestCaseCreatedAt(any())).thenReturn(List.of(bad), List.of(good));

        BaselineResponse response = service.execute(projectId, endpointId, confirmed(2));

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.rounds()).hasSize(2);
        assertThat(askCount).isEqualTo(2);
        ArgumentCaptor<TestCaseGenerationService.BaselineAttempt> attempt = ArgumentCaptor.forClass(TestCaseGenerationService.BaselineAttempt.class);
        verify(generation, times(2)).askBaseline(any(), any(), attempt.capture());
        assertThat(attempt.getAllValues().get(0)).isNull();
        assertThat(attempt.getAllValues().get(1).httpStatus()).isEqualTo(400);
        assertThat(attempt.getAllValues().get(1).maskedBody()).contains("INVALID_CATALOG_CODE");
    }

    @Test
    void repairRoundsExhausted_stopsAsNeedsHuman_withBoundedAiCalls() {
        results(TestResultStatus.FAILED, 422, "{\"errors\":[\"bad\"]}");

        BaselineResponse response = service.execute(projectId, endpointId, confirmed(1));

        assertThat(response.status()).isEqualTo("NEEDS_HUMAN");
        assertThat(response.reason()).isEqualTo("REPAIR_EXHAUSTED");
        assertThat(askCount).isEqualTo(2);
        verify(testCaseService, never()).setLocked(any(), any(), any(), anyBoolean());
    }

    @Test
    void notFoundWithPathParam_stopsAtOnce_askingForARealId_withoutSpendingRepairTokens() {
        proposedPath = "/jobs/{{id}}";
        results(TestResultStatus.FAILED, 404, "{\"error\":\"NOT_FOUND\"}");

        BaselineResponse response = service.execute(projectId, endpointId, confirmed(3));

        assertThat(response.status()).isEqualTo("NEEDS_HUMAN");
        assertThat(response.reason()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(askCount).isEqualTo(1);
    }

    @Test
    void authFailureAndServerError_neverTriggerAiRepair() {
        results(TestResultStatus.FAILED, 403, "{}");
        assertThat(service.execute(projectId, endpointId, confirmed(3)).reason()).isEqualTo("AUTH");
        results(TestResultStatus.FAILED, 500, "{}");
        assertThat(service.execute(projectId, endpointId, confirmed(3)).reason()).isEqualTo("SERVER_ERROR");
        assertThat(askCount).isEqualTo(2);
    }

    @Test
    void success2xxWithDifferentCode_isAdoptedBySystem_withoutAnotherAiCall() {
        TestResult mismatch = TestResult.builder().testCase(TestCase.builder().id(testId).build()).status(TestResultStatus.FAILED).responseStatus(201).responseBody("{}").build();
        TestResult passed = TestResult.builder().testCase(TestCase.builder().id(testId).build()).status(TestResultStatus.PASSED).responseStatus(201).responseBody("{}").build();
        when(resultRepository.findAllByExecutionOrderByTestCaseCreatedAt(any())).thenReturn(List.of(mismatch), List.of(passed));

        BaselineResponse response = service.execute(projectId, endpointId, confirmed(2));

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(askCount).isEqualTo(1);
        verify(generation).setBaselineExpectedStatus(projectId, endpointId, testId, 201);
    }

    @Test
    void writeMethodWithoutConfirmation_isRejected_beforeAnyAiCallOrRun() {
        assertThatThrownBy(() -> service.execute(projectId, endpointId, new BaselineRequest(false, 2, null, true, false)))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(askCount).isZero();
        verify(executionService, never()).triggerForProject(any(), any(), any(), any());
    }

    @Test
    void getEndpoint_needsNoConfirmation_andExistingPassedBaselineSkipsAi() {
        endpoint.setMethod("GET");
        when(gapFill.hasConfirmedBaseline(projectId, endpointId)).thenReturn(true);

        BaselineResponse response = service.execute(projectId, endpointId, new BaselineRequest(false, 2, null, true, false));

        assertThat(response.status()).isEqualTo("ALREADY_HAS_BASELINE");
        assertThat(response.filledCount()).isEqualTo(18);
        assertThat(askCount).isZero();
    }

    @Test
    void diagnosis_mapsEachOutcomeToTheRightNextStep() {
        assertThat(BaselineVerificationService.diagnose(new RunOutcome(TestResultStatus.PASSED, 200, null, null, false), false).decision()).isEqualTo(Decision.PASSED);
        assertThat(BaselineVerificationService.diagnose(new RunOutcome(TestResultStatus.FAILED, 400, "x", null, false), false).decision()).isEqualTo(Decision.REPAIR);
        assertThat(BaselineVerificationService.diagnose(new RunOutcome(TestResultStatus.FAILED, 409, "x", null, false), false).decision()).isEqualTo(Decision.REPAIR);
        assertThat(BaselineVerificationService.diagnose(new RunOutcome(TestResultStatus.FAILED, 404, "x", null, false), false).decision()).isEqualTo(Decision.REPAIR);
        assertThat(BaselineVerificationService.diagnose(new RunOutcome(TestResultStatus.FAILED, 404, "x", null, false), true).reason()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(BaselineVerificationService.diagnose(new RunOutcome(TestResultStatus.ERROR, null, null, "Thiếu giá trị cho tham số id", false), true).reason()).isEqualTo("MISSING_PARAM_VALUE");
        assertThat(BaselineVerificationService.diagnose(new RunOutcome(null, null, null, null, true), false).reason()).isEqualTo("EXECUTION_TIMEOUT");
    }

    @Test
    void userSuppliedPathValues_overrideTheAiGuess() {
        GeneratedTestCase merged = BaselineVerificationService.withUserValues(test("/jobs/{{id}}"), Map.of("id", "REAL-ID"));
        assertThat(merged.pathParamFallbacks()).containsEntry("id", "REAL-ID");
        assertThat(BaselineVerificationService.withUserValues(test("/jobs"), Map.of())).isEqualTo(test("/jobs"));
    }

    @Test
    void pathParamWithoutValueOrChainSource_asksTheHumanFirst_withoutSpendingAnyToken() {
        endpoint.setPath("/companies/{id}");
        when(dependencySuggestion.hasCreatorSource(projectId, endpointId, "id")).thenReturn(false);

        BaselineResponse response = service.execute(projectId, endpointId, confirmed(2));

        assertThat(response.status()).isEqualTo("NEEDS_HUMAN");
        assertThat(response.reason()).isEqualTo("MISSING_PARAM_VALUE");
        assertThat(response.tokensUsed()).isZero();
        assertThat(askCount).isZero();
    }

    @Test
    void pathParamWithUserValueOrChainSource_proceedsToAi() {
        endpoint.setPath("/companies/{id}");
        results(TestResultStatus.PASSED, 200, "{}");
        when(dependencySuggestion.hasCreatorSource(projectId, endpointId, "id")).thenReturn(true);
        assertThat(service.execute(projectId, endpointId, confirmed(2)).status()).isEqualTo("CONFIRMED");

        when(dependencySuggestion.hasCreatorSource(projectId, endpointId, "id")).thenReturn(false);
        BaselineRequest withValue = new BaselineRequest(true, 2, Map.of("id", "REAL"), true, false);
        assertThat(service.execute(projectId, endpointId, withValue).status()).isEqualTo("CONFIRMED");
    }

    // ---- Giám sát từng bước, chạy thử, huỷ và trần token ----

    /** Ghi lại các bước để kiểm tra; có thể cấu hình huỷ sau N lần kiểm tra và trần token. */
    private static class Recording implements com.aiapitesting.backend.service.job.JobProgress {
        final java.util.List<String> phases = new java.util.ArrayList<>();
        final boolean dryRun;
        final int cap;
        int cancelAfterChecks = Integer.MAX_VALUE;
        int checks;
        int tokens;

        Recording(boolean dryRun, int cap) {
            this.dryRun = dryRun;
            this.cap = cap;
        }

        @Override
        public Step start(UUID endpointId, String endpointLabel, String phase, String actor, String title) {
            phases.add(phase + ":" + actor);
            return (status, detail, httpStatus, t) -> { };
        }

        @Override
        public void checkCancelled() {
            if (++checks > cancelAfterChecks) {
                throw new com.aiapitesting.backend.service.job.JobCancelledException();
            }
        }

        @Override
        public int addTokens(int t) {
            tokens += t;
            return tokens;
        }

        @Override
        public int tokensUsed() {
            return tokens;
        }

        @Override
        public int tokenCap() {
            return cap;
        }

        @Override
        public boolean isDryRun() {
            return dryRun;
        }
    }

    @Test
    void successfulRun_reportsEachSystemAndAiStepInOrder() {
        results(TestResultStatus.PASSED, 201, "{}");
        Recording rec = new Recording(false, 0);

        BaselineResponse response = service.execute(projectId, endpointId, confirmed(2), rec);

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(rec.phases).containsExactly("PRECHECK:SYSTEM", "AI_BASELINE:AI", "CHAIN:SYSTEM", "RUN:SYSTEM", "LOCK:SYSTEM", "FILL:SYSTEM");
        assertThat(rec.tokens).isEqualTo(5000);
    }

    @Test
    void dryRun_listsPlannedSteps_withoutCallingAiTargetOrWritingTests() {
        Recording rec = new Recording(true, 0);

        BaselineResponse response = service.execute(projectId, endpointId, confirmed(2), rec);

        assertThat(response.status()).isEqualTo("DRY_RUN");
        assertThat(rec.phases).containsExactly("PRECHECK:SYSTEM", "AI_BASELINE:AI", "RUN:SYSTEM", "FILL:SYSTEM");
        assertThat(askCount).isZero();
        verify(executionService, never()).triggerForProject(any(), any(), any(), any());
        verify(generation, never()).saveBaselineTest(any(), any(), any(), any());
    }

    @Test
    void cancelAtASafeBoundary_stopsBeforeAnotherAiCall_andKeepsNothingLocked() {
        results(TestResultStatus.FAILED, 400, "{\"error\":\"bad\"}");
        Recording rec = new Recording(false, 0);
        rec.cancelAfterChecks = 3; // PRECHECK(1) -> vòng 1 đầu(2) -> sau AI(3) -> vòng 2 đầu(4) = huỷ

        assertThatThrownBy(() -> service.execute(projectId, endpointId, confirmed(3), rec))
                .isInstanceOf(com.aiapitesting.backend.service.job.JobCancelledException.class);

        assertThat(askCount).isEqualTo(1);
        verify(testCaseService, never()).setLocked(any(), any(), any(), anyBoolean());
    }

    @Test
    void jobTokenCap_stopsRepairLoopBeforeTheNextAiCall() {
        results(TestResultStatus.FAILED, 400, "{\"error\":\"bad\"}");
        Recording rec = new Recording(false, 3000); // 1 lần gọi AI = 5000 > trần 3000

        BaselineResponse response = service.execute(projectId, endpointId, confirmed(3), rec);

        assertThat(response.status()).isEqualTo("NEEDS_HUMAN");
        assertThat(response.reason()).isEqualTo("TOKEN_BUDGET");
        assertThat(askCount).isEqualTo(1);
    }
}
