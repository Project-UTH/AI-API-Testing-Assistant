package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.dto.response.AssertionResultResponse;
import com.aiapitesting.backend.entity.ErrorKind;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.service.BugReportStatusService;
import com.aiapitesting.backend.service.DynamicPlaceholderResolver;
import com.aiapitesting.backend.service.TestCasePathValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.path.json.JsonPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Chạy nền 1 lần TestExecution - tuần tự từng test case một theo đúng thứ tự đã được
 * TestExecutionService tính sẵn (topological sort, đã xếp DELETE chạy sau các test dùng chung
 * nguồn). Không đọc SecurityContextHolder ở đây - mọi việc cần quyền đã làm xong ở phần đồng bộ
 * trước đó, entity/dữ liệu nhận thẳng làm tham số.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestExecutionRunner {

    private static final int MAX_RESPONSE_BODY_LENGTH = 10_000;

    private final TestExecutionRepository testExecutionRepository;
    private final TestResultRepository testResultRepository;
    private final RestAssuredTestRunner restAssuredTestRunner;
    private final TestCasePathValidator testCasePathValidator;
    private final BugReportStatusService bugReportStatusService;
    private final ExecutionFailureAnalyzer executionFailureAnalyzer;
    private final com.aiapitesting.backend.repository.EndpointRepository endpointRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Module 16 - dữ liệu phụ thu được quanh 1 lần gọi target, truyền xuống finish() để lưu cùng TestResult. */
    private record Meta(Long durationMs, ErrorKind errorKind, String fingerprint, String schemaViolationsJson, String securityFindingsJson,
                        String specDeviationsJson, String rejectionExcerpt) {
        Meta(Long durationMs, ErrorKind errorKind, String fingerprint) {
            this(durationMs, errorKind, fingerprint, null, null, null, null);
        }
    }

    public void runInBackground(
            TestExecution execution, List<TestCase> testCases, Project project,
            Map<UUID, List<DependencyEdge>> edgesByConsumerId, Set<UUID> autoIncludedIds,
            Map<UUID, List<AssertionSpec>> assertionsByTestCaseId
    ) {
        runInBackground(execution, testCases, project, edgesByConsumerId, autoIncludedIds, assertionsByTestCaseId, false);
    }

    @Async
    public void runInBackground(
            TestExecution execution, List<TestCase> testCases, Project project,
            Map<UUID, List<DependencyEdge>> edgesByConsumerId, Set<UUID> autoIncludedIds,
            Map<UUID, List<AssertionSpec>> assertionsByTestCaseId, boolean cleanupCreated
    ) {
        execution.setStatus(ExecutionStatus.RUNNING);
        testExecutionRepository.save(execution);

        // Trạng thái + response body của các test case đã chạy trong lần này - chỉ 1 luồng xử lý
        // tuần tự trong phạm vi 1 lần thực thi nên dùng Map thường, không cần ConcurrentHashMap.
        Map<UUID, TestResultStatus> statusByTestCaseId = new HashMap<>();
        Map<UUID, String> responseBodyByTestCaseId = new HashMap<>();
        List<TestResult> savedResults = new ArrayList<>();
        List<CreatedResource> createdResources = new ArrayList<>();

        try {
            Map<UUID, TestCase> testCaseById = new HashMap<>();
            testCases.forEach(t -> testCaseById.putIfAbsent(t.getId(), t));
            for (TestCase testCase : testCases) {
                boolean autoIncluded = autoIncludedIds.contains(testCase.getId());
                List<DependencyEdge> ownEdges = edgesByConsumerId.getOrDefault(testCase.getId(), List.of());
                if (!autoIncluded && isIsolatedConsumer(testCase) && !ownEdges.isEmpty()) {
                    refreshCreatedSources(ownEdges, testCaseById, edgesByConsumerId, project, statusByTestCaseId, responseBodyByTestCaseId, createdResources);
                }
                runOne(execution, testCase, project, edgesByConsumerId.getOrDefault(testCase.getId(), List.of()),
                        statusByTestCaseId, responseBodyByTestCaseId, autoIncluded,
                        assertionsByTestCaseId.getOrDefault(testCase.getId(), List.of()), savedResults);
                if (cleanupCreated) {
                    registerOriginalParent(testCase, edgesByConsumerId, statusByTestCaseId, responseBodyByTestCaseId, createdResources);
                }
            }
            // Module 16 - phân loại nguyên nhân fail SAU KHI cả lượt chạy xong (cần xem "anh em" cùng lượt để biết
            // N/M test cùng lỗi), RỒI MỚI chạy tự động hoá Bug Report. Trước đây onNewTestResult gọi ngay lúc lưu
            // từng kết quả - 1 lượt fail hàng loạt vì token hết hạn sẽ tự mở lại các bug đã đóng.
            if (cleanupCreated) {
                cleanupCreatedResources(project, createdResources);
            }
            executionFailureAnalyzer.analyze(execution, savedResults);
            savedResults.forEach(bugReportStatusService::onNewTestResult);
            execution.setStatus(ExecutionStatus.COMPLETED);
        } catch (Exception e) {
            log.error("Loi khong mong doi khi thuc thi execution {}", execution.getId(), e);
            execution.setStatus(ExecutionStatus.FAILED);
        } finally {
            execution.setFinishedAt(Instant.now());
            testExecutionRepository.save(execution);
        }
    }

    /** Test POST nguồn của {{id}} đã chạy PASSED: bản ghi nó vừa tạo cũng là do lần chạy này tạo nên được dọn cùng (chỉ khi bật dọn dẹp). */
    private void registerOriginalParent(
            TestCase testCase, Map<UUID, List<DependencyEdge>> edgesByConsumerId, Map<UUID, TestResultStatus> statusByTestCaseId,
            Map<UUID, String> responseBodyByTestCaseId, List<CreatedResource> createdResources
    ) {
        if (testCase.getEndpoint() == null || !"POST".equalsIgnoreCase(testCase.getEndpoint().getMethod())
                || statusByTestCaseId.get(testCase.getId()) != TestResultStatus.PASSED) {
            return;
        }
        String jsonPath = edgesByConsumerId.values().stream().flatMap(List::stream)
                .filter(e -> e.sourceTestCaseId().equals(testCase.getId())).map(DependencyEdge::jsonPath).findFirst().orElse(null);
        if (jsonPath != null) {
            createdResources.add(new CreatedResource(testCase.getEndpoint().getPath(), extractJsonPathValue(responseBodyByTestCaseId.get(testCase.getId()), jsonPath)));
        }
    }

    /** Tài nguyên cha do chính lần chạy này tạo (đường dẫn endpoint POST + id trích từ phản hồi) - đối tượng duy nhất được phép xoá khi dọn dẹp. */
    private record CreatedResource(String createPath, String id) {
    }

    /**
     * Dọn dẹp TUỲ CHỌN: xoá các tài nguyên cha do chính lần chạy này tạo, bằng endpoint DELETE có đường dẫn "{createPath}/{tham số}" của project. Không có
     * endpoint DELETE tương ứng hoặc không trích được id thì bỏ qua; lỗi khi xoá chỉ ghi log, không đổi kết quả test, không bao giờ xoá thứ gì khác.
     */
    private void cleanupCreatedResources(Project project, List<CreatedResource> created) {
        int deleted = 0;
        int skipped = 0;
        for (CreatedResource resource : created) {
            if (resource.id() == null || resource.id().isBlank()) {
                skipped++;
                continue;
            }
            Endpoint deleteEndpoint = endpointRepository.findAllByProjectOrderByCreatedAtAsc(project).stream()
                    .filter(e -> "DELETE".equalsIgnoreCase(e.getMethod()))
                    .filter(e -> e.getPath().matches(java.util.regex.Pattern.quote(resource.createPath()) + "/\\{[^/}]+}"))
                    .findFirst().orElse(null);
            if (deleteEndpoint == null) {
                skipped++;
                continue;
            }
            try {
                TestCase teardown = TestCase.builder().endpoint(deleteEndpoint)
                        .resolvedPath(deleteEndpoint.getPath().replaceAll("\\{[^/}]+}", java.util.regex.Matcher.quoteReplacement(resource.id())))
                        .expectedStatus(204).build();
                RestAssuredTestRunner.RunResult r = restAssuredTestRunner.run(project, teardown, Map.of());
                if (r.statusCode() >= 200 && r.statusCode() < 300) {
                    deleted++;
                } else {
                    log.warn("Don dep that bai: DELETE {} tra {}", teardown.getResolvedPath(), r.statusCode());
                }
            } catch (RuntimeException e) {
                log.warn("Don dep that bai cho tai nguyen cua {}: {}", resource.createPath(), e.toString());
            }
        }
        log.info("Don dep tai nguyen do lan chay tao: xoa {}, bo qua {}", deleted, skipped);
    }

    /** Test do hệ thống sinh (bù checklist) và test gốc AI: mỗi test dùng tài nguyên cha RIÊNG để không cộng dồn trạng thái lên nhau. */
    private static boolean isIsolatedConsumer(TestCase testCase) {
        return testCase.getGenerationOrigin() == TestCaseGenerationOrigin.SYSTEM_FILLED
                || testCase.getGenerationOrigin() == TestCaseGenerationOrigin.AI_BASELINE;
    }

    /**
     * Gọi LẠI test POST tạo tài nguyên (nguồn của {{id}}) ngay trước test con, để test con chạy trên tài nguyên cha MỚI: tránh lỗi 409/cộng dồn khi
     * nhiều test cùng tạo con trên 1 cha (vd tổng trọng số rubric) và tránh test DELETE xoá mất cha dùng chung của các test sau. Đây là bước chuẩn bị,
     * KHÔNG lưu thành kết quả test và không đổi kết luận. Chỉ áp cho nguồn là POST không phụ thuộc test khác; nguồn gốc đã không PASSED thì bỏ qua
     * (test con sẽ BLOCKED như cũ). Lỗi khi chuẩn bị thì giữ tài nguyên cũ, không giấu lỗi của test con.
     */
    private void refreshCreatedSources(
            List<DependencyEdge> edges, Map<UUID, TestCase> testCaseById, Map<UUID, List<DependencyEdge>> edgesByConsumerId,
            Project project, Map<UUID, TestResultStatus> statusByTestCaseId, Map<UUID, String> responseBodyByTestCaseId,
            List<CreatedResource> createdResources
    ) {
        for (UUID sourceId : edges.stream().map(DependencyEdge::sourceTestCaseId).distinct().toList()) {
            TestCase source = testCaseById.get(sourceId);
            if (source == null || source.getEndpoint() == null || !"POST".equalsIgnoreCase(source.getEndpoint().getMethod())
                    || !edgesByConsumerId.getOrDefault(sourceId, List.of()).isEmpty()
                    || statusByTestCaseId.get(sourceId) != TestResultStatus.PASSED) {
                continue;
            }
            Set<String> placeholders = new LinkedHashSet<>();
            placeholders.addAll(testCasePathValidator.extractPlaceholders(source.getResolvedPath()));
            placeholders.addAll(testCasePathValidator.extractPlaceholders(source.getRequestBody()));
            placeholders.addAll(testCasePathValidator.extractPlaceholders(source.getRequestHeaders()));
            Map<String, String> fallbacks = parseFallbacks(source.getPathParamFallbacks());
            if (!fallbacks.keySet().containsAll(placeholders)) {
                continue;
            }
            try {
                RestAssuredTestRunner.RunResult fresh = restAssuredTestRunner.run(project, source, fallbacks);
                if (fresh.statusCode() >= 200 && fresh.statusCode() < 300 && fresh.responseBody() != null) {
                    responseBodyByTestCaseId.put(sourceId, fresh.responseBody());
                    String jsonPath = edges.stream().filter(e -> e.sourceTestCaseId().equals(sourceId)).map(DependencyEdge::jsonPath).findFirst().orElse("$.id");
                    createdResources.add(new CreatedResource(source.getEndpoint().getPath(), extractJsonPathValue(fresh.responseBody(), jsonPath)));
                }
            } catch (RuntimeException e) {
                log.warn("Khong tao duoc tai nguyen cha moi cho test con (giu tai nguyen cu): {}", e.toString());
            }
        }
    }

    private void runOne(
            TestExecution execution, TestCase testCase, Project project, List<DependencyEdge> edges,
            Map<UUID, TestResultStatus> statusByTestCaseId, Map<UUID, String> responseBodyByTestCaseId,
            boolean autoIncluded, List<AssertionSpec> assertionSpecs, List<TestResult> savedResults
    ) {
        String fingerprint = TestCaseFingerprint.of(testCase, assertionSpecs, edges);
        Meta noCallMeta = new Meta(null, null, fingerprint);
        Set<String> placeholders = new LinkedHashSet<>();
        placeholders.addAll(testCasePathValidator.extractPlaceholders(testCase.getResolvedPath()));
        placeholders.addAll(testCasePathValidator.extractPlaceholders(testCase.getRequestBody()));
        placeholders.addAll(testCasePathValidator.extractPlaceholders(testCase.getRequestHeaders()));

        Map<String, DependencyEdge> edgeByPlaceholder = new HashMap<>();
        edges.forEach(edge -> edgeByPlaceholder.put(edge.placeholderName(), edge));
        Map<String, String> fallbacks = parseFallbacks(testCase.getPathParamFallbacks());

        Map<String, String> resolvedValues = new HashMap<>();
        for (String paramName : placeholders) {
            DependencyEdge edge = edgeByPlaceholder.get(paramName);
            if (edge == null) {
                // Không có dependency tường minh cho tham số này - chỉ còn nguồn dự phòng.
                String fallback = fallbacks.get(paramName);
                if (fallback == null) {
                    finish(execution, testCase, TestResultStatus.ERROR, null, null,
                            "Thiếu giá trị cho tham số " + paramName + " - không có dependency cũng không có giá trị dự phòng",
                            statusByTestCaseId, responseBodyByTestCaseId, null, autoIncluded, null, noCallMeta, savedResults);
                    return;
                }
                resolvedValues.put(paramName, fallback);
                continue;
            }

            // Dependency đã khai báo tường minh nghĩa là người dùng muốn giá trị thật - lỗi thì phải
            // biết (BLOCKED), không được âm thầm rơi về giá trị dự phòng.
            TestResultStatus sourceStatus = statusByTestCaseId.get(edge.sourceTestCaseId());
            if (sourceStatus != TestResultStatus.PASSED) {
                finish(execution, testCase, TestResultStatus.BLOCKED, null, null,
                        "Test case nguồn '" + edge.sourceTestCaseName() + "' không PASSED",
                        statusByTestCaseId, responseBodyByTestCaseId, null, autoIncluded, null, noCallMeta, savedResults);
                return;
            }
            String sourceResponseBody = responseBodyByTestCaseId.get(edge.sourceTestCaseId());
            String value = extractJsonPathValue(sourceResponseBody, edge.jsonPath());
            if (value == null) {
                finish(execution, testCase, TestResultStatus.BLOCKED, null, null,
                        "Không trích được giá trị theo JSONPath '" + edge.jsonPath() + "' từ response của test case nguồn '"
                                + edge.sourceTestCaseName() + "'",
                        statusByTestCaseId, responseBodyByTestCaseId, null, autoIncluded, null, noCallMeta, savedResults);
                return;
            }
            resolvedValues.put(paramName, value);
        }

        long startedNanos = System.nanoTime();
        try {
            RestAssuredTestRunner.RunResult result;
            if (testCase.isSendTwice()) {
                // 17q G3 (luật UNIQUE): gửi 2 lần cùng giá trị {{uuid:x}}; lần 1 phải tạo được (2xx) thì lần 2 mới có nghĩa để chấm.
                DynamicPlaceholderResolver.RunScope scope = new DynamicPlaceholderResolver.RunScope();
                RestAssuredTestRunner.RunResult first = restAssuredTestRunner.run(project, testCase, resolvedValues, scope);
                if (first.statusCode() < 200 || first.statusCode() > 299) {
                    finish(execution, testCase, TestResultStatus.BLOCKED, first.statusCode(), null,
                            "Lần gửi đầu trả " + first.statusCode() + " (không tạo được bản ghi gốc) nên không kiểm được việc tạo trùng",
                            statusByTestCaseId, responseBodyByTestCaseId, null, autoIncluded, null, noCallMeta, savedResults);
                    return;
                }
                result = restAssuredTestRunner.run(project, testCase, resolvedValues, scope);
            } else {
                result = restAssuredTestRunner.run(project, testCase, resolvedValues);
            }
            // Test probe mass-assignment (17b-2) tự chấm theo phản hồi (400/422 = từ chối = đạt; phản chiếu field lạ = Fail); các mã khác
            // (401/403/404...) không kết luận được nên rơi về luật mã trạng thái thường.
            MassAssignmentProbe.Outcome probe = probeOutcomeOf(testCase, result);
            boolean statusMatches = probe != null && probe.verdict() != MassAssignmentProbe.Verdict.INCONCLUSIVE
                    ? probe.verdict() == MassAssignmentProbe.Verdict.PASS
                    : ExpectedStatus.matches(testCase, result.statusCode(), project.getUnauthenticatedStatus());
            // Quét 1 lần trên body THÔ; body lưu/hiển thị là bản đã che. Assertion + dependency vẫn dùng body thô (bên dưới).
            SensitiveDataScanner.Scan scan = scanSensitive(testCase, result.responseBody());
            Meta meta = new Meta(elapsedMillis(startedNanos), null, fingerprint,
                    statusMatches ? schemaViolationsJsonOf(testCase, result.statusCode(), result.responseBody()) : null,
                    securityFindingsJsonOf(result.statusCode(), scan, probeFindingsOf(probe), SensitiveDataScanner.scanErrorLeak(result.responseBody())),
                    specDeviationsJsonOf(testCase, result.statusCode()),
                    rejectionExcerptOf(result.statusCode(), scan.maskedBody() != null ? scan.maskedBody() : result.responseBody()));

            // PASSED chỉ khi status code khớp VÀ mọi assertion đều đúng. Không chấm assertion nếu
            // status code đã sai.
            List<AssertionResultResponse> assertionResults = statusMatches
                    ? maskAssertionValues(evaluateAssertions(result.responseBody(), assertionSpecs), scan)
                    : List.of();
            boolean allAssertionsPassed = assertionResults.stream().allMatch(AssertionResultResponse::passed);
            TestResultStatus status = (statusMatches && allAssertionsPassed) ? TestResultStatus.PASSED : TestResultStatus.FAILED;

            // Che TRƯỚC khi cắt ngắn: cắt trước sẽ làm JSON hỏng và bí mật nằm lại trong text.
            finish(execution, testCase, status, result.statusCode(),
                    truncate(scan.maskedBody() != null ? scan.maskedBody() : result.responseBody()), null,
                    statusByTestCaseId, responseBodyByTestCaseId, result.responseBody(), autoIncluded,
                    writeAssertionResultsAsJson(assertionResults), meta, savedResults);
        } catch (Exception e) {
            log.warn("Loi khi goi target API cho test case {}: {}", testCase.getId(), e.toString());
            // Module 16 - loại lỗi theo LOẠI exception (timeout/không kết nối/DNS), không đọc chuỗi thông báo.
            finish(execution, testCase, TestResultStatus.ERROR, null, null,
                    "Không gọi được target API: " + e.getMessage(),
                    statusByTestCaseId, responseBodyByTestCaseId, null, autoIncluded, null,
                    new Meta(elapsedMillis(startedNanos),
                            e instanceof AuthLoginException login && login.isConfigProblem() ? null : ErrorKindClassifier.classify(e), fingerprint),
                    savedResults);
        }
    }

    /**
     * Cảnh báo phản hồi lệch schema của spec - CHỈ khi mã trạng thái đúng kỳ vọng và là 2xx có schema. Mọi lỗi ở bước này
     * (schema hỏng, endpoint không đọc được) chỉ bỏ qua: tính năng cảnh báo không được làm hỏng lượt chạy.
     */
    /**
     * Module 17 (17b) - quét dữ liệu nhạy cảm trong response (mọi mã trạng thái, để che body lưu lại). Mọi lỗi chỉ bỏ qua (ghi log): tính
     * năng cảnh báo không được làm hỏng lượt chạy.
     */
    private SensitiveDataScanner.Scan scanSensitive(TestCase testCase, String responseBody) {
        try {
            if (testCase.getEndpoint() == null) {
                return SensitiveDataScanner.Scan.EMPTY;
            }
            var endpoint = testCase.getEndpoint();
            return SensitiveDataScanner.scanAndMask(endpoint.getPath(), endpoint.getMethod(), endpoint.effectiveRequiresAuth(),
                    testCase.getRequestBody(), responseBody);
        } catch (Exception e) {
            log.warn("Khong quet duoc du lieu nhay cam cho test case {}: {}", testCase.getId(), e.toString());
            return SensitiveDataScanner.Scan.EMPTY;
        }
    }

    /** Cảnh báo quét dữ liệu nhạy cảm chỉ lấy từ response 2xx; phát hiện của probe (17b-2) luôn được ghi. CHỈ có đường dẫn/quy tắc/mô tả, không có giá trị. */
    private String securityFindingsJsonOf(Integer statusCode, SensitiveDataScanner.Scan scan, List<SensitiveDataScanner.Finding> probeFindings,
                                         List<SensitiveDataScanner.Finding> errorLeaks) {
        try {
            // Rò rỉ chi tiết lỗi (stack trace/SQL/tên lớp) hay nằm ở response 4xx/5xx nên được ghi ở MỌI mã trạng thái.
            List<SensitiveDataScanner.Finding> all = new ArrayList<>(probeFindings);
            all.addAll(errorLeaks);
            if (statusCode != null && statusCode >= 200 && statusCode <= 299) {
                all.addAll(scan.findings());
            }
            return all.isEmpty() ? null : objectMapper.writeValueAsString(all);
        } catch (Exception e) {
            return null;
        }
    }

    /** Chấm probe mass-assignment; null nếu test case không phải probe hoặc không đọc được danh sách field đã tiêm. */
    private MassAssignmentProbe.Outcome probeOutcomeOf(TestCase testCase, RestAssuredTestRunner.RunResult result) {
        if (HttpRobustnessProbe.KIND.equals(testCase.getProbeKind())) {
            HttpRobustnessProbe.Variant variant = HttpRobustnessProbe.variantOf(testCase.getProbeFieldsJson());
            return variant == null ? null : HttpRobustnessProbe.evaluate(variant, result.statusCode());
        }
        if (!MassAssignmentProbe.KIND.equals(testCase.getProbeKind()) || testCase.getProbeFieldsJson() == null) {
            return null;
        }
        try {
            List<MassAssignmentProbe.Field> fields = objectMapper.readValue(testCase.getProbeFieldsJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<MassAssignmentProbe.Field>>() {
                    });
            return MassAssignmentProbe.evaluate(result.statusCode(), result.responseBody(), fields);
        } catch (Exception e) {
            log.warn("Khong doc duoc field probe cua test case {}: {}", testCase.getId(), e.toString());
            return null;
        }
    }

    private List<SensitiveDataScanner.Finding> probeFindingsOf(MassAssignmentProbe.Outcome probe) {
        if (probe == null || probe.verdict() != MassAssignmentProbe.Verdict.FAIL) {
            return List.of();
        }
        if (probe.finding() != null) {
            return List.of(probe.finding());
        }
        if (probe.reflected().isEmpty()) {
            return List.of(new SensitiveDataScanner.Finding("(request)", MassAssignmentProbe.RULE_SERVER_ERROR,
                    SensitiveDataScanner.Severity.HIGH, probe.detail()));
        }
        return probe.reflected().stream()
                .map(f -> new SensitiveDataScanner.Finding(f.name(), MassAssignmentProbe.RULE_ACCEPTED, SensitiveDataScanner.Severity.HIGH,
                        "API nhận field '" + f.name() + "' không khai báo trong schema (giá trị đánh dấu xuất hiện lại trong phản hồi)"))
                .toList();
    }

    /** Kết quả assertion lưu "giá trị thực tế": nếu đúng là giá trị nhạy cảm đã che thì thay bằng dấu che. */
    private List<AssertionResultResponse> maskAssertionValues(List<AssertionResultResponse> results, SensitiveDataScanner.Scan scan) {
        if (scan.sensitiveValues().isEmpty()) {
            return results;
        }
        return results.stream().map(r -> r.actualValue() != null && scan.sensitiveValues().contains(r.actualValue())
                ? new AssertionResultResponse(r.jsonPath(), r.operator(), r.expectedValue(), SensitiveDataScanner.MASK_MARKER, r.passed())
                : r).toList();
    }

    private String schemaViolationsJsonOf(TestCase testCase, Integer statusCode, String responseBody) {
        try {
            if (statusCode == null || statusCode < 200 || statusCode > 299 || testCase.getEndpoint() == null) {
                return null;
            }
            String allSchemas = testCase.getEndpoint().getResponseSchemas();
            if (allSchemas == null || allSchemas.isBlank()) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode schema = objectMapper.readTree(allSchemas).get(String.valueOf(statusCode));
            if (schema == null) {
                return null;
            }
            List<String> violations = ResponseSchemaValidator.validate(schema.toString(), responseBody);
            return violations.isEmpty() ? null : objectMapper.writeValueAsString(violations);
        } catch (Exception e) {
            log.warn("Khong doi chieu duoc schema response cua test case {}: {}", testCase.getId(), e.toString());
            return null;
        }
    }

    /**
     * 17q G1 - cảnh báo mã thành công lệch spec, tính ĐỘC LẬP với việc mã có khớp kỳ vọng hay không (kỳ vọng có thể đã bị đổi
     * theo mã API trả nên "khớp" không có nghĩa là đúng spec). Lỗi ở bước này chỉ bỏ qua.
     */
    private String specDeviationsJsonOf(TestCase testCase, Integer statusCode) {
        try {
            if (testCase.getEndpoint() == null || testCase.getProbeKind() != null) {
                return null;
            }
            return SpecStatusDeviation.check(SpecStatusDeviation.declaredStatusCodes(testCase.getEndpoint().getSchema()),
                            testCase.getExpectedStatus(), statusCode)
                    .map(warning -> {
                        try {
                            return objectMapper.writeValueAsString(List.of(warning));
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Khong doi chieu duoc ma trang thai voi spec cho test case {}: {}", testCase.getId(), e.toString());
            return null;
        }
    }

    /**
     * 17q G2 - đoạn trích lý do API từ chối đầu vào (chỉ phản hồi 4xx, đọc trên body ĐÃ CHE dữ liệu nhạy cảm). Coverage dùng nó để
     * biết 1 test âm Pass vì API từ chối ĐÚNG field đang kiểm (oracle mạnh) hay chỉ trùng hợp bị từ chối vì lý do khác.
     */
    private static String rejectionExcerptOf(Integer statusCode, String maskedBody) {
        if (statusCode == null || statusCode < 400 || statusCode > 499) {
            return null;
        }
        try {
            return InputRejectionDetector.excerptIfRejected(maskedBody);
        } catch (Exception e) {
            return null;
        }
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * Lưu kết quả + cập nhật 2 map trạng thái dùng cho các test case chạy sau (cùng thứ tự tuần tự). Bug Report KHÔNG
     * còn được cập nhật ở đây (xem runInBackground) - phải chờ phân loại nguyên nhân fail xong.
     */
    private void finish(
            TestExecution execution, TestCase testCase, TestResultStatus status,
            Integer responseStatus, String truncatedResponseBody, String errorMessage,
            Map<UUID, TestResultStatus> statusByTestCaseId, Map<UUID, String> responseBodyByTestCaseId,
            String rawResponseBodyForDependents, boolean autoIncluded, String assertionResultsJson,
            Meta meta, List<TestResult> savedResults
    ) {
        TestResult result = TestResult.builder()
                .execution(execution)
                .testCase(testCase)
                .status(status)
                .responseStatus(responseStatus)
                .responseBody(truncatedResponseBody)
                .errorMessage(errorMessage)
                .autoIncluded(autoIncluded)
                .assertionResultsJson(assertionResultsJson)
                .durationMs(meta.durationMs())
                .errorKind(meta.errorKind())
                .testCaseFingerprint(meta.fingerprint())
                .schemaViolationsJson(meta.schemaViolationsJson())
                .securityFindingsJson(meta.securityFindingsJson())
                .specDeviationsJson(meta.specDeviationsJson())
                .rejectionExcerpt(meta.rejectionExcerpt())
                .build();
        testResultRepository.save(result);
        savedResults.add(result);

        statusByTestCaseId.put(testCase.getId(), status);
        if (rawResponseBodyForDependents != null) {
            responseBodyByTestCaseId.put(testCase.getId(), rawResponseBodyForDependents);
        }
    }

    /**
     * Chấm từng assertion theo operator - EXISTS chỉ cần khác null, EQUALS/CONTAINS so chuỗi, TYPE
     * so kiểu Java runtime của giá trị trích được (không stringify trước, khác 3 operator kia).
     */
    private List<AssertionResultResponse> evaluateAssertions(String responseBody, List<AssertionSpec> specs) {
        List<AssertionResultResponse> results = new ArrayList<>();
        for (AssertionSpec spec : specs) {
            Object raw = extractJsonPathRaw(responseBody, spec.jsonPath());
            String actual = raw == null ? null : String.valueOf(raw);
            boolean passed = switch (spec.operator()) {
                case EXISTS -> raw != null;
                case EQUALS -> actual != null && actual.equals(spec.expectedValue());
                case CONTAINS -> actual != null && spec.expectedValue() != null && actual.contains(spec.expectedValue());
                case TYPE -> describeType(raw).equalsIgnoreCase(
                        spec.expectedValue() == null ? "" : spec.expectedValue().trim());
            };
            results.add(new AssertionResultResponse(spec.jsonPath(), spec.operator(), spec.expectedValue(), actual, passed));
        }
        return results;
    }

    private String describeType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof List) {
            return "array";
        }
        if (value instanceof Map) {
            return "object";
        }
        return "string";
    }

    private String writeAssertionResultsAsJson(List<AssertionResultResponse> assertionResults) {
        if (assertionResults == null || assertionResults.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(assertionResults);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonPathValue(String responseBody, String jsonPath) {
        Object value = extractJsonPathRaw(responseBody, jsonPath);
        return value == null ? null : String.valueOf(value);
    }

    private Object extractJsonPathRaw(String responseBody, String jsonPath) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            return JsonPath.from(responseBody).get(normalizeJsonPath(jsonPath));
        } catch (Exception e) {
            return null;
        }
    }

    /** io.restassured.path.json.JsonPath dùng cú pháp GPath ("id", không phải "$.id") - bỏ tiền tố $ nếu có. */
    private String normalizeJsonPath(String jsonPath) {
        if (jsonPath.startsWith("$.")) {
            return jsonPath.substring(2);
        }
        if (jsonPath.startsWith("$")) {
            return jsonPath.substring(1);
        }
        return jsonPath;
    }

    private Map<String, String> parseFallbacks(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String truncate(String text) {
        if (text == null || text.length() <= MAX_RESPONSE_BODY_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_RESPONSE_BODY_LENGTH);
    }
}
