package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.AssertionOperator;
import com.aiapitesting.backend.entity.Endpoint;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestExecutionRunnerTest {

    @Mock
    private TestExecutionRepository testExecutionRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private RestAssuredTestRunner restAssuredTestRunner;

    @Spy
    private TestCasePathValidator testCasePathValidator = new TestCasePathValidator();

    @Mock
    private BugReportStatusService bugReportStatusService;

    @Mock
    private ExecutionFailureAnalyzer executionFailureAnalyzer;

    private TestExecutionRunner runner;

    private Project project;
    private Endpoint endpoint;
    private TestExecution execution;

    @BeforeEach
    void setUp() {
        runner = new TestExecutionRunner(
                testExecutionRepository, testResultRepository, restAssuredTestRunner,
                testCasePathValidator, bugReportStatusService, executionFailureAnalyzer);
        project = Project.builder().id(UUID.randomUUID()).targetBaseUrl("https://petstore.example.com").build();
        endpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/pet/{petId}").build();
        execution = TestExecution.builder().id(UUID.randomUUID()).project(project)
                .status(ExecutionStatus.PENDING).startedAt(Instant.now()).build();
    }

    @Test
    void runInBackground_statusMatchesExpected_savesPassedAndCompletesExecution() {
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint)
                .resolvedPath("/pet/{{petId}}").pathParamFallbacks("{\"petId\":\"1\"}")
                .expectedStatus(200).createdAt(Instant.now()).build();
        when(restAssuredTestRunner.run(eq(project), eq(testCase), eq(Map.of("petId", "1"))))
                .thenReturn(new RestAssuredTestRunner.RunResult(200, "{\"id\":1}"));

        runner.runInBackground(execution, List.of(testCase), project, Map.of(), Set.of(), Map.of());

        ArgumentCaptor<TestResult> resultCaptor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getStatus()).isEqualTo(TestResultStatus.PASSED);
        assertThat(resultCaptor.getValue().getResponseStatus()).isEqualTo(200);

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(execution.getFinishedAt()).isNotNull();
        verify(testExecutionRepository, times(2)).save(execution);
    }

    @Test
    void runInBackground_statusMismatch_savesFailed() {
        Endpoint noParamEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/pets").build();
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(noParamEndpoint)
                .resolvedPath("/pets").expectedStatus(200).createdAt(Instant.now()).build();
        when(restAssuredTestRunner.run(eq(project), eq(testCase), eq(Map.of())))
                .thenReturn(new RestAssuredTestRunner.RunResult(404, "not found"));

        runner.runInBackground(execution, List.of(testCase), project, Map.of(), Set.of(), Map.of());

        ArgumentCaptor<TestResult> resultCaptor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getStatus()).isEqualTo(TestResultStatus.FAILED);
    }

    @Test
    void runInBackground_missingFallbackForPlaceholder_savesErrorWithoutCallingRunner() {
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint)
                .resolvedPath("/pet/{{petId}}").pathParamFallbacks(null)
                .expectedStatus(200).createdAt(Instant.now()).build();

        runner.runInBackground(execution, List.of(testCase), project, Map.of(), Set.of(), Map.of());

        ArgumentCaptor<TestResult> resultCaptor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getStatus()).isEqualTo(TestResultStatus.ERROR);
        verify(restAssuredTestRunner, never()).run(any(), any(), any());
    }

    @Test
    void runInBackground_runnerThrows_savesErrorAndStillCompletesExecution() {
        Endpoint noParamEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/pets").build();
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(noParamEndpoint)
                .resolvedPath("/pets").expectedStatus(200).createdAt(Instant.now()).build();
        when(restAssuredTestRunner.run(any(), any(), any())).thenThrow(new RuntimeException("connection refused"));

        runner.runInBackground(execution, List.of(testCase), project, Map.of(), Set.of(), Map.of());

        ArgumentCaptor<TestResult> resultCaptor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getStatus()).isEqualTo(TestResultStatus.ERROR);
        // 1 test case loi ha tang khong lam hong ca execution - van COMPLETED, khong phai FAILED
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void runInBackground_multipleTestCases_runsSequentiallyInGivenOrder() {
        Endpoint noParamEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/pets").build();
        TestCase first = TestCase.builder().id(UUID.randomUUID()).endpoint(noParamEndpoint)
                .resolvedPath("/pets").expectedStatus(200).createdAt(Instant.now()).build();
        TestCase second = TestCase.builder().id(UUID.randomUUID()).endpoint(noParamEndpoint)
                .resolvedPath("/pets").expectedStatus(200).createdAt(Instant.now()).build();
        when(restAssuredTestRunner.run(any(), any(), any()))
                .thenReturn(new RestAssuredTestRunner.RunResult(200, "{}"));

        runner.runInBackground(execution, List.of(first, second), project, Map.of(), Set.of(), Map.of());

        verify(restAssuredTestRunner, times(2)).run(eq(project), any(TestCase.class), eq(Map.of()));
        verify(testResultRepository, times(2)).save(any(TestResult.class));
    }

    @Test
    void runInBackground_dependencySourcePassed_usesRealValueFromResponseNotFallback() {
        Endpoint createEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/pet").build();
        TestCase source = TestCase.builder().id(UUID.randomUUID()).endpoint(createEndpoint)
                .resolvedPath("/pet").requestBody("{}").expectedStatus(201).createdAt(Instant.now()).build();
        TestCase consumer = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint)
                .resolvedPath("/pet/{{petId}}").pathParamFallbacks("{\"petId\":\"999\"}")
                .expectedStatus(200).createdAt(Instant.now()).build();

        when(restAssuredTestRunner.run(eq(project), eq(source), eq(Map.of())))
                .thenReturn(new RestAssuredTestRunner.RunResult(201, "{\"id\":42}"));
        when(restAssuredTestRunner.run(eq(project), eq(consumer), eq(Map.of("petId", "42"))))
                .thenReturn(new RestAssuredTestRunner.RunResult(200, "{\"id\":42}"));

        Map<UUID, List<DependencyEdge>> edges = Map.of(
                consumer.getId(), List.of(new DependencyEdge(source.getId(), "Tao pet", "$.id", "petId")));

        runner.runInBackground(execution, List.of(source, consumer), project, edges, Set.of(source.getId()), Map.of());

        verify(restAssuredTestRunner).run(eq(project), eq(consumer), eq(Map.of("petId", "42")));
        verify(restAssuredTestRunner, never()).run(eq(project), eq(consumer), eq(Map.of("petId", "999")));

        ArgumentCaptor<TestResult> resultCaptor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository, times(2)).save(resultCaptor.capture());
        TestResult sourceResult = resultCaptor.getAllValues().stream()
                .filter(r -> r.getTestCase().getId().equals(source.getId())).findFirst().orElseThrow();
        TestResult consumerResult = resultCaptor.getAllValues().stream()
                .filter(r -> r.getTestCase().getId().equals(consumer.getId())).findFirst().orElseThrow();
        assertThat(sourceResult.isAutoIncluded()).isTrue();
        assertThat(consumerResult.isAutoIncluded()).isFalse();
    }

    @Test
    void runInBackground_systemFilledConsumers_eachGetTheirOwnFreshParentResource() {
        Endpoint createEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/pet").build();
        TestCase source = TestCase.builder().id(UUID.randomUUID()).endpoint(createEndpoint)
                .resolvedPath("/pet").requestBody("{}").expectedStatus(201).createdAt(Instant.now()).build();
        TestCase first = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).resolvedPath("/pet/{{petId}}")
                .pathParamFallbacks("{\"petId\":\"999\"}").expectedStatus(200).createdAt(Instant.now())
                .generationOrigin(com.aiapitesting.backend.entity.TestCaseGenerationOrigin.SYSTEM_FILLED).build();
        TestCase second = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).resolvedPath("/pet/{{petId}}")
                .pathParamFallbacks("{\"petId\":\"999\"}").expectedStatus(200).createdAt(Instant.now())
                .generationOrigin(com.aiapitesting.backend.entity.TestCaseGenerationOrigin.SYSTEM_FILLED).build();
        // Mỗi lần gọi test nguồn trả 1 id mới: lần chạy chính thức -> 1, hai lần làm mới trước test con -> 2 và 3
        when(restAssuredTestRunner.run(eq(project), eq(source), eq(Map.of())))
                .thenReturn(new RestAssuredTestRunner.RunResult(201, "{\"id\":1}"), new RestAssuredTestRunner.RunResult(201, "{\"id\":2}"),
                        new RestAssuredTestRunner.RunResult(201, "{\"id\":3}"));
        when(restAssuredTestRunner.run(eq(project), any(TestCase.class), org.mockito.ArgumentMatchers.argThat(m -> m != null && !m.isEmpty())))
                .thenReturn(new RestAssuredTestRunner.RunResult(200, "{}"));
        DependencyEdge edge = new DependencyEdge(source.getId(), "Tao pet", "$.id", "petId");

        runner.runInBackground(execution, List.of(source, first, second), project,
                Map.of(first.getId(), List.of(edge), second.getId(), List.of(edge)), Set.of(source.getId()), Map.of());

        verify(restAssuredTestRunner).run(eq(project), eq(first), eq(Map.of("petId", "2")));
        verify(restAssuredTestRunner).run(eq(project), eq(second), eq(Map.of("petId", "3")));
        // Chỉ 2 kết quả test + 1 kết quả nguồn được lưu: các lần làm mới là bước chuẩn bị, không thành kết quả
        verify(testResultRepository, times(3)).save(any(TestResult.class));
    }

    @Test
    void runInBackground_dependencySourceNotPassed_marksConsumerBlockedWithoutCallingRunnerForIt() {
        Endpoint createEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/pet").build();
        TestCase source = TestCase.builder().id(UUID.randomUUID()).endpoint(createEndpoint)
                .resolvedPath("/pet").requestBody("{}").expectedStatus(201).createdAt(Instant.now()).build();
        TestCase consumer = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint)
                .resolvedPath("/pet/{{petId}}").pathParamFallbacks("{\"petId\":\"999\"}")
                .expectedStatus(200).createdAt(Instant.now()).build();

        // Nguon fail (status khac expected) - khong PASSED
        when(restAssuredTestRunner.run(eq(project), eq(source), eq(Map.of())))
                .thenReturn(new RestAssuredTestRunner.RunResult(500, "error"));

        Map<UUID, List<DependencyEdge>> edges = Map.of(
                consumer.getId(), List.of(new DependencyEdge(source.getId(), "Tao pet", "$.id", "petId")));

        runner.runInBackground(execution, List.of(source, consumer), project, edges, Set.of(), Map.of());

        verify(restAssuredTestRunner, never()).run(eq(project), eq(consumer), any());

        ArgumentCaptor<TestResult> resultCaptor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository, times(2)).save(resultCaptor.capture());
        TestResult consumerResult = resultCaptor.getAllValues().stream()
                .filter(r -> r.getTestCase().getId().equals(consumer.getId())).findFirst().orElseThrow();
        assertThat(consumerResult.getStatus()).isEqualTo(TestResultStatus.BLOCKED);
    }

    @Test
    void runInBackground_statusMatchesButAssertionFails_savesFailedWithAssertionDetail() {
        Endpoint noParamEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/products").build();
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(noParamEndpoint)
                .resolvedPath("/products").expectedStatus(201).createdAt(Instant.now()).build();
        when(restAssuredTestRunner.run(eq(project), eq(testCase), eq(Map.of())))
                .thenReturn(new RestAssuredTestRunner.RunResult(201, "{\"price\":19.99}"));

        Map<UUID, List<AssertionSpec>> assertions = Map.of(
                testCase.getId(), List.of(new AssertionSpec("price", AssertionOperator.EQUALS, "9.99")));

        runner.runInBackground(execution, List.of(testCase), project, Map.of(), Set.of(), assertions);

        ArgumentCaptor<TestResult> resultCaptor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository).save(resultCaptor.capture());
        TestResult result = resultCaptor.getValue();
        // Status code khop expectedStatus (201) nhung assertion sai gia tri -> van phai la FAILED
        assertThat(result.getStatus()).isEqualTo(TestResultStatus.FAILED);
        assertThat(result.getAssertionResultsJson())
                .contains("\"passed\":false")
                .contains("\"actualValue\":\"19.99\"")
                .contains("\"expectedValue\":\"9.99\"");
    }

    @Test
    void runInBackground_statusMatchesAndAssertionPasses_savesPassed() {
        Endpoint noParamEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/products").build();
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(noParamEndpoint)
                .resolvedPath("/products").expectedStatus(201).createdAt(Instant.now()).build();
        when(restAssuredTestRunner.run(eq(project), eq(testCase), eq(Map.of())))
                .thenReturn(new RestAssuredTestRunner.RunResult(201, "{\"price\":19.99}"));

        Map<UUID, List<AssertionSpec>> assertions = Map.of(
                testCase.getId(), List.of(new AssertionSpec("price", AssertionOperator.EQUALS, "19.99")));

        runner.runInBackground(execution, List.of(testCase), project, Map.of(), Set.of(), assertions);

        ArgumentCaptor<TestResult> resultCaptor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository).save(resultCaptor.capture());
        TestResult result = resultCaptor.getValue();
        assertThat(result.getStatus()).isEqualTo(TestResultStatus.PASSED);
        assertThat(result.getAssertionResultsJson()).contains("\"passed\":true");
    }

    @Test
    void runInBackground_existsAssertionOnMissingField_savesFailed() {
        Endpoint noParamEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/products").build();
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(noParamEndpoint)
                .resolvedPath("/products").expectedStatus(201).createdAt(Instant.now()).build();
        when(restAssuredTestRunner.run(eq(project), eq(testCase), eq(Map.of())))
                .thenReturn(new RestAssuredTestRunner.RunResult(201, "{\"name\":\"Ao\"}"));

        Map<UUID, List<AssertionSpec>> assertions = Map.of(
                testCase.getId(), List.of(new AssertionSpec("stock", AssertionOperator.EXISTS, null)));

        runner.runInBackground(execution, List.of(testCase), project, Map.of(), Set.of(), assertions);

        ArgumentCaptor<TestResult> resultCaptor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getStatus()).isEqualTo(TestResultStatus.FAILED);
    }

    // ---- Module 16 - dữ liệu thu thập + phân tích nguyên nhân fail + thứ tự tự động hoá Bug Report ----

    private TestCase simpleCase() {
        Endpoint noParamEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/pets").build();
        return TestCase.builder().id(UUID.randomUUID()).endpoint(noParamEndpoint)
                .resolvedPath("/pets").expectedStatus(200).createdAt(Instant.now()).build();
    }

    @Test
    void runInBackground_callingTarget_recordsDurationAndFingerprint() {
        TestCase testCase = simpleCase();
        when(restAssuredTestRunner.run(any(), any(), any())).thenReturn(new RestAssuredTestRunner.RunResult(200, "{}"));

        runner.runInBackground(execution, List.of(testCase), project, Map.of(), Set.of(), Map.of());

        ArgumentCaptor<TestResult> captor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository).save(captor.capture());
        assertThat(captor.getValue().getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(captor.getValue().getErrorKind()).isNull();
        assertThat(captor.getValue().getTestCaseFingerprint()).hasSize(64);
    }

    // ---- Module 17 (17b) - quét dữ liệu nhạy cảm trong response ----

    @Test
    void runInBackground_responseWithSensitiveField_savesFindingWithoutTheRealValue_andDoesNotChangeStatus() {
        TestCase testCase = simpleCase();
        when(restAssuredTestRunner.run(any(), any(), any()))
                .thenReturn(new RestAssuredTestRunner.RunResult(200, "{\"id\":1,\"passwordHash\":\"$2a$10$REAL-HASH-VALUE-abcdefghijk\"}"));

        runner.runInBackground(execution, List.of(testCase), project, Map.of(), Set.of(), Map.of());

        ArgumentCaptor<TestResult> captor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(TestResultStatus.PASSED); // chỉ là cảnh báo, không Fail
        assertThat(captor.getValue().getSecurityFindingsJson()).contains("passwordHash").doesNotContain("REAL-HASH-VALUE");
        // body lưu/hiển thị đã che giá trị, các field khác còn nguyên
        assertThat(captor.getValue().getResponseBody()).doesNotContain("REAL-HASH-VALUE").contains("\"id\":1")
                .contains(SensitiveDataScanner.MASK_MARKER);
    }

    @Test
    void runInBackground_cleanResponseOrNon2xx_hasNoFindings() {
        TestCase ok = simpleCase();
        TestCase notFound = simpleCase();
        when(restAssuredTestRunner.run(any(), eq(ok), any())).thenReturn(new RestAssuredTestRunner.RunResult(200, "{\"name\":\"x\"}"));
        when(restAssuredTestRunner.run(any(), eq(notFound), any())).thenReturn(new RestAssuredTestRunner.RunResult(404, "{\"password\":\"abcdefgh\"}"));

        runner.runInBackground(execution, List.of(ok, notFound), project, Map.of(), Set.of(), Map.of());

        ArgumentCaptor<TestResult> captor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(r -> assertThat(r.getSecurityFindingsJson()).isNull());
    }

    // ---- Module 17 (17b-2) - probe mass-assignment ----

    private TestCase probeCase() {
        TestCase testCase = simpleCase();
        testCase.setProbeKind(MassAssignmentProbe.KIND);
        testCase.setProbeFieldsJson("[{\"name\":\"role\",\"type\":\"STRING\",\"sentinel\":\"zz_probe_abcd1234\"}]");
        return testCase;
    }

    @Test
    void runInBackground_probeWhoseSentinelIsEchoed_failsWithFindingEvenThoughStatusMatches() {
        TestCase testCase = probeCase();
        when(restAssuredTestRunner.run(any(), any(), any()))
                .thenReturn(new RestAssuredTestRunner.RunResult(200, "{\"id\":1,\"role\":\"zz_probe_abcd1234\"}"));

        runner.runInBackground(execution, List.of(testCase), project, Map.of(), Set.of(), Map.of());

        ArgumentCaptor<TestResult> captor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(TestResultStatus.FAILED);
        assertThat(captor.getValue().getSecurityFindingsJson()).contains(MassAssignmentProbe.RULE_ACCEPTED).contains("role");
    }

    @Test
    void runInBackground_probeRejectedWith400_orIgnored_passesEvenThoughStatusDiffersFromExpected() {
        TestCase rejected = probeCase();
        TestCase ignored = probeCase();
        when(restAssuredTestRunner.run(any(), eq(rejected), any())).thenReturn(new RestAssuredTestRunner.RunResult(400, "{\"error\":\"unknown field\"}"));
        when(restAssuredTestRunner.run(any(), eq(ignored), any())).thenReturn(new RestAssuredTestRunner.RunResult(200, "{\"id\":1,\"role\":\"member\"}"));

        runner.runInBackground(execution, List.of(rejected, ignored), project, Map.of(), Set.of(), Map.of());

        ArgumentCaptor<TestResult> captor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(TestResultStatus.PASSED);
            assertThat(r.getSecurityFindingsJson()).isNull();
        });
    }

    @Test
    void runInBackground_targetUnreachable_recordsErrorKindByExceptionTypeNotMessage() {
        TestCase testCase = simpleCase();
        // Thông báo cố tình KHÔNG chứa chữ "timeout"/"refused" - phải phân loại theo loại exception.
        when(restAssuredTestRunner.run(any(), any(), any()))
                .thenThrow(new RuntimeException("wrapper", new java.net.SocketTimeoutException("x")));

        runner.runInBackground(execution, List.of(testCase), project, Map.of(), Set.of(), Map.of());

        ArgumentCaptor<TestResult> captor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(TestResultStatus.ERROR);
        assertThat(captor.getValue().getErrorKind()).isEqualTo(com.aiapitesting.backend.entity.ErrorKind.TIMEOUT);
        assertThat(captor.getValue().getDurationMs()).isNotNull();
    }

    @Test
    void runInBackground_missingFallback_errorHasNoErrorKindAndNoDuration() {
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint)
                .resolvedPath("/pet/{{petId}}").pathParamFallbacks(null)
                .expectedStatus(200).createdAt(Instant.now()).build();

        runner.runInBackground(execution, List.of(testCase), project, Map.of(), Set.of(), Map.of());

        ArgumentCaptor<TestResult> captor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository).save(captor.capture());
        // Không gọi target nên không có errorKind (= lỗi cấu hình test, khác lỗi hạ tầng) và không có thời gian.
        assertThat(captor.getValue().getErrorKind()).isNull();
        assertThat(captor.getValue().getDurationMs()).isNull();
        assertThat(captor.getValue().getTestCaseFingerprint()).isNotNull();
    }

    /** Bug Report tự động hoá phải chạy SAU phân tích nguyên nhân (để biết fail do token/môi trường) - và đúng 1 lần/kết quả. */
    @Test
    void runInBackground_analyzesFailuresBeforeNotifyingBugReports() {
        TestCase first = simpleCase();
        TestCase second = simpleCase();
        when(restAssuredTestRunner.run(any(), any(), any())).thenReturn(new RestAssuredTestRunner.RunResult(401, "no"));

        runner.runInBackground(execution, List.of(first, second), project, Map.of(), Set.of(), Map.of());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(executionFailureAnalyzer, bugReportStatusService);
        order.verify(executionFailureAnalyzer).analyze(eq(execution), any());
        order.verify(bugReportStatusService, times(2)).onNewTestResult(any(TestResult.class));
    }

    @Test
    void runInBackground_analyzerReceivesEveryResultOfTheExecution() {
        TestCase first = simpleCase();
        TestCase second = simpleCase();
        when(restAssuredTestRunner.run(any(), any(), any())).thenReturn(new RestAssuredTestRunner.RunResult(200, "{}"));

        runner.runInBackground(execution, List.of(first, second), project, Map.of(), Set.of(), Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(executionFailureAnalyzer).analyze(eq(execution), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    void runInBackground_statusMismatch_doesNotEvaluateAssertionsAtAll() {
        Endpoint noParamEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/products").build();
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(noParamEndpoint)
                .resolvedPath("/products").expectedStatus(201).createdAt(Instant.now()).build();
        when(restAssuredTestRunner.run(eq(project), eq(testCase), eq(Map.of())))
                .thenReturn(new RestAssuredTestRunner.RunResult(400, "{\"error\":\"bad request\"}"));

        Map<UUID, List<AssertionSpec>> assertions = Map.of(
                testCase.getId(), List.of(new AssertionSpec("price", AssertionOperator.EQUALS, "19.99")));

        runner.runInBackground(execution, List.of(testCase), project, Map.of(), Set.of(), assertions);

        ArgumentCaptor<TestResult> resultCaptor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository).save(resultCaptor.capture());
        TestResult result = resultCaptor.getValue();
        assertThat(result.getStatus()).isEqualTo(TestResultStatus.FAILED);
        // Status code da sai tu dau - khong co y nghia cham assertion, khong luu ket qua assertion nao.
        assertThat(result.getAssertionResultsJson()).isNull();
    }
}
