package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.ErrorKind;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.repository.TestResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Module 16 - phân tích nguyên nhân fail cho cả 1 lượt chạy: gom lịch sử, đếm "N/M test", lưu nhãn. */
@ExtendWith(MockitoExtension.class)
class ExecutionFailureAnalyzerTest {

    private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");

    @Mock
    private TestResultRepository testResultRepository;

    private ExecutionFailureAnalyzer analyzer;
    private Endpoint endpoint;
    private TestExecution execution;

    @BeforeEach
    void setUp() {
        analyzer = new ExecutionFailureAnalyzer(testResultRepository, new FailureCauseClassifier());
        endpoint = Endpoint.builder().id(UUID.randomUUID()).method("GET").path("/pets").build();
        execution = TestExecution.builder().id(UUID.randomUUID()).targetBaseUrlSnapshot("http://target").build();
    }

    private TestResult result(TestResultStatus status, Integer expected, Integer actual, String fingerprint) {
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).expectedStatus(expected)
                .authOverride(TestCaseAuthOverride.DEFAULT).build();
        return TestResult.builder().id(UUID.randomUUID()).testCase(testCase).execution(execution).status(status)
                .responseStatus(actual).testCaseFingerprint(fingerprint).build();
    }

    private TestResultRepository.HistoryRow row(TestResult of, String fingerprint, TestResultStatus status, Integer code, int minute) {
        return new TestResultRepository.HistoryRow() {
            @Override
            public UUID getTestCaseId() {
                return of.getTestCase().getId();
            }

            @Override
            public String getFingerprint() {
                return fingerprint;
            }

            @Override
            public TestResultStatus getStatus() {
                return status;
            }

            @Override
            public Integer getResponseStatus() {
                return code;
            }

            @Override
            public Instant getCreatedAt() {
                return T0.plusSeconds(60L * minute);
            }

            @Override
            public String getBaseUrl() {
                return "http://target";
            }
        };
    }

    @Test
    void noFailures_doesNotQueryHistoryOrSaveAnything() {
        List<TestResult> results = List.of(result(TestResultStatus.PASSED, 200, 200, "fp"),
                result(TestResultStatus.BLOCKED, 200, null, "fp"));

        analyzer.analyze(execution, results);

        verifyNoInteractions(testResultRepository);
        assertThat(results).allSatisfy(r -> assertThat(r.getFailureCause()).isNull());
    }

    @Test
    void tokenRejectedForMostTests_allFailuresLabelledAuthConfigWithSiblingCount() {
        List<TestResult> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(result(TestResultStatus.FAILED, 200, 401, "fp" + i));
        }
        results.add(result(TestResultStatus.PASSED, 200, 200, "fp3"));
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of());

        analyzer.analyze(execution, results);

        assertThat(results.subList(0, 3)).allSatisfy(r -> {
            assertThat(r.getFailureCause()).isEqualTo(FailureCause.AUTH_CONFIG);
            assertThat(r.getFailureEvidence()).contains("3/4 test dùng token mặc định");
        });
        assertThat(results.get(3).getFailureCause()).isNull();
        verify(testResultRepository).saveAll(results.subList(0, 3));
    }

    @Test
    void historyIsFilteredBySameFingerprint_editedTestStartsFromScratch() {
        TestResult current = result(TestResultStatus.FAILED, 200, 404, "fp-new");
        // Lịch sử có 2 lần Pass của PHIÊN BẢN CŨ (fp-old) - không được tính, nếu không sẽ ra "Pass rồi Fail liên tiếp".
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(current, "fp-old", TestResultStatus.PASSED, 200, 0),
                row(current, "fp-old", TestResultStatus.PASSED, 200, 1),
                row(current, "fp-new", TestResultStatus.FAILED, 404, 2)));

        analyzer.analyze(execution, List.of(current));

        assertThat(current.getFailureCause()).isEqualTo(FailureCause.UNDETERMINED);
        assertThat(current.getFailureEvidence()).contains("Mới có 1 lần chạy");
    }

    @Test
    void sameFingerprintHistory_producesDataStatePattern() {
        TestResult current = result(TestResultStatus.FAILED, 200, 404, "fp");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(current, "fp", TestResultStatus.PASSED, 200, 0),
                row(current, "fp", TestResultStatus.FAILED, 404, 1),
                row(current, "fp", TestResultStatus.FAILED, 404, 2)));

        analyzer.analyze(execution, List.of(current));

        assertThat(current.getFailureCause()).isEqualTo(FailureCause.DATA_STATE);
    }

    @Test
    void resultWithoutFingerprint_isNotComparedWithAnyHistory() {
        TestResult legacy = result(TestResultStatus.FAILED, 200, 500, null);
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(legacy, "fp", TestResultStatus.FAILED, 500, 0),
                row(legacy, "fp", TestResultStatus.FAILED, 500, 1),
                row(legacy, "fp", TestResultStatus.FAILED, 500, 2)));

        analyzer.analyze(execution, List.of(legacy));

        assertThat(legacy.getFailureCause()).isEqualTo(FailureCause.UNDETERMINED);
    }

    @Test
    void connectionErrors_countedAgainstTestsThatActuallyCalledTheTarget() {
        TestResult down1 = result(TestResultStatus.ERROR, 200, null, "a");
        down1.setErrorKind(ErrorKind.CONNECTION);
        TestResult down2 = result(TestResultStatus.ERROR, 200, null, "b");
        down2.setErrorKind(ErrorKind.CONNECTION);
        TestResult setup = result(TestResultStatus.ERROR, 200, null, "c"); // không gọi target (thiếu tham số)
        TestResult ok = result(TestResultStatus.PASSED, 200, 200, "d");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of());

        analyzer.analyze(execution, List.of(down1, down2, setup, ok));

        assertThat(down1.getFailureCause()).isEqualTo(FailureCause.ENVIRONMENT);
        assertThat(down1.getFailureEvidence()).contains("2/3 test");
        assertThat(setup.getFailureCause()).isEqualTo(FailureCause.TEST_SETUP);
    }

    @Test
    void executionSnapshotsFeedTheClassifier_baseUrlChangeAndTokenTime() {
        execution.setTargetBaseUrlSnapshot("http://new-host");
        TestResult down = result(TestResultStatus.ERROR, 200, null, "fp");
        down.setErrorKind(ErrorKind.CONNECTION);
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(down, "fp", TestResultStatus.PASSED, 200, 0), // baseUrl của dòng này là "http://target"
                row(down, "fp", TestResultStatus.ERROR, null, 1)));

        analyzer.analyze(execution, List.of(down));

        assertThat(down.getFailureEvidence()).contains("http://target → http://new-host");
    }

    @Test
    void analysisFailureNeverBreaksTheExecution_resultsKeepTheirData() {
        TestResult failed = result(TestResultStatus.FAILED, 200, 500, "fp");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> analyzer.analyze(execution, List.of(failed))).doesNotThrowAnyException();

        assertThat(failed.getFailureCause()).isNull();
        assertThat(failed.getStatus()).isEqualTo(TestResultStatus.FAILED);
        verify(testResultRepository, never()).saveAll(any());
    }
}
