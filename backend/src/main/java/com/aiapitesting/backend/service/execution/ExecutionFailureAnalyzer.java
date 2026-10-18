package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.dto.response.AssertionResultResponse;
import com.aiapitesting.backend.dto.response.StabilityResponse;
import com.aiapitesting.backend.entity.ErrorKind;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.StabilityState;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /**
     * Mutate và lưu lại: các {@code results} FAILED/ERROR được gắn failureCause + failureEvidence; MỌI kết quả (kể cả PASSED)
     * của test case đang không ổn định (FLAKY, giai đoạn 2) được đánh dấu {@code unstable}. Kết quả khác giữ nguyên.
     */
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
        // Độ ổn định xét cho cả test PASSED (1 lần Pass của test chập chờn cũng không phải bằng chứng chắc chắn) nhưng chỉ
        // khi biết phiên bản test (fingerprint) - dữ liệu trước Module 16 không so được.
        List<TestResult> versioned = results.stream().filter(r -> r.getTestCaseFingerprint() != null).toList();
        if (failed.isEmpty() && versioned.isEmpty()) {
            return;
        }

        List<UUID> testCaseIds = Stream.concat(failed.stream(), versioned.stream())
                .map(r -> r.getTestCase().getId()).distinct().toList();
        Map<UUID, List<TestResultRepository.HistoryRow>> historyByTestCaseId = testResultRepository
                .findHistoryRowsByTestCaseIds(testCaseIds).stream()
                .collect(Collectors.groupingBy(TestResultRepository.HistoryRow::getTestCaseId));
        FailureCauseClassifier.ExecutionContext context = buildContext(results);
        List<TestResult> changed = new ArrayList<>();

        for (TestResult result : failed) {
            List<TestResultRepository.HistoryRow> rows = historyByTestCaseId.getOrDefault(result.getTestCase().getId(), List.of());
            FailureCauseClassifier.Input input = new FailureCauseClassifier.Input(
                    result.getStatus(), result.getTestCase().getExpectedStatus(), result.getResponseStatus(),
                    result.getErrorKind(), result.getDurationMs(), result.getErrorMessage(),
                    result.getTestCase().getAuthOverride(),
                    historyOf(result, rows, Map.of()),
                    execution.getTargetBaseUrlSnapshot(), execution.getTargetAuthUpdatedAtSnapshot(), context,
                    failedAssertionsOf(result), lastOtherVersionOf(result, rows));
            FailureCauseClassifier.Result classified = failureCauseClassifier.classify(input);
            if (classified != null) {
                result.setFailureCause(classified.cause());
                result.setFailureEvidence(writeEvidence(classified.evidence()));
            }
            changed.add(result);
        }

        // Nguyên nhân của lần chạy HIỆN TẠI vừa chấm xong chưa nằm trong DB - truyền vào để đánh giá ổn định bỏ qua đúng
        // các lần Fail do môi trường/token (test Pass khi có token, Fail khi mất token KHÔNG phải test chập chờn).
        Map<UUID, FailureCause> causeOfThisRun = new HashMap<>();
        for (TestResult r : results) {
            if (r.getId() != null && r.getFailureCause() != null) {
                causeOfThisRun.put(r.getId(), r.getFailureCause());
            }
        }
        for (TestResult result : versioned) {
            List<TestResultRepository.HistoryRow> rows = historyByTestCaseId.getOrDefault(result.getTestCase().getId(), List.of());
            if (TestStabilityAnalyzer.assess(historyOf(result, rows, causeOfThisRun)).state()
                    == StabilityState.FLAKY) {
                result.setUnstable(true);
                if (!changed.contains(result)) {
                    changed.add(result);
                }
            }
        }
        if (!changed.isEmpty()) {
            testResultRepository.saveAll(changed);
        }
    }

    /**
     * Đọc lúc xem kết quả: độ ổn định HIỆN TẠI (theo toàn bộ lịch sử cùng phiên bản) của từng test trong lượt chạy, kể cả
     * PASSED. Chỉ trả những kết quả đáng chú ý (FLAKY / NEEDS_MORE_RUNS); 1 query lịch sử nhẹ cho cả lượt. Lỗi truy vấn
     * không được làm hỏng trang kết quả - trả rỗng.
     */
    public Map<UUID, StabilityResponse> stabilityOf(List<TestResult> results) {
        try {
            List<TestResult> versioned = results.stream().filter(r -> r.getTestCaseFingerprint() != null).toList();
            if (versioned.isEmpty()) {
                return Map.of();
            }
            Map<UUID, List<TestResultRepository.HistoryRow>> historyByTestCaseId = testResultRepository
                    .findHistoryRowsByTestCaseIds(versioned.stream().map(r -> r.getTestCase().getId()).distinct().toList()).stream()
                    .collect(Collectors.groupingBy(TestResultRepository.HistoryRow::getTestCaseId));
            Map<UUID, StabilityResponse> stability = new HashMap<>();
            for (TestResult result : versioned) {
                TestStabilityAnalyzer.Assessment assessment = TestStabilityAnalyzer.assess(historyOf(result,
                        historyByTestCaseId.getOrDefault(result.getTestCase().getId(), List.of()), Map.of()));
                if (assessment.state() == StabilityState.FLAKY || assessment.state() == StabilityState.NEEDS_MORE_RUNS) {
                    stability.put(result.getId(), StabilityResponse.of(assessment, result.getTestCase().getEndpoint().getMethod()));
                }
            }
            return stability;
        } catch (Exception e) {
            log.warn("Khong danh gia duoc do on dinh: {}", e.toString());
            return Map.of();
        }
    }

    /**
     * Lịch sử CÙNG phiên bản test (fingerprint); kết quả cũ không có fingerprint không so được nên bỏ.
     * {@code causeOverrides}: nguyên nhân vừa chấm cho các kết quả của lượt này (theo id) - ưu tiên hơn giá trị trong DB.
     */
    private List<FailureCauseClassifier.HistoryPoint> historyOf(
            TestResult result, List<TestResultRepository.HistoryRow> rows, Map<UUID, FailureCause> causeOverrides
    ) {
        if (result.getTestCaseFingerprint() == null) {
            return List.of();
        }
        List<FailureCauseClassifier.HistoryPoint> history = new ArrayList<>();
        for (TestResultRepository.HistoryRow row : rows) {
            if (Objects.equals(row.getFingerprint(), result.getTestCaseFingerprint())) {
                FailureCause cause = row.getResultId() != null && causeOverrides.containsKey(row.getResultId())
                        ? causeOverrides.get(row.getResultId()) : row.getFailureCause();
                history.add(new FailureCauseClassifier.HistoryPoint(
                        row.getStatus(), row.getResponseStatus(), row.getCreatedAt(), row.getBaseUrl(), cause));
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
