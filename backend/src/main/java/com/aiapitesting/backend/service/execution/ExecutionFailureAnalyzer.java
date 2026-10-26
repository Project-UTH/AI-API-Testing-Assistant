package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.dto.response.AssertionResultResponse;
import com.aiapitesting.backend.dto.response.StabilityResponse;
import com.aiapitesting.backend.entity.ErrorKind;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.StabilityState;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.repository.TestCaseAssertionRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
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
    private final TestCaseDependencyRepository testCaseDependencyRepository;
    private final TestCaseAssertionRepository testCaseAssertionRepository;
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
        Map<UUID, Map<String, String>> fallbackPathParamsByTestCaseId = fallbackPathParamsOf(failed);
        List<TestResult> changed = new ArrayList<>();

        for (TestResult result : failed) {
            List<String> robustnessEvidence = probeEvidence(result, HttpRobustnessProbe.KIND);
            if (!robustnessEvidence.isEmpty()) {
                result.setFailureCause(FailureCause.HTTP_ROBUSTNESS);
                result.setFailureEvidence(writeEvidence(robustnessEvidence));
                changed.add(result);
                continue;
            }
            // Probe mass-assignment đã bị phát hiện (17b-2): đây là phát hiện bảo mật thật, bỏ qua luật phân loại theo mã trạng thái.
            List<String> probeEvidence = massAssignmentEvidence(result);
            if (!probeEvidence.isEmpty()) {
                result.setFailureCause(FailureCause.MASS_ASSIGNMENT);
                result.setFailureEvidence(writeEvidence(probeEvidence));
                changed.add(result);
                continue;
            }
            List<String> ruleEvidence = ruleViolationAcceptedEvidence(result);
            if (!ruleEvidence.isEmpty()) {
                result.setFailureCause(FailureCause.RULE_VIOLATION_ACCEPTED);
                result.setFailureEvidence(writeEvidence(ruleEvidence));
                changed.add(result);
                continue;
            }
            List<TestResultRepository.HistoryRow> rows = historyByTestCaseId.getOrDefault(result.getTestCase().getId(), List.of());
            FailureCauseClassifier.Input input = new FailureCauseClassifier.Input(
                    result.getStatus(), result.getTestCase().getExpectedStatus(), result.getResponseStatus(),
                    result.getErrorKind(), result.getDurationMs(), result.getErrorMessage(),
                    result.getTestCase().getAuthOverride(),
                    historyOf(result, rows, Map.of()),
                    execution.getTargetBaseUrlSnapshot(), execution.getTargetAuthUpdatedAtSnapshot(), context,
                    failedAssertionsOf(result), lastOtherVersionOf(result, rows),
                    result.getTestCase().getEndpoint() == null ? null : result.getTestCase().getEndpoint().effectiveRequiresAuth(),
                    fallbackPathParamsByTestCaseId.getOrDefault(result.getTestCase().getId(), Map.of()),
                    hasAssertions(result), result.getTestCase().getAuthProfileId() != null,
                    profileAcceptedCount(result, results),
                    InputRejectionDetector.excerptIfRejected(result.getResponseBody()));
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

    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{\\{(\\w+)}}");

    /**
     * Tham số TRONG ĐƯỜNG DẪN (phần trước dấu ?) mà test dùng giá trị dự phòng: placeholder của resolvedPath không có
     * dependency cấp giá trị (giống cách TestExecutionRunner chọn nguồn giá trị). Chỉ tính cho kết quả 404 - nơi duy nhất
     * thông tin này được dùng - để khỏi tốn truy vấn cho các kết quả khác. Lỗi đọc dữ liệu thì bỏ qua (không có nhãn).
     */
    private Map<UUID, Map<String, String>> fallbackPathParamsOf(List<TestResult> failed) {
        List<TestResult> notFound = failed.stream()
                .filter(r -> Integer.valueOf(404).equals(r.getResponseStatus())).toList();
        if (notFound.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = notFound.stream().map(r -> r.getTestCase().getId()).distinct().toList();
        Map<UUID, Set<String>> withDependency = testCaseDependencyRepository.findPlaceholderRowsByTestCaseIds(ids).stream()
                .collect(Collectors.groupingBy(TestCaseDependencyRepository.PlaceholderRow::getTestCaseId,
                        Collectors.mapping(TestCaseDependencyRepository.PlaceholderRow::getPlaceholderName, Collectors.toSet())));
        Map<UUID, Map<String, String>> result = new HashMap<>();
        for (TestResult r : notFound) {
            var testCase = r.getTestCase();
            if (result.containsKey(testCase.getId()) || testCase.getResolvedPath() == null) {
                continue;
            }
            String path = testCase.getResolvedPath();
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            Map<String, String> fallbacks = parseStringMap(testCase.getPathParamFallbacks());
            Set<String> covered = withDependency.getOrDefault(testCase.getId(), Set.of());
            Map<String, String> used = new java.util.LinkedHashMap<>();
            var matcher = PLACEHOLDER.matcher(path);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (!covered.contains(name) && fallbacks.containsKey(name)) {
                    used.put(name, fallbacks.get(name));
                }
            }
            result.put(testCase.getId(), used);
        }
        return result;
    }

    private Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Test case có assertion nội dung phản hồi không - chỉ tra khi cần (2xx lệch 2xx), lỗi thì coi là có (không gắn nhãn). */
    private boolean hasAssertions(TestResult result) {
        Integer expected = result.getTestCase().getExpectedStatus();
        Integer actual = result.getResponseStatus();
        if (expected == null || actual == null || expected.equals(actual) || expected < 200 || expected > 299 || actual < 200 || actual > 299) {
            return false;
        }
        try {
            return testCaseAssertionRepository.existsByTestCase(result.getTestCase());
        } catch (Exception e) {
            return true;
        }
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

    /** Số test CÙNG profile vai trò trong lượt chạy nhận phản hồi không phải 401/403 - bằng chứng token của vai trò còn dùng được. */
    /** Bằng chứng từ securityFindingsJson của probe mass-assignment (chỉ tên field/mô tả, không giá trị); rỗng nếu không phải probe bị phát hiện. */
    /**
     * 17q G3 - test từ luật nghiệp vụ đã duyệt kỳ vọng "bị từ chối" (4xx) mà API trả 2xx/3xx: API nhận dữ liệu vi phạm luật. Chỉ áp cho test
     * VI PHẠM luật (test biên hợp lệ kỳ vọng 2xx không bao giờ vào đây).
     */
    private List<String> ruleViolationAcceptedEvidence(TestResult result) {
        TestCase testCase = result.getTestCase();
        Integer actual = result.getResponseStatus();
        if (testCase.getRuleId() == null || result.getStatus() != TestResultStatus.FAILED || actual == null || actual < 200 || actual >= 400
                || testCase.getExpectedStatus() == null || testCase.getExpectedStatus() < 400 || testCase.getExpectedStatus() > 499) {
            return List.of();
        }
        String rule = testCase.getChecklistItem() == null ? "luật nghiệp vụ đã duyệt" : testCase.getChecklistItem();
        return List.of("API trả " + actual + " (thành công) cho request cố ý vi phạm 1 luật bạn đã duyệt: " + rule,
                testCase.getExpectedStatusClass() != null
                        ? "Kỳ vọng: bị từ chối (4xx). Nếu mục checklist sai, hãy Sửa hoặc Loại mục đó ở tab Checklist nghiệp vụ - không sửa kỳ vọng để test Pass."
                        : "Kỳ vọng: " + testCase.getExpectedStatus() + ". Nếu mục checklist sai, hãy Sửa hoặc Loại mục đó ở tab Checklist nghiệp vụ.");
    }

    private List<String> massAssignmentEvidence(TestResult result) {
        return probeEvidence(result, MassAssignmentProbe.KIND);
    }

    /** Bằng chứng (mô tả, không có giá trị) của probe loại {@code kind} mà lần chạy này đã phát hiện; rỗng nếu không phải probe đó. */
    private List<String> probeEvidence(TestResult result, String kind) {
        String json = result.getSecurityFindingsJson();
        if (json == null || !kind.equals(result.getTestCase().getProbeKind()) || !json.contains(kind)) {
            return List.of();
        }
        try {
            List<Map<String, String>> findings = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {
                    });
            return findings.stream().filter(f -> f.getOrDefault("rule", "").startsWith(kind))
                    .map(f -> f.get("detail")).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private int profileAcceptedCount(TestResult result, List<TestResult> results) {
        UUID profileId = result.getTestCase().getAuthProfileId();
        if (profileId == null) {
            return 0;
        }
        return (int) results.stream()
                .filter(r -> profileId.equals(r.getTestCase().getAuthProfileId()))
                .filter(r -> r.getResponseStatus() != null && !AUTH_STATUSES.contains(r.getResponseStatus()))
                .count();
    }

    private FailureCauseClassifier.ExecutionContext buildContext(List<TestResult> results) {
        int called = 0;
        int connectionErrors = 0;
        int defaultAuthCalled = 0;
        int defaultAuthUnauthorized = 0;
        int defaultAuthAccepted = 0;
        // Module 17 (1.1) - dự phòng cho API mà spec KHÔNG khai xác thực ở endpoint nào (vd springdoc mặc định): requiresAuth
        // luôn false nên điều kiện "endpoint yêu cầu xác thực" không bao giờ thoả, token không bao giờ được coi là còn dùng
        // và mọi 401 của API (vd 401 cho id sai kiểu) đều bị gán nhầm "nghi token", che mất lỗi thật.
        boolean anyEndpointRequiresAuth = false;
        int defaultAuthSuccess2xx = 0;
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
            // Test chạy bằng profile vai trò (Module 17 - 3.1) không dùng token mặc định: không đếm vào bằng chứng về token đó.
            boolean usesProjectToken = r.getTestCase().getAuthProfileId() == null;
            if (usesProjectToken && (override == null || override == TestCaseAuthOverride.DEFAULT)) {
                defaultAuthCalled++;
                // Chỉ endpoint spec khai YÊU CẦU xác thực mới chứng minh được token còn dùng: endpoint công khai nhận 200 dù
                // token hỏng.
                boolean authRequired = r.getTestCase().getEndpoint() != null && r.getTestCase().getEndpoint().effectiveRequiresAuth();
                anyEndpointRequiresAuth |= authRequired;
                if (authRequired && r.getResponseStatus() != null && !AUTH_STATUSES.contains(r.getResponseStatus())) {
                    defaultAuthAccepted++;
                }
                if (r.getResponseStatus() != null && r.getResponseStatus() >= 200 && r.getResponseStatus() < 300) {
                    defaultAuthSuccess2xx++;
                }
                Integer expected = r.getTestCase().getExpectedStatus();
                if (r.getStatus() == TestResultStatus.FAILED && r.getResponseStatus() != null
                        && AUTH_STATUSES.contains(r.getResponseStatus()) && (expected == null || !AUTH_STATUSES.contains(expected))) {
                    defaultAuthUnauthorized++;
                }
            }
        }
        // Dự phòng chỉ khi spec không khai xác thực ở endpoint nào trong lượt này, và chỉ khi 401/403 là THIỂU SỐ: token hết hạn
        // làm hỏng gần như mọi test cần xác thực, nên đa số bị từ chối vẫn là AUTH_CONFIG; thiểu số bị từ chối trong khi các test
        // khác nhận 2xx là hành vi của chính API.
        if (defaultAuthAccepted == 0 && !anyEndpointRequiresAuth && defaultAuthSuccess2xx > 0
                && defaultAuthUnauthorized * 2 < defaultAuthCalled) {
            defaultAuthAccepted = defaultAuthSuccess2xx;
        }
        return new FailureCauseClassifier.ExecutionContext(connectionErrors, called, defaultAuthUnauthorized, defaultAuthCalled,
                defaultAuthAccepted);
    }

    private String writeEvidence(List<String> evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            return null;
        }
    }

}
