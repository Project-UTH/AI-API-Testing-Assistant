package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.dto.response.AssertionResultResponse;
import com.aiapitesting.backend.entity.ErrorKind;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Module 16 - gắn nguyên nhân nghi ngờ (FailureCause + bằng chứng) cho các kết quả FAILED/ERROR của 1 lượt chạy,
 * gọi 1 lần cuối lượt (cần nhìn "anh em" cùng lượt để biết "N/M test cùng lỗi"). Nhãn là KẾT QUẢ CHẤM LÚC CHẠY, không
 * tự tính lại về sau (cùng tinh thần nhãn Tier 1 ở Module 15b) - lịch sử chạy thêm về sau không đổi nhãn đã lưu.
 *
 * Tuyệt đối không được làm hỏng lượt chạy: mọi lỗi ở bước phân tích chỉ ghi log và bỏ qua, kết quả test vẫn giữ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionFailureAnalyzer {

    private static final Set<Integer> AUTH_STATUSES = Set.of(401, 403);

    private final TestResultRepository testResultRepository;
    private final FailureCauseClassifier failureCauseClassifier;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Mutate và lưu lại các {@code results} FAILED/ERROR (failureCause + failureEvidence); kết quả khác giữ nguyên. */
    public void analyze(TestExecution execution, List<TestResult> results) {
        try {
            doAnalyze(execution, results);
        } catch (Exception e) {
            log.warn("Khong phan tich duoc nguyen nhan fail cua execution {}: {}", execution.getId(), e.toString());
        }
    }

    private void doAnalyze(TestExecution execution, List<TestResult> results) {
        List<TestResult> failed = results.stream()
                .filter(r -> r.getStatus() == TestResultStatus.FAILED || r.getStatus() == TestResultStatus.ERROR)
                .toList();
        if (failed.isEmpty()) {
            return;
        }

        List<UUID> testCaseIds = failed.stream().map(r -> r.getTestCase().getId()).distinct().toList();
        Map<UUID, List<TestResultRepository.HistoryRow>> historyByTestCaseId = testResultRepository
                .findHistoryRowsByTestCaseIds(testCaseIds).stream()
                .collect(Collectors.groupingBy(TestResultRepository.HistoryRow::getTestCaseId));
        FailureCauseClassifier.ExecutionContext context = buildContext(results);

        for (TestResult result : failed) {
            List<TestResultRepository.HistoryRow> rows = historyByTestCaseId.getOrDefault(result.getTestCase().getId(), List.of());
            FailureCauseClassifier.Input input = new FailureCauseClassifier.Input(
                    result.getStatus(), result.getTestCase().getExpectedStatus(), result.getResponseStatus(),
                    result.getErrorKind(), result.getDurationMs(), result.getErrorMessage(),
                    result.getTestCase().getAuthOverride(),
                    historyOf(result, rows),
                    execution.getTargetBaseUrlSnapshot(), execution.getTargetAuthUpdatedAtSnapshot(), context,
                    failedAssertionsOf(result), lastOtherVersionOf(result, rows));
            FailureCauseClassifier.Result classified = failureCauseClassifier.classify(input);
            if (classified != null) {
                result.setFailureCause(classified.cause());
                result.setFailureEvidence(writeEvidence(classified.evidence()));
            }
        }
        testResultRepository.saveAll(failed);
    }

    /** Lịch sử CÙNG phiên bản test (fingerprint); kết quả cũ không có fingerprint không so được nên bỏ. */
    private List<FailureCauseClassifier.HistoryPoint> historyOf(TestResult result, List<TestResultRepository.HistoryRow> rows) {
        if (result.getTestCaseFingerprint() == null) {
            return List.of();
        }
        List<FailureCauseClassifier.HistoryPoint> history = new ArrayList<>();
        for (TestResultRepository.HistoryRow row : rows) {
            if (Objects.equals(row.getFingerprint(), result.getTestCaseFingerprint())) {
                history.add(new FailureCauseClassifier.HistoryPoint(
                        row.getStatus(), row.getResponseStatus(), row.getCreatedAt(), row.getBaseUrl()));
            }
        }
        return history;
    }

    /**
     * Lần chạy gần nhất của test case này ở PHIÊN BẢN KHÁC (fingerprint khác) - tức test đã được sửa kể từ đó. Dòng lịch
     * sử sắp theo thời gian tăng dần nên lấy phần tử cuối thoả điều kiện. Null nếu kết quả hiện tại không có fingerprint
     * (không biết phiên bản) hoặc chưa từng chạy ở phiên bản nào khác.
     */
    private FailureCauseClassifier.HistoryPoint lastOtherVersionOf(TestResult result, List<TestResultRepository.HistoryRow> rows) {
        if (result.getTestCaseFingerprint() == null) {
            return null;
        }
        TestResultRepository.HistoryRow last = null;
        for (TestResultRepository.HistoryRow row : rows) {
            if (!Objects.equals(row.getFingerprint(), result.getTestCaseFingerprint())) {
                last = row;
            }
        }
        return last == null ? null : new FailureCauseClassifier.HistoryPoint(
                last.getStatus(), last.getResponseStatus(), last.getCreatedAt(), last.getBaseUrl());
    }

    /** Các assertion sai của kết quả (đọc từ JSON đã lưu); JSON thiếu/hỏng thì rỗng - không được làm hỏng bước phân tích. */
    private List<FailureCauseClassifier.FailedAssertion> failedAssertionsOf(TestResult result) {
        if (result.getAssertionResultsJson() == null || result.getAssertionResultsJson().isBlank()) {
            return List.of();
        }
        try {
            List<AssertionResultResponse> all = objectMapper.readValue(result.getAssertionResultsJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<AssertionResultResponse>>() {
                    });
            return all.stream()
                    .filter(a -> !a.passed())
                    .map(a -> new FailureCauseClassifier.FailedAssertion(
                            a.jsonPath(), a.operator() == null ? null : a.operator().name(), a.expectedValue(), a.actualValue()))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private FailureCauseClassifier.ExecutionContext buildContext(List<TestResult> results) {
        int called = 0;
        int connectionErrors = 0;
        int defaultAuthCalled = 0;
        int defaultAuthUnauthorized = 0;
        for (TestResult r : results) {
            boolean calledTarget = r.getResponseStatus() != null || r.getErrorKind() != null;
            if (!calledTarget) {
                continue; // ERROR cấu hình/BLOCKED không gọi target - không tính vào "N/M test cùng lỗi"
            }
            called++;
            if (r.getErrorKind() != null && r.getErrorKind() != ErrorKind.OTHER) {
                connectionErrors++;
            }
            TestCaseAuthOverride override = r.getTestCase().getAuthOverride();
            if (override == null || override == TestCaseAuthOverride.DEFAULT) {
                defaultAuthCalled++;
                Integer expected = r.getTestCase().getExpectedStatus();
                if (r.getStatus() == TestResultStatus.FAILED && r.getResponseStatus() != null
                        && AUTH_STATUSES.contains(r.getResponseStatus()) && (expected == null || !AUTH_STATUSES.contains(expected))) {
                    defaultAuthUnauthorized++;
                }
            }
        }
        return new FailureCauseClassifier.ExecutionContext(connectionErrors, called, defaultAuthUnauthorized, defaultAuthCalled);
    }

    private String writeEvidence(List<String> evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            return null;
        }
    }

}
