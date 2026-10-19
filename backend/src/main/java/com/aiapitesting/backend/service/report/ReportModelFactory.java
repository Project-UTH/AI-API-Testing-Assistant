package com.aiapitesting.backend.service.report;

import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Module 17 (17d) - dựng {@link ReportModel} từ kết quả chạy. Đây là NÚT CỔ CHAI chống rò rỉ: chỉ lấy field trong whitelist (tên test,
 * method/path, trạng thái, mã trả về, thời gian, nguyên nhân + bằng chứng), mọi chuỗi tự do đi qua {@link SecretMasker}. Cố ý KHÔNG
 * đọc requestHeaders/requestBody/responseBody của test case hay kết quả.
 */
@Component
public class ReportModelFactory {

    static final int MAX_MESSAGE_LENGTH = 300;
    static final int MAX_EVIDENCE_LENGTH = 400;
    static final int MAX_EVIDENCE_LINES = 8;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReportModel build(Project project, TestExecution execution, List<TestResult> results, ExecutionConclusionCalculator.FailOn failOn) {
        ExecutionConclusionCalculator.Result conclusion = ExecutionConclusionCalculator.calculate(execution.getStatus(), results, failOn);

        Map<String, List<ReportModel.Case>> byEndpoint = new LinkedHashMap<>();
        Map<String, String[]> endpointNames = new LinkedHashMap<>();
        for (TestResult result : results) {
            TestCase testCase = result.getTestCase();
            String method = testCase.getEndpoint() == null ? "" : testCase.getEndpoint().getMethod();
            String path = testCase.getEndpoint() == null ? "" : testCase.getEndpoint().getPath();
            String key = method + " " + path;
            endpointNames.putIfAbsent(key, new String[]{method, path});
            byEndpoint.computeIfAbsent(key, k -> new ArrayList<>()).add(toCase(result, testCase));
        }
        List<ReportModel.EndpointGroup> groups = new ArrayList<>();
        byEndpoint.forEach((key, cases) -> groups.add(new ReportModel.EndpointGroup(
                SecretMasker.mask(endpointNames.get(key)[0], 20), SecretMasker.mask(endpointNames.get(key)[1], 300), cases)));

        return new ReportModel(
                SecretMasker.mask(project.getName(), 200), execution.getId(), execution.getStartedAt(), execution.getFinishedAt(),
                execution.getStatus(), SecretMasker.mask(execution.getTargetBaseUrlSnapshot(), 300),
                conclusion.summary(), conclusion.conclusion(), groups);
    }

    private ReportModel.Case toCase(TestResult result, TestCase testCase) {
        FailureCause cause = result.getFailureCause();
        return new ReportModel.Case(
                SecretMasker.mask(testCase.getName(), 255), result.getStatus(), testCase.getExpectedStatus(), result.getResponseStatus(),
                result.getDurationMs(),
                cause == null ? null : cause.name(), cause == null ? null : cause.label(),
                cause != null && cause.isNotApiFault(),
                Boolean.TRUE.equals(result.getUnstable()) || cause == FailureCause.FLAKY,
                messageOf(result, testCase), evidenceOf(result), findingsOf(result));
    }

    private String messageOf(TestResult result, TestCase testCase) {
        return switch (result.getStatus()) {
            case PASSED -> null;
            case FAILED -> "Mã trả về " + (result.getResponseStatus() == null ? "—" : result.getResponseStatus())
                    + ", kỳ vọng " + testCase.getExpectedStatus();
            default -> SecretMasker.mask(result.getErrorMessage(), MAX_MESSAGE_LENGTH);
        };
    }

    /** Chỉ lấy đường dẫn + quy tắc + mô tả (không bao giờ có giá trị), vẫn qua SecretMasker và giới hạn số dòng. */
    private List<String> findingsOf(TestResult result) {
        if (result.getSecurityFindingsJson() == null || result.getSecurityFindingsJson().isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, String>> raw = objectMapper.readValue(result.getSecurityFindingsJson(), new TypeReference<List<Map<String, String>>>() {
            });
            return raw.stream().limit(MAX_EVIDENCE_LINES)
                    .map(f -> SecretMasker.mask(f.get("path") + " [" + f.get("rule") + "] - " + f.get("detail"), MAX_EVIDENCE_LENGTH))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> evidenceOf(TestResult result) {
        if (result.getFailureEvidence() == null || result.getFailureEvidence().isBlank()) {
            return List.of();
        }
        try {
            List<String> lines = objectMapper.readValue(result.getFailureEvidence(), new TypeReference<List<String>>() {
            });
            return lines.stream().limit(MAX_EVIDENCE_LINES).map(l -> SecretMasker.mask(l, MAX_EVIDENCE_LENGTH)).toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
