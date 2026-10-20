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

    @Mock
    private com.aiapitesting.backend.repository.TestCaseDependencyRepository testCaseDependencyRepository;

    @Mock
    private com.aiapitesting.backend.repository.TestCaseAssertionRepository testCaseAssertionRepository;

    private ExecutionFailureAnalyzer analyzer;
    private Endpoint endpoint;
    private TestExecution execution;

    @BeforeEach
    void setUp() {
        analyzer = new ExecutionFailureAnalyzer(testResultRepository, new FailureCauseClassifier(), testCaseDependencyRepository, testCaseAssertionRepository);
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
        return row(of, fingerprint, status, code, minute, null, UUID.randomUUID());
    }

    private TestResultRepository.HistoryRow row(
            TestResult of, String fingerprint, TestResultStatus status, Integer code, int minute, FailureCause cause, UUID resultId
    ) {
        return new TestResultRepository.HistoryRow() {
            @Override
            public UUID getResultId() {
                return resultId;
            }

            @Override
            public FailureCause getFailureCause() {
                return cause;
            }

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

    // ---- 404 do id dự phòng (RESOURCE_NOT_FOUND) ----

    private TestResult notFoundResult(String resolvedPath, String fallbacksJson) {
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).expectedStatus(200)
                .resolvedPath(resolvedPath).pathParamFallbacks(fallbacksJson).authOverride(TestCaseAuthOverride.DEFAULT).build();
        return TestResult.builder().id(UUID.randomUUID()).testCase(testCase).execution(execution)
                .status(TestResultStatus.FAILED).responseStatus(404).testCaseFingerprint("fp").build();
    }

    private com.aiapitesting.backend.repository.TestCaseDependencyRepository.PlaceholderRow dependencyRow(UUID testCaseId, String name) {
        return new com.aiapitesting.backend.repository.TestCaseDependencyRepository.PlaceholderRow() {
            @Override
            public UUID getTestCaseId() {
                return testCaseId;
            }

            @Override
            public String getPlaceholderName() {
                return name;
            }
        };
    }

    @Test
    void notFoundOnFallbackIdInPath_isResourceNotFoundAndNamesTheFallbackValue() {
        TestResult r = notFoundResult("/api/products/{{id}}", "{\"id\":\"1\"}");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(r, "fp", TestResultStatus.FAILED, 404, 0)));

        analyzer.analyze(execution, List.of(r));

        assertThat(r.getFailureCause()).isEqualTo(FailureCause.RESOURCE_NOT_FOUND);
        assertThat(r.getFailureEvidence()).contains("{id}=1");
    }

    @Test
    void fallbackOnlyInQueryString_isNotAPathParam() {
        TestResult r = notFoundResult("/api/products?minPrice={{minPrice}}", "{\"minPrice\":\"5\"}");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(r, "fp", TestResultStatus.FAILED, 404, 0)));

        analyzer.analyze(execution, List.of(r));

        assertThat(r.getFailureCause()).isNotEqualTo(FailureCause.RESOURCE_NOT_FOUND);
    }

    @Test
    void pathParamSuppliedByDependency_isNotAFallback() {
        TestResult r = notFoundResult("/api/products/{{id}}", "{\"id\":\"1\"}");
        when(testCaseDependencyRepository.findPlaceholderRowsByTestCaseIds(anyList()))
                .thenReturn(List.of(dependencyRow(r.getTestCase().getId(), "id")));
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(r, "fp", TestResultStatus.FAILED, 404, 0)));

        analyzer.analyze(execution, List.of(r));

        assertThat(r.getFailureCause()).isNotEqualTo(FailureCause.RESOURCE_NOT_FOUND);
    }

    @Test
    void notFoundOnlyChecksDependenciesForResultsThatActuallyGot404() {
        TestResult r = result(TestResultStatus.FAILED, 200, 500, "fp");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(r, "fp", TestResultStatus.FAILED, 500, 0)));

        analyzer.analyze(execution, List.of(r));

        org.mockito.Mockito.verifyNoInteractions(testCaseDependencyRepository);
    }

    @Test
    void noFailuresAndNoUnstableTest_savesNothing() {
        List<TestResult> results = List.of(result(TestResultStatus.PASSED, 200, 200, "fp"),
                result(TestResultStatus.BLOCKED, 200, null, "fp"));

        analyzer.analyze(execution, results);

        verify(testResultRepository, never()).saveAll(any());
        assertThat(results).allSatisfy(r -> {
            assertThat(r.getFailureCause()).isNull();
            assertThat(r.getUnstable()).isNull();
        });
    }

    @Test
    void resultsWithoutFingerprintAndNoFailures_doNotQueryHistoryAtAll() {
        List<TestResult> results = List.of(result(TestResultStatus.PASSED, 200, 200, null));

        analyzer.analyze(execution, results);

        verifyNoInteractions(testResultRepository);
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
    void tokenProvenValidByAnotherTestOnAProtectedEndpoint_aLoneForbiddenIsNotBlamedOnTheToken() {
        // Target trả 403 cho JSON hỏng dù token tốt (bug của API): test khác trên endpoint yêu cầu xác thực nhận 200.
        Endpoint protectedEndpoint = Endpoint.builder().id(UUID.randomUUID()).method("POST").path("/api/products").requiresAuth(true).build();
        endpoint = protectedEndpoint;
        TestResult lone403 = result(TestResultStatus.FAILED, 400, 403, "fp-a");
        TestResult proof = result(TestResultStatus.PASSED, 200, 200, "fp-b");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of());

        analyzer.analyze(execution, List.of(lone403, proof));

        assertThat(lone403.getFailureCause()).isNotEqualTo(FailureCause.AUTH_CONFIG);
    }

    @Test
    void passOnAPublicEndpointDoesNotProveTheTokenIsValid() {
        Endpoint publicEndpoint = Endpoint.builder().id(UUID.randomUUID()).method("GET").path("/health").requiresAuth(false).build();
        TestResult rejected = result(TestResultStatus.FAILED, 200, 401, "fp-a");
        endpoint = publicEndpoint;
        TestResult publicPass = result(TestResultStatus.PASSED, 200, 200, "fp-b");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of());

        analyzer.analyze(execution, List.of(rejected, publicPass));

        assertThat(rejected.getFailureCause()).isEqualTo(FailureCause.AUTH_CONFIG);
    }

    @Test
    void specWithoutAnySecurity_minority401WhileOthersSucceed_isNotBlamedOnTheToken() {
        // Ca thật (ai-recruitment-agent): spec không khai security nên requiresAuth=false ở mọi endpoint; API trả 401 cho id sai kiểu
        // trong khi các test khác cùng token nhận 200 - đó là hành vi của API, không phải token hỏng.
        endpoint = Endpoint.builder().id(UUID.randomUUID()).method("GET").path("/api/hr/jobs/{id}").requiresAuth(false).build();
        TestResult wrongType401 = result(TestResultStatus.FAILED, 400, 401, "fp-a");
        List<TestResult> results = new ArrayList<>(List.of(wrongType401));
        for (int i = 0; i < 3; i++) {
            results.add(result(TestResultStatus.PASSED, 200, 200, "fp-ok" + i));
        }
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of());

        analyzer.analyze(execution, results);

        assertThat(wrongType401.getFailureCause()).isNotEqualTo(FailureCause.AUTH_CONFIG);
    }

    @Test
    void specWithoutAnySecurity_majority401_stillBlamedOnTheToken() {
        // Token hết hạn thật: hầu hết test bị từ chối, chỉ 1 endpoint công khai vẫn 200 - không được coi là bằng chứng token tốt.
        endpoint = Endpoint.builder().id(UUID.randomUUID()).method("GET").path("/api/hr/jobs").requiresAuth(false).build();
        List<TestResult> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(result(TestResultStatus.FAILED, 200, 401, "fp" + i));
        }
        results.add(result(TestResultStatus.PASSED, 200, 200, "fp-public"));
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of());

        analyzer.analyze(execution, results);

        assertThat(results.subList(0, 3)).allSatisfy(r -> assertThat(r.getFailureCause()).isEqualTo(FailureCause.AUTH_CONFIG));
    }

    @Test
    void endpointRequiresAuthFlagFromSpec_decidesWhetherCredentialsCaveatIsAdded() {
        Endpoint login = Endpoint.builder().id(UUID.randomUUID()).method("POST").path("/auth/login").requiresAuth(false).build();
        Endpoint protectedEndpoint = Endpoint.builder().id(UUID.randomUUID()).method("GET").path("/me").requiresAuth(true).build();
        endpoint = login;
        TestResult onLogin = result(TestResultStatus.FAILED, 200, 401, "fp-login");
        endpoint = protectedEndpoint;
        TestResult onProtected = result(TestResultStatus.FAILED, 200, 401, "fp-me");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of());

        analyzer.analyze(execution, List.of(onLogin, onProtected));

        assertThat(onLogin.getFailureCause()).isEqualTo(FailureCause.AUTH_CONFIG);
        assertThat(onLogin.getFailureEvidence()).contains("tài khoản/mật khẩu");
        assertThat(onProtected.getFailureCause()).isEqualTo(FailureCause.AUTH_CONFIG);
        assertThat(onProtected.getFailureEvidence()).doesNotContain("tài khoản/mật khẩu");
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

    /** Đúng ca người dùng gặp: đã Pass, thêm assertion (đổi phiên bản), chạy lại fail do assertion. */
    @Test
    void assertionAddedAfterAPass_isAssertionFailedAndMentionsTheEarlierPass() {
        TestResult current = result(TestResultStatus.FAILED, 404, 404, "fp-with-assertion");
        current.setAssertionResultsJson("[{\"jsonPath\":\"1\",\"operator\":\"EQUALS\",\"expectedValue\":\"1\","
                + "\"actualValue\":null,\"passed\":false},"
                + "{\"jsonPath\":\"code\",\"operator\":\"EXISTS\",\"expectedValue\":null,\"actualValue\":\"X\",\"passed\":true}]");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(current, "fp-no-assertion", TestResultStatus.PASSED, 404, 0),
                row(current, "fp-with-assertion", TestResultStatus.FAILED, 404, 5)));

        analyzer.analyze(execution, List.of(current));

        assertThat(current.getFailureCause()).isEqualTo(FailureCause.ASSERTION_FAILED);
        // Chỉ assertion SAI được liệt kê (assertion `code` đã đúng thì không).
        assertThat(current.getFailureEvidence()).contains("1 assertion sai").contains("`1` EQUALS")
                .doesNotContain("`code`")
                .contains("đã được sửa kể từ lần chạy gần nhất ở phiên bản trước").contains("kết quả lúc đó là Pass");
    }

    @Test
    void brokenAssertionJson_doesNotBreakAnalysis() {
        TestResult current = result(TestResultStatus.FAILED, 200, 200, "fp");
        current.setAssertionResultsJson("{ khong phai json");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(current, "fp", TestResultStatus.FAILED, 200, 1)));

        analyzer.analyze(execution, List.of(current));

        assertThat(current.getFailureCause()).isEqualTo(FailureCause.ASSERTION_FAILED);
    }

    @Test
    void lastOtherVersion_isTheMostRecentRunOfADifferentFingerprint_notTheOldestOne() {
        TestResult current = result(TestResultStatus.FAILED, 200, 500, "fp-v3");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(current, "fp-v1", TestResultStatus.PASSED, 200, 0),
                row(current, "fp-v2", TestResultStatus.FAILED, 500, 1),
                row(current, "fp-v3", TestResultStatus.FAILED, 500, 2)));

        analyzer.analyze(execution, List.of(current));

        // Phiên bản liền trước (v2) mới là điều liên quan - kết quả của nó là Fail(500), không phải Pass của v1.
        assertThat(current.getFailureEvidence()).contains("kết quả lúc đó là Fail(500)").doesNotContain("là Pass");
    }

    @Test
    void resultWithoutFingerprint_hasNoEditedEvidence() {
        TestResult legacy = result(TestResultStatus.FAILED, 200, 500, null);
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(legacy, "fp-old", TestResultStatus.PASSED, 200, 0)));

        analyzer.analyze(execution, List.of(legacy));

        assertThat(legacy.getFailureEvidence()).doesNotContain("đã được sửa");
    }

    // ---- Giai đoạn 2: độ ổn định ----

    @Test
    void flakyHistory_failIsLabelledFlakyAndMarkedUnstable() {
        TestResult current = result(TestResultStatus.FAILED, 200, 500, "fp");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(current, "fp", TestResultStatus.PASSED, 200, 0),
                row(current, "fp", TestResultStatus.FAILED, 500, 1),
                row(current, "fp", TestResultStatus.PASSED, 200, 2),
                row(current, "fp", TestResultStatus.PASSED, 200, 3),
                row(current, "fp", TestResultStatus.FAILED, 500, 4, null, current.getId())));

        analyzer.analyze(execution, List.of(current));

        assertThat(current.getFailureCause()).isEqualTo(FailureCause.FLAKY);
        assertThat(current.getUnstable()).isTrue();
        verify(testResultRepository).saveAll(List.of(current));
    }

    /** 1 lần Pass của test không ổn định cũng không phải bằng chứng chắc chắn: đánh dấu unstable, KHÔNG gắn nguyên nhân fail. */
    @Test
    void flakyHistory_passIsMarkedUnstableWithoutAFailureCause() {
        TestResult current = result(TestResultStatus.PASSED, 200, 200, "fp");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(current, "fp", TestResultStatus.PASSED, 200, 0),
                row(current, "fp", TestResultStatus.FAILED, 500, 1),
                row(current, "fp", TestResultStatus.PASSED, 200, 2),
                row(current, "fp", TestResultStatus.FAILED, 500, 3),
                row(current, "fp", TestResultStatus.PASSED, 200, 4, null, current.getId())));

        analyzer.analyze(execution, List.of(current));

        assertThat(current.getUnstable()).isTrue();
        assertThat(current.getFailureCause()).isNull();
        verify(testResultRepository).saveAll(List.of(current));
    }

    @Test
    void stableHistory_leavesResultUntouchedAndSavesNothing() {
        TestResult current = result(TestResultStatus.PASSED, 200, 200, "fp");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(current, "fp", TestResultStatus.PASSED, 200, 0),
                row(current, "fp", TestResultStatus.PASSED, 200, 1)));

        analyzer.analyze(execution, List.of(current));

        assertThat(current.getUnstable()).isNull();
        verify(testResultRepository, never()).saveAll(any());
    }

    /**
     * Lần chạy hiện tại fail do token và nguyên nhân CHƯA nằm trong DB: phải dùng nguyên nhân vừa chấm trong bộ nhớ, nếu
     * không lần Fail đó bị đếm là "Fail thật" và test bị gắn nhầm là chập chờn (Pass, Pass, Fail, Pass, Fail-do-token).
     */
    @Test
    void currentFailCausedByToken_usesFreshCauseSoTheTestIsNotMarkedUnstable() {
        TestResult current = result(TestResultStatus.FAILED, 200, 403, "fp");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(current, "fp", TestResultStatus.PASSED, 200, 0),
                row(current, "fp", TestResultStatus.PASSED, 200, 1),
                row(current, "fp", TestResultStatus.FAILED, 500, 2),
                row(current, "fp", TestResultStatus.PASSED, 200, 3),
                row(current, "fp", TestResultStatus.FAILED, 403, 4, null, current.getId())));

        analyzer.analyze(execution, List.of(current));

        assertThat(current.getFailureCause()).isEqualTo(FailureCause.AUTH_CONFIG);
        assertThat(current.getUnstable()).isNull();
    }

    @Test
    void persistedCausesInHistory_excludeTokenFailsFromTheStabilityCount() {
        TestResult current = result(TestResultStatus.PASSED, 200, 200, "fp");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(List.of(
                row(current, "fp", TestResultStatus.PASSED, 200, 0),
                row(current, "fp", TestResultStatus.FAILED, 403, 1, FailureCause.AUTH_CONFIG, UUID.randomUUID()),
                row(current, "fp", TestResultStatus.PASSED, 200, 2),
                row(current, "fp", TestResultStatus.FAILED, 403, 3, FailureCause.AUTH_CONFIG, UUID.randomUUID()),
                row(current, "fp", TestResultStatus.PASSED, 200, 4, null, current.getId())));

        analyzer.analyze(execution, List.of(current));

        assertThat(current.getUnstable()).isNull();
    }

    @Test
    void stabilityOf_returnsOnlyNoteworthyResultsWithMethodAwareness() {
        Endpoint post = Endpoint.builder().id(UUID.randomUUID()).method("POST").path("/p").build();
        TestResult flaky = result(TestResultStatus.PASSED, 201, 201, "fp-flaky");
        flaky.getTestCase().setEndpoint(post);
        TestResult suggest = result(TestResultStatus.FAILED, 200, 500, "fp-suggest");
        TestResult calm = result(TestResultStatus.PASSED, 200, 200, "fp-calm");
        TestResult legacy = result(TestResultStatus.FAILED, 200, 500, null);
        List<TestResultRepository.HistoryRow> rows = new ArrayList<>(List.of(
                row(flaky, "fp-flaky", TestResultStatus.PASSED, 201, 0), row(flaky, "fp-flaky", TestResultStatus.FAILED, 500, 1),
                row(flaky, "fp-flaky", TestResultStatus.PASSED, 201, 2), row(flaky, "fp-flaky", TestResultStatus.FAILED, 500, 3),
                row(flaky, "fp-flaky", TestResultStatus.PASSED, 201, 4),
                row(suggest, "fp-suggest", TestResultStatus.PASSED, 200, 0), row(suggest, "fp-suggest", TestResultStatus.FAILED, 500, 1),
                row(calm, "fp-calm", TestResultStatus.PASSED, 200, 0), row(calm, "fp-calm", TestResultStatus.PASSED, 200, 1)));
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenReturn(rows);

        var stability = analyzer.stabilityOf(List.of(flaky, suggest, calm, legacy));

        assertThat(stability).containsOnlyKeys(flaky.getId(), suggest.getId());
        assertThat(stability.get(flaky.getId()).state()).isEqualTo(com.aiapitesting.backend.entity.StabilityState.FLAKY);
        assertThat(stability.get(flaky.getId()).mutatesData()).isTrue();
        assertThat(stability.get(suggest.getId()).state()).isEqualTo(com.aiapitesting.backend.entity.StabilityState.NEEDS_MORE_RUNS);
        assertThat(stability.get(suggest.getId()).rerunReason()).contains("Pass → Fail");
        assertThat(stability.get(suggest.getId()).mutatesData()).as("GET").isFalse();
    }

    @Test
    void stabilityOf_queryFailureReturnsEmptyInsteadOfBreakingThePage() {
        TestResult r = result(TestResultStatus.PASSED, 200, 200, "fp");
        when(testResultRepository.findHistoryRowsByTestCaseIds(anyList())).thenThrow(new RuntimeException("db down"));

        assertThat(analyzer.stabilityOf(List.of(r))).isEmpty();
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
