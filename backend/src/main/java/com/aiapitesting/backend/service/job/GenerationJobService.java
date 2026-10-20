package com.aiapitesting.backend.service.job;

import com.aiapitesting.backend.dto.request.BaselineRequest;
import com.aiapitesting.backend.dto.request.CoverageFillRequest;
import com.aiapitesting.backend.dto.request.GenerateTestCasesRequest;
import com.aiapitesting.backend.dto.request.StartGenerationJobRequest;
import com.aiapitesting.backend.dto.response.BaselineResponse;
import com.aiapitesting.backend.dto.response.CoverageFillResponse;
import com.aiapitesting.backend.dto.response.GenerateTestCasesResponse;
import com.aiapitesting.backend.dto.response.GenerationJobResponse;
import com.aiapitesting.backend.dto.response.GenerationPlanSummary;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.GenerationJob;
import com.aiapitesting.backend.entity.GenerationJobStep;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.exception.ExecutionInProgressException;
import com.aiapitesting.backend.exception.GenerationJobNotFoundException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.GenerationJobRepository;
import com.aiapitesting.backend.repository.GenerationJobStepRepository;
import com.aiapitesting.backend.service.CoverageGapFillService;
import com.aiapitesting.backend.service.ProjectService;
import com.aiapitesting.backend.service.ai.BaselineVerificationService;
import com.aiapitesting.backend.service.ai.TestCaseGenerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Job sinh test chạy nền cho người dùng GIÁM SÁT từng bước (hệ thống/AI), HUỶ giữa chừng và vẫn xem được khi chuyển trang. Mỗi project chỉ có 1 job
 * đang hoạt động; mỗi người dùng tối đa {@link #MAX_ACTIVE_PER_USER}. Lúc khởi động lại, job đang chạy được đánh dấu INTERRUPTED (không có job ma).
 *
 * <p>CỐ Ý không @Transactional ở cấp lớp: luồng job commit từng bước ngay qua {@link JobStepRecorder} để polling thấy kịp thời.
 */
@Slf4j
@Service
public class GenerationJobService {

    static final int MAX_ENDPOINTS = 200;
    static final int MAX_ACTIVE_PER_USER = 3;
    private static final Set<String> ACTIVE = Set.of(GenerationJob.QUEUED, GenerationJob.RUNNING, GenerationJob.CANCELLING);

    private final GenerationJobRepository jobs;
    private final GenerationJobStepRepository steps;
    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final JobStepRecorder recorder;
    private final BaselineVerificationService baseline;
    private final CoverageGapFillService gapFill;
    private final TestCaseGenerationService generation;
    private final Executor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<UUID, Object> projectLocks = new ConcurrentHashMap<>();

    public GenerationJobService(
            GenerationJobRepository jobs, GenerationJobStepRepository steps, ProjectService projectService, EndpointRepository endpointRepository,
            JobStepRecorder recorder, BaselineVerificationService baseline, CoverageGapFillService gapFill, TestCaseGenerationService generation,
            @Qualifier("jobExecutor") Executor executor
    ) {
        this.jobs = jobs;
        this.steps = steps;
        this.projectService = projectService;
        this.endpointRepository = endpointRepository;
        this.recorder = recorder;
        this.baseline = baseline;
        this.gapFill = gapFill;
        this.generation = generation;
        this.executor = executor;
    }

    // ---- Khởi động / khôi phục ----

    /** Job đang chạy lúc server tắt thì không còn luồng nào chạy nó nữa: đánh dấu INTERRUPTED và đóng các bước còn RUNNING. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedJobs() {
        Instant now = Instant.now();
        int n = jobs.markAll(ACTIVE, GenerationJob.INTERRUPTED, now, "Máy chủ khởi động lại khi job đang chạy");
        if (n > 0) {
            steps.failAllRunning(now, "Bị gián đoạn do máy chủ khởi động lại");
            log.warn("Da danh dau {} job sinh test bi gian doan do khoi dong lai", n);
        }
    }

    // ---- API ----

    public GenerationJobResponse start(UUID projectId, StartGenerationJobRequest request) {
        if (request == null || request.getEndpointIds() == null || request.getEndpointIds().isEmpty()) {
            throw new InvalidRequestException("Phải chọn ít nhất 1 endpoint để sinh test");
        }
        if (request.getEndpointIds().size() > MAX_ENDPOINTS) {
            throw new InvalidRequestException("Tối đa " + MAX_ENDPOINTS + " endpoint cho 1 job");
        }
        Project project = projectService.getOwnedProject(projectId);
        UUID ownerId = project.getOwner().getId();
        List<UUID> ids = request.getEndpointIds().stream().distinct().toList();
        List<Endpoint> found = endpointRepository.findAllById(ids);
        if (found.size() != ids.size() || found.stream().anyMatch(e -> !projectId.equals(e.getProject().getId()))) {
            throw new InvalidRequestException("Có endpoint không thuộc project này");
        }
        String type = request.getType() == null ? (ids.size() == 1 ? GenerationJob.TYPE_FULL : GenerationJob.TYPE_SYSTEM) : request.getType().toUpperCase();
        if (!Set.of(GenerationJob.TYPE_FULL, GenerationJob.TYPE_SYSTEM, GenerationJob.TYPE_LEGACY).contains(type)) {
            throw new InvalidRequestException("Loại job không hợp lệ: " + type);
        }

        GenerationJob job;
        synchronized (projectLocks.computeIfAbsent(projectId, k -> new Object())) {
            if (jobs.existsByProjectIdAndStatusIn(projectId, ACTIVE)) {
                throw new ExecutionInProgressException("Project đang có job sinh test chưa xong - đợi xong hoặc huỷ job đó trước");
            }
            if (jobs.countByOwnerIdAndStatusIn(ownerId, ACTIVE) >= MAX_ACTIVE_PER_USER) {
                throw new ExecutionInProgressException("Bạn đang có quá nhiều job sinh test đang chạy (" + MAX_ACTIVE_PER_USER + ")");
            }
            job = jobs.save(GenerationJob.builder()
                    .projectId(projectId).ownerId(ownerId).type(type).status(GenerationJob.QUEUED)
                    .dryRun(Boolean.TRUE.equals(request.getDryRun()))
                    .tokenCap(request.getTokenCap() == null ? 0 : Math.max(0, request.getTokenCap()))
                    .paramsJson(toJson(request))
                    .build());
        }
        UUID jobId = job.getId();
        executor.execute(() -> runJob(jobId, projectId, ids, request));
        return toResponse(job, List.of(), 0);
    }

    @Transactional(readOnly = true)
    public GenerationJobResponse get(UUID projectId, UUID jobId, int afterSeq) {
        projectService.getOwnedProject(projectId);
        GenerationJob job = jobs.findByIdAndProjectId(jobId, projectId).orElseThrow(() -> new GenerationJobNotFoundException("Không tìm thấy job"));
        return toResponse(job, steps.findForPolling(jobId, Math.max(0, afterSeq)), afterSeq);
    }

    @Transactional(readOnly = true)
    public List<GenerationJobResponse> list(UUID projectId, boolean activeOnly) {
        projectService.getOwnedProject(projectId);
        List<GenerationJob> list = activeOnly
                ? jobs.findAllByProjectIdAndStatusInOrderByCreatedAtDesc(projectId, ACTIVE) : jobs.findTop20ByProjectIdOrderByCreatedAtDesc(projectId);
        return list.stream().map(j -> toResponse(j, List.of(), 0)).toList();
    }

    @Transactional
    public GenerationJobResponse cancel(UUID projectId, UUID jobId) {
        projectService.getOwnedProject(projectId);
        GenerationJob job = jobs.findByIdAndProjectId(jobId, projectId).orElseThrow(() -> new GenerationJobNotFoundException("Không tìm thấy job"));
        if (GenerationJob.QUEUED.equals(job.getStatus())) {
            job.setCancelRequested(true);
            job.setStatus(GenerationJob.CANCELLED);
            job.setFinishedAt(Instant.now());
        } else if (job.isActive()) {
            job.setCancelRequested(true);
            job.setStatus(GenerationJob.CANCELLING);
        }
        return toResponse(jobs.save(job), List.of(), 0);
    }

    /** Dọn job + bước của project khi xoá project. */
    @Transactional
    public void deleteForProject(UUID projectId) {
        List<UUID> ids = jobs.findIdsByProjectId(projectId);
        if (!ids.isEmpty()) {
            steps.deleteAllByJobIdIn(ids);
            jobs.deleteAllByProjectId(projectId);
        }
    }

    // ---- Chạy job (trên jobExecutor, có SecurityContext của người bấm) ----

    void runJob(UUID jobId, UUID projectId, List<UUID> endpointIds, StartGenerationJobRequest req) {
        GenerationJob job = jobs.findById(jobId).orElse(null);
        if (job == null || GenerationJob.CANCELLED.equals(job.getStatus())) {
            return; // đã huỷ lúc còn xếp hàng
        }
        job.setStatus(GenerationJob.RUNNING);
        job.setStartedAt(Instant.now());
        jobs.save(job);
        JobContext ctx = new JobContext(jobId, recorder, job.isDryRun(), job.getTokenCap(), 0);
        List<GenerationJobResponse.EndpointResult> results = new ArrayList<>();
        String finalStatus;
        String error = null;
        try {
            for (UUID endpointId : endpointIds) {
                ctx.checkCancelled();
                results.add(runEndpoint(job, projectId, endpointId, req, ctx));
            }
            boolean anyHuman = results.stream().anyMatch(r -> "NEEDS_HUMAN".equals(r.status()) || "FAILED".equals(r.status()));
            boolean allFailed = !results.isEmpty() && results.stream().allMatch(r -> "FAILED".equals(r.status()));
            finalStatus = allFailed ? GenerationJob.FAILED : anyHuman ? GenerationJob.NEEDS_HUMAN : GenerationJob.SUCCEEDED;
        } catch (JobCancelledException e) {
            finalStatus = GenerationJob.CANCELLED;
        } catch (RuntimeException e) {
            log.warn("Job sinh test {} that bai: {}", jobId, e.toString());
            finalStatus = GenerationJob.FAILED;
            error = e.getMessage();
        }
        GenerationJob done = jobs.findById(jobId).orElse(job);
        done.setStatus(finalStatus);
        done.setFinishedAt(Instant.now());
        done.setErrorMessage(error);
        done.setResultJson(toJson(results));
        jobs.save(done);
    }

    private GenerationJobResponse.EndpointResult runEndpoint(
            GenerationJob job, UUID projectId, UUID endpointId, StartGenerationJobRequest req, JobContext ctx
    ) {
        Endpoint endpoint = endpointRepository.findById(endpointId).orElseThrow();
        String label = endpoint.getMethod() + " " + endpoint.getPath();
        try {
            return switch (job.getType()) {
                case GenerationJob.TYPE_FULL -> {
                    BaselineRequest br = new BaselineRequest(Boolean.TRUE.equals(req.getConfirmWrite()), req.getMaxRepairRounds(),
                            req.getPathParamValues(), req.getFillCoverage(), false);
                    BaselineResponse r = baseline.run(projectId, endpointId, br, ctx);
                    GenerationJobResponse.EndpointResult base = new GenerationJobResponse.EndpointResult(
                            endpointId, label, r.status(), r.reason(), r.message(), r.tokensUsed(), r.filledCount());
                    boolean baselineOk = ctx.isDryRun() || "CONFIRMED".equals(r.status()) || "ALREADY_HAS_BASELINE".equals(r.status());
                    if (baselineOk && Boolean.TRUE.equals(req.getIncludeBusiness())) {
                        runAiGaps(projectId, endpointId, label, ctx);
                    }
                    yield base;
                }
                case GenerationJob.TYPE_SYSTEM -> runSystemTier(projectId, endpointId, label, ctx);
                default -> runLegacy(projectId, endpointId, label, req, ctx);
            };
        } catch (JobCancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            ctx.start(endpointId, label, "ERROR", "SYSTEM", "Lỗi khi xử lý endpoint").fail(e.getMessage());
            return new GenerationJobResponse.EndpointResult(endpointId, label, "FAILED", "ERROR", e.getMessage(), 0, 0);
        }
    }

    /** Tầng hệ thống thuần tuý (0 token): bổ sung checklist từ test gốc hợp lệ có sẵn của endpoint. */
    private GenerationJobResponse.EndpointResult runSystemTier(UUID projectId, UUID endpointId, String label, JobContext ctx) {
        JobProgress.Step pre = ctx.start(endpointId, label, "PRECHECK", "SYSTEM", "Tìm test gốc hợp lệ có sẵn của endpoint");
        if (ctx.isDryRun()) {
            pre.ok("Chạy thử: sẽ bổ sung checklist từ test gốc có sẵn (0 token)");
            ctx.start(endpointId, label, "FILL", "SYSTEM", "Sẽ bổ sung checklist (0 token)").skipped("Chạy thử: không ghi gì");
            return new GenerationJobResponse.EndpointResult(endpointId, label, "DRY_RUN", null, "Chạy thử", 0, 0);
        }
        pre.ok("Dùng test hợp lệ có sẵn (ưu tiên test đã Pass) làm nền");
        JobProgress.Step f = ctx.start(endpointId, label, "FILL", "SYSTEM", "Hệ thống bổ sung checklist từ test gốc (0 token)");
        CoverageFillResponse r = gapFill.fill(projectId, endpointId, new CoverageFillRequest(null));
        if (r.createdCount() == 0 && !r.notFilled().isEmpty()) {
            String reason = r.notFilled().get(0).reason();
            f.fail(reason);
            return new GenerationJobResponse.EndpointResult(endpointId, label, "NEEDS_HUMAN", "NO_BASELINE", reason, 0, 0);
        }
        f.ok("Đã tạo " + r.createdCount() + " test" + (r.notFilled().isEmpty() ? "" : "; " + r.notFilled().size() + " mục chưa bù được"));
        return new GenerationJobResponse.EndpointResult(endpointId, label, "FILLED", null, "Đã bổ sung " + r.createdCount() + " test", 0, r.createdCount());
    }

    /** Nút 'Sinh Test Case' cũ chạy trong job: AI sinh theo các nhóm đã tick, hệ thống chấm theo nội dung và tự bù mục còn thiếu. */
    /**
     * Bước AI "còn thiếu": sau khi checklist đã đủ, AI đoán thêm luật nghiệp vụ không có trong spec. Kết quả là test BUSINESS gắn nhãn
     * GIẢ ĐỊNH, không khoá, không tính vào độ bao phủ - người dùng phải duyệt. Lỗi ở bước này KHÔNG làm hỏng kết quả của test đã xác nhận.
     */
    private void runAiGaps(UUID projectId, UUID endpointId, String label, JobContext ctx) {
        if (ctx.isDryRun()) {
            ctx.start(endpointId, label, "AI_GAPS", "AI", "Sẽ nhờ AI đoán thêm test nghiệp vụ còn thiếu (GIẢ ĐỊNH, cần duyệt)").skipped("Chạy thử: chưa gọi AI");
            return;
        }
        ctx.checkCancelled();
        if (ctx.overCap()) {
            ctx.start(endpointId, label, "AI_GAPS", "AI", "Bỏ qua bước AI bổ sung nghiệp vụ").skipped("Đã chạm trần token của job");
            return;
        }
        JobProgress.Step s = ctx.start(endpointId, label, "AI_GAPS", "AI", "AI đoán thêm test nghiệp vụ còn thiếu (nhãn GIẢ ĐỊNH, không tính vào độ bao phủ)");
        try {
            java.time.Instant since = java.time.Instant.now().minusSeconds(1);
            GenerateTestCasesResponse r = awaitCancellable(generation.generate(projectId, endpointId, new GenerateTestCasesRequest(false, false, false, false, true)), ctx);
            int tokens = generation.tokensSince(endpointId, since);
            ctx.addTokens(tokens);
            s.finish("OK", "Thêm " + r.testCases().size() + " test nghiệp vụ GIẢ ĐỊNH: cần bạn duyệt trước khi tin kết quả", null, tokens);
        } catch (java.util.concurrent.ExecutionException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Throwable cause = e.getCause() == null ? e : e.getCause();
            s.fail(cause.getMessage());
        }
    }

    /** Chờ kết quả AI nhưng kiểm tra huỷ mỗi 2 giây: job dừng ngay khi người dùng huỷ (lần gọi AI đang dở tự kết thúc ở nền, không bỏ dữ liệu nửa chừng). */
    private static <T> T awaitCancellable(java.util.concurrent.CompletableFuture<T> future, JobProgress ctx)
            throws java.util.concurrent.ExecutionException, InterruptedException {
        while (true) {
            try {
                return future.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.checkCancelled();
            }
        }
    }

    private GenerationJobResponse.EndpointResult runLegacy(UUID projectId, UUID endpointId, String label, StartGenerationJobRequest req, JobContext ctx) {
        GenerateTestCasesRequest g = new GenerateTestCasesRequest(
                Boolean.TRUE.equals(req.getIncludeSecurity()), !Boolean.FALSE.equals(req.getIncludePositive()),
                !Boolean.FALSE.equals(req.getIncludeNegative()), !Boolean.FALSE.equals(req.getIncludeBoundary()),
                Boolean.TRUE.equals(req.getIncludeBusiness()));
        if (ctx.isDryRun()) {
            ctx.start(endpointId, label, "AI_GENERATE", "AI", "Sẽ gọi AI sinh test theo các nhóm đã chọn (~20.000-40.000 token)").skipped("Chạy thử: chưa gọi AI");
            return new GenerationJobResponse.EndpointResult(endpointId, label, "DRY_RUN", null, "Chạy thử", 0, 0);
        }
        ctx.checkCancelled();
        JobProgress.Step s = ctx.start(endpointId, label, "AI_GENERATE", "AI", "AI sinh test theo checklist, hệ thống chấm theo nội dung và tự bù mục thiếu");
        try {
            java.time.Instant since = java.time.Instant.now().minusSeconds(1);
            GenerateTestCasesResponse r = awaitCancellable(generation.generate(projectId, endpointId, g), ctx);
            int tokens = generation.tokensSince(endpointId, since);
            ctx.addTokens(tokens);
            GenerationPlanSummary plan = r.planSummary();
            String detail = "Đã tạo " + r.testCases().size() + " test"
                    + (plan == null ? "" : " (AI đạt " + plan.aiSatisfiedCount() + " mục, hệ thống bù " + plan.systemFilledCount() + ", đề xuất thêm " + plan.aiExtraCount() + ")");
            s.finish("OK", detail, null, tokens);
            return new GenerationJobResponse.EndpointResult(endpointId, label, "GENERATED", null, detail, tokens, plan == null ? 0 : plan.systemFilledCount());
        } catch (java.util.concurrent.ExecutionException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Throwable cause = e.getCause() == null ? e : e.getCause();
            s.fail(cause.getMessage());
            throw cause instanceof RuntimeException re ? re : new IllegalStateException(cause);
        }
    }

    // ---- Chuyển đổi ----

    private GenerationJobResponse toResponse(GenerationJob job, List<GenerationJobStep> stepList, int afterSeq) {
        List<GenerationJobResponse.Step> mapped = stepList.stream().map(s -> new GenerationJobResponse.Step(
                s.getSeq(), s.getEndpointId(), s.getEndpointLabel(), s.getPhase(), s.getActor(), s.getStatus(), s.getTitle(), s.getDetail(),
                s.getHttpStatus(), s.getTokens(), s.getStartedAt(), s.getFinishedAt())).toList();
        int lastSeq = stepList.stream().mapToInt(GenerationJobStep::getSeq).max().orElse(afterSeq);
        return new GenerationJobResponse(job.getId(), job.getProjectId(), job.getType(), job.getStatus(), job.isDryRun(), job.getTokenCap(),
                job.getTokensUsed(), job.getErrorMessage(), job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt(), readResults(job.getResultJson()),
                mapped, lastSeq);
    }

    private List<GenerationJobResponse.EndpointResult> readResults(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<GenerationJobResponse.EndpointResult>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
