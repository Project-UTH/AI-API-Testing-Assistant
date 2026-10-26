package com.aiapitesting.backend.service.job;

import com.aiapitesting.backend.dto.request.StartGenerationJobRequest;
import com.aiapitesting.backend.dto.response.BaselineResponse;
import com.aiapitesting.backend.dto.response.CoverageFillResponse;
import com.aiapitesting.backend.dto.response.GenerationJobResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.GenerationJob;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.ExecutionInProgressException;
import com.aiapitesting.backend.exception.GenerationJobNotFoundException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.GenerationJobRepository;
import com.aiapitesting.backend.repository.GenerationJobStepRepository;
import com.aiapitesting.backend.service.CoverageGapFillService;
import com.aiapitesting.backend.service.ProjectService;
import com.aiapitesting.backend.service.ai.BaselineVerificationService;
import com.aiapitesting.backend.service.ai.RuleExtractionService;
import com.aiapitesting.backend.service.rule.RequirementDocumentService;
import com.aiapitesting.backend.service.ai.TestCaseGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Job sinh test chạy nền: kiểm tra ràng buộc khi bắt đầu, huỷ, quy tắc trạng thái cuối và khôi phục khi khởi động lại. */
class GenerationJobServiceTest {

    private final GenerationJobRepository jobs = mock(GenerationJobRepository.class);
    private final GenerationJobStepRepository steps = mock(GenerationJobStepRepository.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final EndpointRepository endpointRepository = mock(EndpointRepository.class);
    private final JobStepRecorder recorder = mock(JobStepRecorder.class);
    private final BaselineVerificationService baseline = mock(BaselineVerificationService.class);
    private final CoverageGapFillService gapFill = mock(CoverageGapFillService.class);
    private final TestCaseGenerationService generation = mock(TestCaseGenerationService.class);
    private final RuleExtractionService ruleExtraction = mock(RuleExtractionService.class);
    private final RequirementDocumentService documents = mock(RequirementDocumentService.class);
    private final Executor executor = mock(Executor.class);
    private GenerationJobService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private Project project;
    private Endpoint e1;
    private Endpoint e2;

    @BeforeEach
    void setUp() {
        service = new GenerationJobService(jobs, steps, projectService, endpointRepository, recorder, baseline, gapFill, generation, ruleExtraction, documents, executor);
        User owner = User.builder().id(ownerId).build();
        project = Project.builder().id(projectId).owner(owner).build();
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        e1 = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/jobs").build();
        e2 = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/ping").build();
        when(endpointRepository.findAllById(any())).thenAnswer(inv -> {
            List<UUID> ids = new java.util.ArrayList<>();
            ((Iterable<UUID>) inv.getArgument(0)).forEach(ids::add);
            return List.of(e1, e2).stream().filter(e -> ids.contains(e.getId())).toList();
        });
        when(jobs.existsByProjectIdAndStatusIn(eq(projectId), anyCollection())).thenReturn(false);
        when(jobs.countByOwnerIdAndStatusIn(eq(ownerId), anyCollection())).thenReturn(0L);
        when(jobs.save(any())).thenAnswer(inv -> {
            GenerationJob j = inv.getArgument(0);
            if (j.getId() == null) {
                j.setId(UUID.randomUUID());
            }
            return j;
        });
    }

    private StartGenerationJobRequest request(UUID... ids) {
        StartGenerationJobRequest r = new StartGenerationJobRequest();
        r.setEndpointIds(List.of(ids));
        return r;
    }

    @Test
    void start_createsQueuedJob_picksTypeByEndpointCount_andSubmitsToTheExecutor() {
        GenerationJobResponse one = service.start(projectId, request(e1.getId()));
        GenerationJobResponse many = service.start(projectId, request(e1.getId(), e2.getId()));

        assertThat(one.status()).isEqualTo(GenerationJob.QUEUED);
        assertThat(one.type()).isEqualTo(GenerationJob.TYPE_FULL);
        assertThat(many.type()).isEqualTo(GenerationJob.TYPE_SYSTEM); // nhiều endpoint: mặc định tầng hệ thống (0 token)
        verify(executor, org.mockito.Mockito.times(2)).execute(any(Runnable.class));
    }

    @Test
    void start_checklistOnly_needsTheBusinessChecklistOption() {
        StartGenerationJobRequest r = request(e1.getId());
        r.setChecklistOnly(true);
        assertThatThrownBy(() -> service.start(projectId, r)).isInstanceOf(InvalidRequestException.class);
        r.setBusinessChecklist(true);
        r.setInstruction("Câu hỏi: hạn nộp? Trả lời: không quá khứ");
        when(documents.activeDocuments(eq(project), any())).thenReturn(List.of());
        assertThat(service.start(projectId, r).status()).isEqualTo(GenerationJob.QUEUED);
    }

    @Test
    void start_rejectsEmptySelection_foreignEndpointAndUnknownType() {
        assertThatThrownBy(() -> service.start(projectId, request())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.start(projectId, null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.start(projectId, request(UUID.randomUUID()))).isInstanceOf(InvalidRequestException.class);
        StartGenerationJobRequest bad = request(e1.getId());
        bad.setType("HACK");
        assertThatThrownBy(() -> service.start(projectId, bad)).isInstanceOf(InvalidRequestException.class);
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void start_rulesJob_needsDocumentOrInstruction_defaultsToAllEndpoints_andClampsTheTokenBudget() {
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(e1, e2));
        StartGenerationJobRequest req = new StartGenerationJobRequest();
        req.setType("rules");
        when(documents.activeDocuments(eq(project), any())).thenReturn(List.of());
        // Không tài liệu, không chỉ dẫn: vẫn hợp lệ, làn "AI gợi ý thêm" tự bật (chỉ gợi ý từ spec).
        assertThat(service.start(projectId, req).type()).isEqualTo(GenerationJob.TYPE_RULES);

        req.setInstruction("Lương tối thiểu không được lớn hơn lương tối đa");
        GenerationJobResponse job = service.start(projectId, req);
        assertThat(job.type()).isEqualTo(GenerationJob.TYPE_RULES);
        assertThat(job.tokenCap()).isEqualTo(RuleExtractionService.DEFAULT_BUDGET);

        req.setTokenCap(10);
        assertThat(service.start(projectId, req).tokenCap()).isEqualTo(RuleExtractionService.MIN_BUDGET);
        req.setTokenCap(50_000_000);
        assertThat(service.start(projectId, req).tokenCap()).isEqualTo(RuleExtractionService.MAX_BUDGET);

        req.setInstruction("x".repeat(GenerationJobService.MAX_INSTRUCTION_CHARS + 1));
        assertThatThrownBy(() -> service.start(projectId, req)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void start_rulesJob_rejectsEndpointOrDocumentOutsideTheProject() {
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(e1));
        StartGenerationJobRequest req = new StartGenerationJobRequest();
        req.setType("RULES");
        req.setInstruction("Một chỉ dẫn hợp lệ cho AI");
        req.setEndpointIds(List.of(UUID.randomUUID()));
        assertThatThrownBy(() -> service.start(projectId, req)).isInstanceOf(InvalidRequestException.class);

        req.setEndpointIds(null);
        req.setDocumentIds(List.of(UUID.randomUUID()));
        when(documents.activeDocuments(eq(project), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.start(projectId, req)).isInstanceOf(InvalidRequestException.class);
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void runJob_savesTheOwaspScopeOnTheEndpoint_fromTheIncludeOwaspFlag() {
        for (boolean owasp : new boolean[]{true, false}) {
            StartGenerationJobRequest req = request(e1.getId());
            req.setType("SYSTEM");
            req.setIncludeOwasp(owasp);
            GenerationJob job = GenerationJob.builder().id(UUID.randomUUID()).projectId(projectId).ownerId(ownerId).type(GenerationJob.TYPE_SYSTEM)
                    .status(GenerationJob.QUEUED).build();
            when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
            when(endpointRepository.findById(e1.getId())).thenReturn(Optional.of(e1));
            when(gapFill.fill(any(), any(), any())).thenReturn(new com.aiapitesting.backend.dto.response.CoverageFillResponse(0, List.of(), List.of(), null, 0));
            service.runJob(job.getId(), projectId, List.of(e1.getId()), req);
            assertThat(e1.getScopeOwasp()).isEqualTo(owasp);
        }
    }

    @Test
    void start_rejectsASecondActiveJobInTheSameProject_andTooManyJobsPerUser() {
        when(jobs.existsByProjectIdAndStatusIn(eq(projectId), anyCollection())).thenReturn(true);
        assertThatThrownBy(() -> service.start(projectId, request(e1.getId()))).isInstanceOf(ExecutionInProgressException.class);

        when(jobs.existsByProjectIdAndStatusIn(eq(projectId), anyCollection())).thenReturn(false);
        when(jobs.countByOwnerIdAndStatusIn(eq(ownerId), anyCollection())).thenReturn((long) GenerationJobService.MAX_ACTIVE_PER_USER);
        assertThatThrownBy(() -> service.start(projectId, request(e1.getId()))).isInstanceOf(ExecutionInProgressException.class);
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void cancel_queuedJobIsCancelledAtOnce_runningJobBecomesCancelling_unknownJobIs404() {
        GenerationJob queued = GenerationJob.builder().id(UUID.randomUUID()).projectId(projectId).status(GenerationJob.QUEUED).build();
        GenerationJob running = GenerationJob.builder().id(UUID.randomUUID()).projectId(projectId).status(GenerationJob.RUNNING).build();
        when(jobs.findByIdAndProjectId(queued.getId(), projectId)).thenReturn(Optional.of(queued));
        when(jobs.findByIdAndProjectId(running.getId(), projectId)).thenReturn(Optional.of(running));
        when(jobs.findByIdAndProjectId(any(), any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return id.equals(queued.getId()) ? Optional.of(queued) : id.equals(running.getId()) ? Optional.of(running) : Optional.empty();
        });

        assertThat(service.cancel(projectId, queued.getId()).status()).isEqualTo(GenerationJob.CANCELLED);
        assertThat(service.cancel(projectId, running.getId()).status()).isEqualTo(GenerationJob.CANCELLING);
        assertThat(running.isCancelRequested()).isTrue();
        assertThatThrownBy(() -> service.cancel(projectId, UUID.randomUUID())).isInstanceOf(GenerationJobNotFoundException.class);
    }

    private GenerationJob runWith(String type, StartGenerationJobRequest req, UUID... endpointIds) {
        GenerationJob job = GenerationJob.builder().id(UUID.randomUUID()).projectId(projectId).ownerId(ownerId).type(type).status(GenerationJob.QUEUED).build();
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(endpointRepository.findById(e1.getId())).thenReturn(Optional.of(e1));
        when(endpointRepository.findById(e2.getId())).thenReturn(Optional.of(e2));
        when(recorder.startStep(any(), any(), anyString(), anyString(), anyString(), anyString())).thenReturn(UUID.randomUUID());
        service.runJob(job.getId(), projectId, List.of(endpointIds), req);
        return job;
    }

    @Test
    void runJob_allConfirmed_isSucceeded_andStoresPerEndpointResults() {
        when(baseline.run(eq(projectId), any(), any(), any())).thenReturn(
                new BaselineResponse("CONFIRMED", null, "ok", UUID.randomUUID(), List.of(), 1000, 20, 200, null));

        GenerationJob job = runWith(GenerationJob.TYPE_FULL, request(e1.getId()), e1.getId());

        assertThat(job.getStatus()).isEqualTo(GenerationJob.SUCCEEDED);
        assertThat(job.getResultJson()).contains("CONFIRMED").contains("POST /jobs");
        assertThat(job.getFinishedAt()).isNotNull();
    }

    @Test
    void runJob_aiGapsStep_usesBusinessOnlyGeneration_neverTheFullRegenerate() {
        when(baseline.run(eq(projectId), any(), any(), any())).thenReturn(
                new BaselineResponse("CONFIRMED", null, "ok", UUID.randomUUID(), List.of(), 1000, 20, 200, null));
        when(generation.generateBusinessOnly(eq(projectId), any())).thenReturn(
                new com.aiapitesting.backend.dto.response.GenerateTestCasesResponse(List.of(), null));
        StartGenerationJobRequest req = request(e1.getId());
        req.setIncludeBusiness(true);

        runWith(GenerationJob.TYPE_FULL, req, e1.getId());

        // generate() khai báo lại toàn bộ tập test và xoá test cơ bản/Security chưa khoá (gồm test hệ thống vừa bù)
        verify(generation).generateBusinessOnly(eq(projectId), eq(e1.getId()));
        verify(generation, never()).generate(any(), any(), any());
    }

    @Test
    void runJob_anyNeedsHuman_isNeedsHuman_onePerEndpointFailureDoesNotStopOthers() {
        when(baseline.run(eq(projectId), eq(e1.getId()), any(), any())).thenThrow(new IllegalStateException("boom"));
        when(baseline.run(eq(projectId), eq(e2.getId()), any(), any())).thenReturn(
                new BaselineResponse("CONFIRMED", null, "ok", null, List.of(), 0, 5, 200, null));

        GenerationJob job = runWith(GenerationJob.TYPE_FULL, request(e1.getId(), e2.getId()), e1.getId(), e2.getId());

        assertThat(job.getStatus()).isEqualTo(GenerationJob.NEEDS_HUMAN);
        assertThat(job.getResultJson()).contains("FAILED").contains("CONFIRMED");
    }

    @Test
    void runJob_allEndpointsFail_isFailed() {
        when(baseline.run(eq(projectId), any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        GenerationJob job = runWith(GenerationJob.TYPE_FULL, request(e1.getId()), e1.getId());

        assertThat(job.getStatus()).isEqualTo(GenerationJob.FAILED);
    }

    @Test
    void runJob_cancelledByUser_endsCancelled_andStopsBeforeTheNextEndpoint() {
        when(recorder.isCancelRequested(any())).thenReturn(false, true);
        when(baseline.run(eq(projectId), any(), any(), any())).thenReturn(
                new BaselineResponse("CONFIRMED", null, "ok", null, List.of(), 0, 0, 200, null));

        GenerationJob job = runWith(GenerationJob.TYPE_FULL, request(e1.getId(), e2.getId()), e1.getId(), e2.getId());

        assertThat(job.getStatus()).isEqualTo(GenerationJob.CANCELLED);
        verify(baseline, org.mockito.Mockito.times(1)).run(eq(projectId), any(), any(), any());
    }

    @Test
    void runJob_systemTier_usesNoAi_andReportsNoBaselineAsNeedsHuman() {
        when(gapFill.fill(eq(projectId), eq(e1.getId()), any())).thenReturn(new CoverageFillResponse(0, List.of(),
                List.of(new CoverageFillResponse.NotFilled("k", "label", "Cần ít nhất 1 test case làm gốc")), null, 0));
        when(gapFill.fill(eq(projectId), eq(e2.getId()), any())).thenReturn(new CoverageFillResponse(12, List.of(), List.of(), null, 0));

        GenerationJob job = runWith(GenerationJob.TYPE_SYSTEM, request(e1.getId(), e2.getId()), e1.getId(), e2.getId());

        assertThat(job.getStatus()).isEqualTo(GenerationJob.NEEDS_HUMAN);
        assertThat(job.getResultJson()).contains("NO_BASELINE").contains("FILLED");
        verify(baseline, never()).run(any(), any(), any(), any());
    }

    @Test
    void runJob_dryRunMakesNoWrites_andRecoveryMarksInterruptedJobs() {
        GenerationJob dry = GenerationJob.builder().id(UUID.randomUUID()).projectId(projectId).ownerId(ownerId).type(GenerationJob.TYPE_SYSTEM)
                .status(GenerationJob.QUEUED).dryRun(true).build();
        when(jobs.findById(dry.getId())).thenReturn(Optional.of(dry));
        when(endpointRepository.findById(e2.getId())).thenReturn(Optional.of(e2));
        when(recorder.startStep(any(), any(), anyString(), anyString(), anyString(), anyString())).thenReturn(UUID.randomUUID());

        service.runJob(dry.getId(), projectId, List.of(e2.getId()), request(e2.getId()));

        assertThat(dry.getStatus()).isEqualTo(GenerationJob.SUCCEEDED);
        verify(gapFill, never()).fill(any(), any(), any());

        when(jobs.markAll(anyCollection(), eq(GenerationJob.INTERRUPTED), any(), anyString())).thenReturn(2);
        service.recoverInterruptedJobs();
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(steps).failAllRunning(any(), msg.capture());
        assertThat(msg.getValue()).contains("khởi động lại");
        verify(jobs, org.mockito.Mockito.atLeastOnce()).markAll(anyCollection(), anyString(), any(), anyString());
    }
}
