package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.dto.response.AssertionResultResponse;
import com.aiapitesting.backend.entity.ErrorKind;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.service.BugReportStatusService;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Module 16 - dữ liệu phụ thu được quanh 1 lần gọi target, truyền xuống finish() để lưu cùng TestResult. */
    private record Meta(Long durationMs, ErrorKind errorKind, String fingerprint) {
    }

    @Async
    public void runInBackground(
            TestExecution execution, List<TestCase> testCases, Project project,
            Map<UUID, List<DependencyEdge>> edgesByConsumerId, Set<UUID> autoIncludedIds,
            Map<UUID, List<AssertionSpec>> assertionsByTestCaseId
    ) {
        execution.setStatus(ExecutionStatus.RUNNING);
        testExecutionRepository.save(execution);

        // Trạng thái + response body của các test case đã chạy trong lần này - chỉ 1 luồng xử lý
        // tuần tự trong phạm vi 1 lần thực thi nên dùng Map thường, không cần ConcurrentHashMap.
        Map<UUID, TestResultStatus> statusByTestCaseId = new HashMap<>();
        Map<UUID, String> responseBodyByTestCaseId = new HashMap<>();
        List<TestResult> savedResults = new ArrayList<>();

        try {
            for (TestCase testCase : testCases) {
                boolean autoIncluded = autoIncludedIds.contains(testCase.getId());
                runOne(execution, testCase, project, edgesByConsumerId.getOrDefault(testCase.getId(), List.of()),
                        statusByTestCaseId, responseBodyByTestCaseId, autoIncluded,
                        assertionsByTestCaseId.getOrDefault(testCase.getId(), List.of()), savedResults);
            }
            // Module 16 - phân loại nguyên nhân fail SAU KHI cả lượt chạy xong (cần xem "anh em" cùng lượt để biết
            // N/M test cùng lỗi), RỒI MỚI chạy tự động hoá Bug Report. Trước đây onNewTestResult gọi ngay lúc lưu
            // từng kết quả - 1 lượt fail hàng loạt vì token hết hạn sẽ tự mở lại các bug đã đóng.
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
            RestAssuredTestRunner.RunResult result = restAssuredTestRunner.run(project, testCase, resolvedValues);
            Meta meta = new Meta(elapsedMillis(startedNanos), null, fingerprint);
            boolean statusMatches = Objects.equals(result.statusCode(), testCase.getExpectedStatus());

            // PASSED chỉ khi status code khớp VÀ mọi assertion đều đúng. Không chấm assertion nếu
            // status code đã sai.
            List<AssertionResultResponse> assertionResults = statusMatches
                    ? evaluateAssertions(result.responseBody(), assertionSpecs)
                    : List.of();
            boolean allAssertionsPassed = assertionResults.stream().allMatch(AssertionResultResponse::passed);
            TestResultStatus status = (statusMatches && allAssertionsPassed) ? TestResultStatus.PASSED : TestResultStatus.FAILED;

            finish(execution, testCase, status, result.statusCode(), truncate(result.responseBody()), null,
                    statusByTestCaseId, responseBodyByTestCaseId, result.responseBody(), autoIncluded,
                    writeAssertionResultsAsJson(assertionResults), meta, savedResults);
        } catch (Exception e) {
            log.warn("Loi khi goi target API cho test case {}: {}", testCase.getId(), e.toString());
            // Module 16 - loại lỗi theo LOẠI exception (timeout/không kết nối/DNS), không đọc chuỗi thông báo.
            finish(execution, testCase, TestResultStatus.ERROR, null, null,
                    "Không gọi được target API: " + e.getMessage(),
                    statusByTestCaseId, responseBodyByTestCaseId, null, autoIncluded, null,
                    new Meta(elapsedMillis(startedNanos), ErrorKindClassifier.classify(e), fingerprint), savedResults);
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
