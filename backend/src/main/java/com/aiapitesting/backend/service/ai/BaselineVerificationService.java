package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.request.BaselineRequest;
import com.aiapitesting.backend.dto.request.CoverageFillRequest;
import com.aiapitesting.backend.dto.response.BaselineResponse;
import com.aiapitesting.backend.dto.response.TestExecutionResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.service.CoverageGapFillService;
import com.aiapitesting.backend.service.ProjectService;
import com.aiapitesting.backend.service.TestCaseService;
import com.aiapitesting.backend.service.execution.TestExecutionService;
import com.aiapitesting.backend.service.job.JobProgress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * 'AI sinh test gốc + chạy thử xác nhận': (1) AI sinh ĐÚNG 1 test Positive hợp lệ, (2) hệ thống CHẠY THẬT test đó, (3) nếu chưa Pass thì
 * đưa thông báo lỗi THẬT của API cho AI sửa (tối đa vài vòng), nếu lỗi không phải do dữ liệu (401/403/404 với id/5xx...) thì DỪNG và nói rõ
 * cần người làm gì, (4) khi Pass thì khoá test gốc và (tuỳ chọn) bổ sung toàn bộ checklist bằng hệ thống (0 token).
 *
 * <p>CỐ Ý không đánh dấu @Transactional: test vừa lưu phải được COMMIT trước khi runner ở thread khác đọc, và vòng chờ phải thấy được
 * các commit của runner. Mỗi bước ghi nằm ở bean riêng ({@link TestCaseGenerationService}) với transaction ngắn. Chạy trên executor riêng
 * (không dùng chung pool của runner, tránh bế tắc khi mọi thread bận chờ).
 */
@Slf4j
@Service
public class BaselineVerificationService {

    static final int DEFAULT_REPAIR_ROUNDS = 2;
    static final int MAX_REPAIR_ROUNDS = 5;
    /** Trần token AI cho 1 lần chạy (lần sinh đầu + các lần sửa). */
    static final int TOKEN_BUDGET = 120_000;
    private static final int BODY_LIMIT = 1_500;
    private static final Pattern PATH_PLACEHOLDER = Pattern.compile("\\{\\{\\w+}}");

    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final TestCaseGenerationService generationService;
    private final CoverageGapFillService coverageGapFillService;
    private final TestExecutionService testExecutionService;
    private final TestExecutionRepository testExecutionRepository;
    private final TestResultRepository testResultRepository;
    private final TestCaseService testCaseService;
    private final com.aiapitesting.backend.service.DependencySuggestionService dependencySuggestionService;

    private long executionTimeoutMs;
    private long pollIntervalMs;

    public BaselineVerificationService(
            ProjectService projectService, EndpointRepository endpointRepository, TestCaseGenerationService generationService,
            CoverageGapFillService coverageGapFillService, TestExecutionService testExecutionService,
            TestExecutionRepository testExecutionRepository, TestResultRepository testResultRepository, TestCaseService testCaseService,
            com.aiapitesting.backend.service.DependencySuggestionService dependencySuggestionService,
            @Value("${baseline.execution-timeout-ms:600000}") long executionTimeoutMs,
            @Value("${baseline.poll-interval-ms:500}") long pollIntervalMs
    ) {
        this.projectService = projectService;
        this.endpointRepository = endpointRepository;
        this.generationService = generationService;
        this.coverageGapFillService = coverageGapFillService;
        this.testExecutionService = testExecutionService;
        this.testExecutionRepository = testExecutionRepository;
        this.testResultRepository = testResultRepository;
        this.testCaseService = testCaseService;
        this.dependencySuggestionService = dependencySuggestionService;
        this.executionTimeoutMs = executionTimeoutMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    @Async("baselineExecutor")
    public CompletableFuture<BaselineResponse> run(UUID projectId, UUID endpointId, BaselineRequest request) {
        return CompletableFuture.completedFuture(execute(projectId, endpointId, request));
    }

    BaselineResponse execute(UUID projectId, UUID endpointId, BaselineRequest request) {
        return execute(projectId, endpointId, request, JobProgress.NOOP);
    }

    /** Chạy ĐỒNG BỘ trong luồng gọi (dùng bởi job nền): báo từng bước cho {@code progress}, kiểm tra huỷ ở ranh giới an toàn. */
    public BaselineResponse run(UUID projectId, UUID endpointId, BaselineRequest request, JobProgress progress) {
        return execute(projectId, endpointId, request, progress);
    }

    /**
     * Như {@link #execute(UUID, UUID, BaselineRequest)} nhưng báo từng bước cho {@code progress} (giao diện giám sát) và kiểm tra huỷ ở ranh giới
     * an toàn (đầu mỗi vòng, sau lần gọi AI, trước khi bổ sung checklist). Chạy thử (dry-run) chỉ ghi các bước 'sẽ làm gì' - không gọi AI/API đích.
     */
    BaselineResponse execute(UUID projectId, UUID endpointId, BaselineRequest request, JobProgress progress) {
        JobProgress p = progress == null ? JobProgress.NOOP : progress;
        BaselineRequest req = request == null ? new BaselineRequest(false, null, null, null, false) : request;
        Project project = projectService.getOwnedProject(projectId);
        Endpoint endpoint = endpointRepository.findByIdAndProject(endpointId, project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));
        String label = endpoint.getMethod() + " " + endpoint.getPath();
        JobProgress.Step pre = p.start(endpointId, label, "PRECHECK", "SYSTEM", "Kiểm tra endpoint và điều kiện chạy");
        if (isWriteMethod(endpoint.getMethod()) && !Boolean.TRUE.equals(req.getConfirmWrite())) {
            String msg = "Test gốc sẽ được CHẠY THẬT trên API đích và có thể ghi dữ liệu (" + endpoint.getMethod() + "): cần xác nhận (confirmWrite) trước";
            pre.fail(msg);
            throw new InvalidRequestException(msg);
        }
        p.checkCancelled();
        boolean fill = req.getFillCoverage() == null || req.getFillCoverage();
        if (!Boolean.TRUE.equals(req.getForce()) && coverageGapFillService.hasConfirmedBaseline(projectId, endpointId)) {
            pre.ok("Endpoint đã có test gốc hợp lệ đã Pass: bỏ qua AI, dùng test đó làm nền");
            if (p.isDryRun()) {
                JobProgress.Step f = p.start(endpointId, label, "FILL", "SYSTEM", "Sẽ bổ sung checklist từ test gốc có sẵn (0 token)");
                f.skipped("Chạy thử: không ghi gì");
                return new BaselineResponse("DRY_RUN", null, "Chạy thử: endpoint đã có test gốc, chỉ cần bổ sung checklist.", null, List.of(), 0, 0, null, null);
            }
            JobProgress.Step f = p.start(endpointId, label, "FILL", "SYSTEM", "Hệ thống bổ sung checklist từ test gốc có sẵn (0 token)");
            int filled = fill ? coverageGapFillService.fill(projectId, endpointId, new CoverageFillRequest(null)).createdCount() : 0;
            f.ok("Đã tạo " + filled + " test");
            return new BaselineResponse("ALREADY_HAS_BASELINE", null, "Endpoint đã có test gốc hợp lệ đã Pass, không cần AI sinh lại.",
                    null, List.of(), 0, filled, null, null);
        }

        // Tham số đường dẫn cần id thật: không có giá trị người dùng nhập và không có test POST để nối chuỗi thì AI chỉ đoán id (UUID mẫu) -> chắc chắn 404.
        // Hỏi ngay, KHÔNG tốn token.
        Map<String, String> given = req.getPathParamValues() == null ? Map.of() : req.getPathParamValues();
        List<String> missingParams = new ArrayList<>();
        java.util.regex.Matcher pm = Pattern.compile("\\{(\\w+)}").matcher(endpoint.getPath() == null ? "" : endpoint.getPath());
        while (pm.find()) {
            String name = pm.group(1);
            boolean provided = given.get(name) != null && !given.get(name).isBlank();
            if (!provided && !dependencySuggestionService.hasCreatorSource(projectId, endpointId, name)) {
                missingParams.add(name);
            }
        }
        if (!missingParams.isEmpty()) {
            String msg = "Cần giá trị thật cho tham số đường dẫn " + missingParams + " (chưa có test POST tạo tài nguyên để nối chuỗi). Chưa gọi AI nên chưa tốn token.";
            pre.fail(msg);
            return new BaselineResponse("NEEDS_HUMAN", "MISSING_PARAM_VALUE", msg, null, List.of(), 0, 0, null, null);
        }
        pre.ok("Chưa có test gốc đã Pass: cần AI sinh 1 test gốc rồi chạy thử xác nhận");

        if (p.isDryRun()) {
            p.start(endpointId, label, "AI_BASELINE", "AI", "Sẽ gọi AI sinh 1 test gốc hợp lệ (~10.000 token)").skipped("Chạy thử: chưa gọi AI");
            p.start(endpointId, label, "RUN", "SYSTEM", "Sẽ chạy thử test gốc trên API đích (" + endpoint.getMethod() + " thật)").skipped("Chạy thử: chưa gọi API đích");
            p.start(endpointId, label, "FILL", "SYSTEM", "Sẽ bổ sung toàn bộ checklist từ test gốc (0 token)").skipped("Chạy thử: chưa ghi test");
            return new BaselineResponse("DRY_RUN", null, "Chạy thử: sẽ gọi AI 1 lần (tối đa " + (1 + MAX_REPAIR_ROUNDS) + " lần nếu phải sửa), chạy thật test gốc rồi bổ sung checklist.",
                    null, List.of(), 0, 0, null, null);
        }

        int maxRepair = Math.max(0, Math.min(req.getMaxRepairRounds() == null ? DEFAULT_REPAIR_ROUNDS : req.getMaxRepairRounds(), MAX_REPAIR_ROUNDS));
        Map<String, String> userValues = req.getPathParamValues() == null ? Map.of() : req.getPathParamValues();
        int cap = p.tokenCap() > 0 ? p.tokenCap() : TOKEN_BUDGET;

        List<BaselineResponse.Round> rounds = new ArrayList<>();
        UUID testId = null;
        TestCaseGenerationService.BaselineAttempt previous = null;
        int tokens = 0;
        RunOutcome last = null;
        for (int attempt = 0; attempt <= maxRepair; attempt++) {
            p.checkCancelled();
            if (tokens >= cap || p.overCap()) {
                String msg = "Đã chạm trần " + cap + " token cho lần chạy này mà test gốc chưa Pass.";
                p.start(endpointId, label, "DIAGNOSE", "SYSTEM", "Dừng: chạm trần token").fail(msg);
                return human("TOKEN_BUDGET", msg, testId, rounds, tokens, last);
            }
            JobProgress.Step ai = p.start(endpointId, label, "AI_BASELINE", "AI",
                    attempt == 0 ? "AI sinh 1 test gốc hợp lệ" : "AI sửa test gốc theo lỗi thật của API (lần " + attempt + ")");
            TestCaseGenerationService.BaselineProposal proposal;
            try {
                proposal = generationService.askBaseline(projectId, endpointId, previous);
            } catch (RuntimeException e) {
                ai.fail(e.getMessage());
                throw e;
            }
            tokens += proposal.tokens();
            p.addTokens(proposal.tokens());
            ai.finish("OK", "Đã sinh: " + proposal.test().name() + (proposal.test().resolvedPath() == null ? "" : " | " + proposal.test().resolvedPath()),
                    null, proposal.tokens());
            p.checkCancelled();

            GeneratedTestCase test = withUserValues(proposal.test(), userValues);
            testId = generationService.saveBaselineTest(projectId, endpointId, test, testId);
            int chained = generationService.applyChaining(projectId, endpointId, testId);
            if (chained > 0) {
                p.start(endpointId, label, "CHAIN", "SYSTEM", "Nối chuỗi dữ liệu").ok("Tự nối " + chained + " tham số đường dẫn với test POST tạo tài nguyên");
            } else {
                p.start(endpointId, label, "CHAIN", "SYSTEM", "Nối chuỗi dữ liệu").skipped("Không có tham số nào cần nối với test tạo tài nguyên");
            }

            RunOutcome outcome = runStep(p, endpointId, label, projectId, testId, "Chạy thử test gốc trên API đích");
            last = outcome;
            boolean hasPathParams = test.resolvedPath() != null && PATH_PLACEHOLDER.matcher(test.resolvedPath()).find();
            Diagnosis diagnosis = diagnose(outcome, hasPathParams);

            if (diagnosis.decision() == Decision.ADOPT_2XX) {
                // API trả 2xx khác mã AI kỳ vọng (vd 201 thay vì 200): hệ thống tự sửa kỳ vọng, chạy lại, KHÔNG tốn AI.
                p.start(endpointId, label, "ADJUST_EXPECTED", "SYSTEM", "Sửa kỳ vọng sang " + outcome.httpStatus()).ok(diagnosis.message());
                generationService.setBaselineExpectedStatus(projectId, endpointId, testId, outcome.httpStatus());
                RunOutcome second = runStep(p, endpointId, label, projectId, testId, "Chạy lại test gốc sau khi sửa kỳ vọng");
                last = second;
                outcome = second;
                diagnosis = diagnose(second, hasPathParams);
            }
            rounds.add(new BaselineResponse.Round(attempt + 1, outcome.httpStatus(), diagnosis.decision() == Decision.PASSED,
                    proposal.tokens(), diagnosis.message()));

            if (diagnosis.decision() == Decision.PASSED) {
                p.start(endpointId, label, "LOCK", "SYSTEM", "Khoá test gốc làm nền").ok("Test gốc đã Pass sau " + (attempt + 1) + " lần thử");
                testCaseService.setLocked(projectId, endpointId, testId, true);
                p.checkCancelled();
                JobProgress.Step f = p.start(endpointId, label, "FILL", "SYSTEM", "Hệ thống bổ sung toàn bộ checklist từ test gốc (0 token)");
                int filled = fill ? coverageGapFillService.fill(projectId, endpointId, new CoverageFillRequest(null)).createdCount() : 0;
                f.ok("Đã tạo " + filled + " test");
                return new BaselineResponse("CONFIRMED", null, "Test gốc đã Pass sau " + (attempt + 1) + " lần thử và được khoá làm nền.",
                        testId, rounds, tokens, filled, outcome.httpStatus(), mask(outcome.body()));
            }
            if (diagnosis.decision() == Decision.HUMAN) {
                p.start(endpointId, label, "DIAGNOSE", "SYSTEM", "Dừng: cần người xử lý (" + diagnosis.reason() + ")").fail(diagnosis.message());
                return human(diagnosis.reason(), diagnosis.message(), testId, rounds, tokens, outcome);
            }
            p.start(endpointId, label, "DIAGNOSE", "SYSTEM", "Chưa Pass (HTTP " + outcome.httpStatus() + ")").ok(diagnosis.message());
            previous = new TestCaseGenerationService.BaselineAttempt(test, outcome.httpStatus(), outcome.body());
        }
        String msg = "AI đã sửa " + maxRepair + " lần nhưng test gốc vẫn chưa Pass - xem phản hồi của API và sửa tay test gốc.";
        p.start(endpointId, label, "DIAGNOSE", "SYSTEM", "Dừng: hết số lần AI sửa").fail(msg);
        return human("REPAIR_EXHAUSTED", msg, testId, rounds, tokens, last);
    }

    private RunOutcome runStep(JobProgress p, UUID endpointId, String label, UUID projectId, UUID testId, String title) {
        JobProgress.Step run = p.start(endpointId, label, "RUN", "SYSTEM", title);
        RunOutcome outcome;
        try {
            outcome = runAndWait(p, projectId, testId);
        } catch (RuntimeException e) {
            run.fail(e.getMessage());
            throw e;
        }
        String detail = outcome.timedOut() ? "Quá thời gian chờ kết quả"
                : outcome.httpStatus() == null ? "Không nhận được phản hồi từ API đích" + (outcome.errorMessage() == null ? "" : ": " + outcome.errorMessage())
                : "HTTP " + outcome.httpStatus() + (outcome.status() == TestResultStatus.PASSED ? " - Pass" : " - chưa Pass");
        run.finish(outcome.status() == TestResultStatus.PASSED ? "OK" : "FAILED", detail, outcome.httpStatus(), null);
        return outcome;
    }

    // ---- Chẩn đoán kết quả chạy thử (thuần tuý, dễ kiểm thử) ----

    enum Decision {
        PASSED, ADOPT_2XX, REPAIR, HUMAN
    }

    record Diagnosis(Decision decision, String reason, String message) {
    }

    record RunOutcome(TestResultStatus status, Integer httpStatus, String body, String errorMessage, boolean timedOut) {
    }

    static Diagnosis diagnose(RunOutcome o, boolean hasPathParams) {
        if (o.timedOut()) {
            return new Diagnosis(Decision.HUMAN, "EXECUTION_TIMEOUT", "Lần chạy thử không kết thúc kịp thời gian chờ.");
        }
        if (o.status() == TestResultStatus.PASSED) {
            return new Diagnosis(Decision.PASSED, null, "Pass");
        }
        Integer http = o.httpStatus();
        if (http != null && http >= 200 && http <= 299) {
            return new Diagnosis(Decision.ADOPT_2XX, null, "API trả " + http + " (thành công) nhưng khác mã AI kỳ vọng - hệ thống tự sửa kỳ vọng.");
        }
        if (http == null) {
            String err = o.errorMessage() == null ? "" : o.errorMessage();
            if (err.toLowerCase(Locale.ROOT).contains("thiếu giá trị")) {
                return new Diagnosis(Decision.HUMAN, "MISSING_PARAM_VALUE", "Thiếu giá trị cho tham số đường dẫn - nhập giá trị thật (vd id).");
            }
            return new Diagnosis(Decision.HUMAN, "EXECUTION_ERROR", "Không gọi được API đích: " + (err.isBlank() ? "không có phản hồi" : err));
        }
        if (http == 401 || http == 403) {
            return new Diagnosis(Decision.HUMAN, "AUTH", "API trả " + http + ": token mặc định không đủ quyền hoặc hết hạn - kiểm tra xác thực/vai trò, AI không sửa được.");
        }
        if (http == 404 && hasPathParams) {
            return new Diagnosis(Decision.HUMAN, "RESOURCE_NOT_FOUND",
                    "API trả 404 với tham số đường dẫn: id này không tồn tại - nhập id thật hoặc tạo trước 1 test POST tạo tài nguyên (chuỗi dữ liệu).");
        }
        if (http >= 500) {
            return new Diagnosis(Decision.HUMAN, "SERVER_ERROR", "API trả " + http + " với test hợp lệ - nghi lỗi phía API, AI không sửa được.");
        }
        return new Diagnosis(Decision.REPAIR, null, "API trả " + http + " - gửi thông báo lỗi thật cho AI sửa.");
    }

    // ---- Chạy 1 test và chờ kết quả ----

    private RunOutcome runAndWait(JobProgress p, UUID projectId, UUID testId) {
        Project project = projectService.getOwnedProject(projectId);
        TestExecutionResponse started = testExecutionService.triggerForProject(project, List.of(testId), null, null);
        long deadline = System.currentTimeMillis() + executionTimeoutMs;
        TestExecution execution = null;
        while (System.currentTimeMillis() < deadline) {
            p.checkCancelled(); // huỷ có hiệu lực ngay cả khi đang chờ lần chạy
            execution = testExecutionRepository.findById(started.id()).orElse(null);
            if (execution != null && (execution.getStatus() == ExecutionStatus.COMPLETED || execution.getStatus() == ExecutionStatus.FAILED)) {
                break;
            }
            execution = null;
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (execution == null) {
            return new RunOutcome(null, null, null, null, true);
        }
        List<TestResult> results = testResultRepository.findAllByExecutionOrderByTestCaseCreatedAt(execution);
        if (results.isEmpty()) {
            return new RunOutcome(TestResultStatus.ERROR, null, null, "Lần chạy không có kết quả", false);
        }
        TestResult r = results.get(0);
        return new RunOutcome(r.getStatus(), r.getResponseStatus(), r.getResponseBody(), r.getErrorMessage(), false);
    }

    // ---- Tiện ích ----

    private BaselineResponse human(String reason, String message, UUID testId, List<BaselineResponse.Round> rounds, int tokens, RunOutcome last) {
        return new BaselineResponse("NEEDS_HUMAN", reason, message, testId, List.copyOf(rounds), tokens, 0,
                last == null ? null : last.httpStatus(), last == null ? null : mask(last.body()));
    }

    /** Body đã được che khi lưu (SensitiveDataScanner); ở đây chỉ cắt ngắn để trả về/đưa cho AI. */
    private static String mask(String body) {
        if (body == null) {
            return null;
        }
        return body.length() > BODY_LIMIT ? body.substring(0, BODY_LIMIT) + "..." : body;
    }

    /** Giá trị người dùng nhập cho tham số đường dẫn luôn thắng giá trị AI đoán. */
    static GeneratedTestCase withUserValues(GeneratedTestCase test, Map<String, String> userValues) {
        if (userValues.isEmpty()) {
            return test;
        }
        Map<String, String> merged = new HashMap<>(test.pathParamFallbacks() == null ? Map.of() : test.pathParamFallbacks());
        merged.putAll(userValues);
        return new GeneratedTestCase(test.name(), test.description(), test.requestHeaders(), test.requestBody(), test.expectedStatus(),
                test.resolvedPath(), merged, test.authOverride(), test.rationale());
    }

    private static boolean isWriteMethod(String method) {
        if (method == null) {
            return false;
        }
        String m = method.toUpperCase(Locale.ROOT);
        return !(m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS"));
    }
}
