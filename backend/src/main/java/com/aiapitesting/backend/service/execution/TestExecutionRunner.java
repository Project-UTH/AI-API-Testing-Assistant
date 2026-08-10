package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.service.TestCasePathValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.path.json.JsonPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async
    public void runInBackground(
            TestExecution execution, List<TestCase> testCases, Project project,
            Map<UUID, List<DependencyEdge>> edgesByConsumerId, Set<UUID> autoIncludedIds
    ) {
        execution.setStatus(ExecutionStatus.RUNNING);
        testExecutionRepository.save(execution);

        // Trạng thái + response body của các test case đã chạy trong lần này - chỉ 1 luồng xử lý
        // tuần tự trong phạm vi 1 lần thực thi nên dùng Map thường, không cần ConcurrentHashMap.
        Map<UUID, TestResultStatus> statusByTestCaseId = new HashMap<>();
        Map<UUID, String> responseBodyByTestCaseId = new HashMap<>();

        try {
            for (TestCase testCase : testCases) {
                boolean autoIncluded = autoIncludedIds.contains(testCase.getId());
                runOne(execution, testCase, project, edgesByConsumerId.getOrDefault(testCase.getId(), List.of()),
                        statusByTestCaseId, responseBodyByTestCaseId, autoIncluded);
            }
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
            boolean autoIncluded
    ) {
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
                            statusByTestCaseId, responseBodyByTestCaseId, null, autoIncluded);
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
                        statusByTestCaseId, responseBodyByTestCaseId, null, autoIncluded);
                return;
            }
            String sourceResponseBody = responseBodyByTestCaseId.get(edge.sourceTestCaseId());
            String value = extractJsonPathValue(sourceResponseBody, edge.jsonPath());
            if (value == null) {
                finish(execution, testCase, TestResultStatus.BLOCKED, null, null,
                        "Không trích được giá trị theo JSONPath '" + edge.jsonPath() + "' từ response của test case nguồn '"
                                + edge.sourceTestCaseName() + "'",
                        statusByTestCaseId, responseBodyByTestCaseId, null, autoIncluded);
                return;
            }
            resolvedValues.put(paramName, value);
        }

        try {
            RestAssuredTestRunner.RunResult result = restAssuredTestRunner.run(project, testCase, resolvedValues);
            TestResultStatus status = Objects.equals(result.statusCode(), testCase.getExpectedStatus())
                    ? TestResultStatus.PASSED : TestResultStatus.FAILED;
            finish(execution, testCase, status, result.statusCode(), truncate(result.responseBody()), null,
                    statusByTestCaseId, responseBodyByTestCaseId, result.responseBody(), autoIncluded);
        } catch (Exception e) {
            log.warn("Loi khi goi target API cho test case {}: {}", testCase.getId(), e.toString());
            finish(execution, testCase, TestResultStatus.ERROR, null, null,
                    "Không gọi được target API: " + e.getMessage(),
                    statusByTestCaseId, responseBodyByTestCaseId, null, autoIncluded);
        }
    }

    /** Lưu kết quả + cập nhật 2 map trạng thái dùng cho các test case chạy sau (cùng thứ tự tuần tự). */
    private void finish(
            TestExecution execution, TestCase testCase, TestResultStatus status,
            Integer responseStatus, String truncatedResponseBody, String errorMessage,
            Map<UUID, TestResultStatus> statusByTestCaseId, Map<UUID, String> responseBodyByTestCaseId,
            String rawResponseBodyForDependents, boolean autoIncluded
    ) {
        TestResult result = TestResult.builder()
                .execution(execution)
                .testCase(testCase)
                .status(status)
                .responseStatus(responseStatus)
                .responseBody(truncatedResponseBody)
                .errorMessage(errorMessage)
                .autoIncluded(autoIncluded)
                .build();
        testResultRepository.save(result);

        statusByTestCaseId.put(testCase.getId(), status);
        if (rawResponseBodyForDependents != null) {
            responseBodyByTestCaseId.put(testCase.getId(), rawResponseBodyForDependents);
        }
    }

    private String extractJsonPathValue(String responseBody, String jsonPath) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            Object value = JsonPath.from(responseBody).get(normalizeJsonPath(jsonPath));
            return value == null ? null : String.valueOf(value);
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
