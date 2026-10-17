package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.CoverageFillRequest;
import com.aiapitesting.backend.dto.response.ChecklistItemType;
import com.aiapitesting.backend.dto.response.CoverageFillResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.entity.TestCaseDependency;
import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
import com.aiapitesting.backend.entity.TestCaseReliability;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Module 15j - bù khoảng trống coverage: tất định, 0 token, chỉ lấp đúng mục còn thiếu. */
@ExtendWith(MockitoExtension.class)
class CoverageGapFillServiceTest {

    /** email (required, format email - không sinh mục chuỗi rỗng): checklist dữ liệu = hợp lệ + thiếu/null/sai kiểu email. */
    private static final String EMAIL_SCHEMA = """
            {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["email"],
              "properties":{"email":{"type":"string","format":"email"}}}}}},
             "responseStatusCodes":["201","400"]}""";

    @Mock
    private ProjectService projectService;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestCaseDependencyRepository testCaseDependencyRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @Spy
    private TestCaseChecklistPlanner testCaseChecklistPlanner = new TestCaseChecklistPlanner();

    @Spy
    private TestCaseReliabilityValidator testCaseReliabilityValidator = new TestCaseReliabilityValidator();

    @Spy
    private TestCasePathValidator testCasePathValidator = new TestCasePathValidator();

    @InjectMocks
    private CoverageGapFillService coverageGapFillService;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = Project.builder().id(projectId).build();
        lenient().when(projectService.getOwnedProject(projectId)).thenReturn(project);
        lenient().when(testCaseRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Endpoint endpoint(String path, String method, boolean requiresAuth, String schema) {
        Endpoint endpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).path(path).method(method)
                .requiresAuth(requiresAuth).schema(schema).build();
        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(Optional.of(endpoint));
        return endpoint;
    }

    private TestCase testCase(Endpoint endpoint, String name, int expectedStatus, String body) {
        return TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).name(name).source(TestCaseSource.AI_GENERATED)
                .expectedStatus(expectedStatus).requestBody(body).resolvedPath(endpoint.getPath())
                .createdAt(Instant.now()).build();
    }

    @SuppressWarnings("unchecked")
    private List<TestCase> savedCases() {
        ArgumentCaptor<List<TestCase>> captor = ArgumentCaptor.forClass(List.class);
        verify(testCaseRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void fill_allMissing_createsOneSystemFilledCasePerItemFromValidBaseline() {
        Endpoint endpoint = endpoint("/users", "POST", false, EMAIL_SCHEMA);
        TestCase positive = testCase(endpoint, "Positive - hop le", 201, "{\"email\":\"an@gmail.com\"}");
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of(positive));

        CoverageFillResponse response = coverageGapFillService.fill(projectId, endpoint.getId(), null);

        // Thiếu email + email null + email sai kiểu (Security không áp dụng vì endpoint không yêu cầu xác thực).
        assertThat(response.createdCount()).isEqualTo(3);
        assertThat(response.notFilled()).isEmpty();
        assertThat(response.baselineTestCaseName()).isEqualTo("Positive - hop le");
        List<TestCase> saved = savedCases();
        assertThat(saved).hasSize(3).allSatisfy(tc -> {
            assertThat(tc.getGenerationOrigin()).isEqualTo(TestCaseGenerationOrigin.SYSTEM_FILLED);
            assertThat(tc.getSource()).isEqualTo(TestCaseSource.AI_GENERATED);
            assertThat(tc.getExpectedStatus()).isEqualTo(400);
            assertThat(tc.getDescription()).contains("trang Coverage").contains("Positive - hop le");
            assertThat(tc.getChecklistItem()).isNotBlank();
            assertThat(tc.getRationale()).isNotBlank();
            assertThat(tc.getReliabilityStatus()).isEqualTo(TestCaseReliability.RELIABLE);
        });
        assertThat(saved).extracting(TestCase::getRequestBody)
                .containsExactlyInAnyOrder("{}", "{\"email\":null}", "{\"email\":12345}");
    }

    @Test
    void fill_requestedKeysOnly_fillsJustThoseItems() {
        Endpoint endpoint = endpoint("/users", "POST", false, EMAIL_SCHEMA);
        TestCase positive = testCase(endpoint, "Positive", 201, "{\"email\":\"an@gmail.com\"}");
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of(positive));

        CoverageFillResponse response = coverageGapFillService.fill(projectId, endpoint.getId(),
                new CoverageFillRequest(List.of("NEGATIVE_NULL_FIELD|BODY|email|", "khoa-khong-ton-tai")));

        assertThat(response.createdCount()).isEqualTo(1);
        assertThat(savedCases()).singleElement().satisfies(tc -> assertThat(tc.getRequestBody()).isEqualTo("{\"email\":null}"));
    }

    @Test
    void fill_everythingAlreadyCovered_createsNothing() {
        Endpoint endpoint = endpoint("/health", "GET", false, "{\"responseStatusCodes\":[\"200\"]}");
        TestCase positive = testCase(endpoint, "Positive", 200, null);
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of(positive));

        CoverageFillResponse response = coverageGapFillService.fill(projectId, endpoint.getId(), null);

        assertThat(response.createdCount()).isZero();
        assertThat(response.notFilled()).isEmpty();
        verify(testCaseRepository, never()).saveAll(anyList());
    }

    /** Không có case gốc hợp lệ thì KHÔNG tạo case sai âm thầm - báo rõ từng mục và cách khắc phục. */
    @Test
    void fill_noValidBaseline_reportsEveryItemAsNotFilledWithReason() {
        Endpoint endpoint = endpoint("/users", "POST", false, EMAIL_SCHEMA);
        TestCase onlyNegative = testCase(endpoint, "Negative - thieu email", 400, "{}");
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of(onlyNegative));

        CoverageFillResponse response = coverageGapFillService.fill(projectId, endpoint.getId(), null);

        assertThat(response.createdCount()).isZero();
        // Positive (không bù được vì chính nó là case gốc) + email null + email sai kiểu.
        assertThat(response.notFilled()).hasSize(3)
                .allSatisfy(nf -> assertThat(nf.reason()).isEqualTo(CoverageGapFillService.NO_BASELINE_REASON));
        assertThat(response.baselineTestCaseName()).isNull();
    }

    /** Case gốc đã chạy PASS thật được ưu tiên hơn case tạo trước nhưng chưa từng chạy. */
    @Test
    void fill_prefersBaselineThatPassedWhenRun() {
        Endpoint endpoint = endpoint("/users", "POST", false, """
                {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["email"],
                  "properties":{"email":{"type":"string","format":"email"},"note":{"type":"string","format":"text"}}}}}}}""");
        TestCase neverRun = testCase(endpoint, "Positive - chua chay", 201, "{\"email\":\"a@x.com\",\"note\":\"cu\"}");
        TestCase passed = testCase(endpoint, "Positive - da PASS", 201, "{\"email\":\"b@x.com\",\"note\":\"that\"}");
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of(neverRun, passed));
        when(testResultRepository.findLatestResultsByProject(project)).thenReturn(List.of(latest(passed.getId(), TestResultStatus.PASSED)));

        CoverageFillResponse response = coverageGapFillService.fill(projectId, endpoint.getId(),
                new CoverageFillRequest(List.of("NEGATIVE_MISSING_FIELD|BODY|email|")));

        assertThat(response.baselineTestCaseName()).isEqualTo("Positive - da PASS");
        assertThat(savedCases()).singleElement().satisfies(tc -> assertThat(tc.getRequestBody()).isEqualTo("{\"note\":\"that\"}"));
    }

    @Test
    void fill_securityItemsOnAuthEndpoint_createSecurityCasesExpecting401() {
        Endpoint endpoint = endpoint("/users", "POST", true, EMAIL_SCHEMA);
        TestCase positive = testCase(endpoint, "Positive", 201, "{\"email\":\"an@gmail.com\"}");
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of(positive));

        coverageGapFillService.fill(projectId, endpoint.getId(), new CoverageFillRequest(List.of(
                "SECURITY_MISSING_TOKEN|||", "SECURITY_INVALID_TOKEN|||")));

        assertThat(savedCases()).hasSize(2).allSatisfy(tc -> {
            assertThat(tc.getSource()).isEqualTo(TestCaseSource.SECURITY);
            assertThat(tc.getExpectedStatus()).isEqualTo(401);
        }).extracting(TestCase::getAuthOverride)
                .containsExactlyInAnyOrder(TestCaseAuthOverride.NONE, TestCaseAuthOverride.INVALID);
    }

    /**
     * Case gốc lấy id thật từ case khác (Test Data Chaining) - case bù còn token {{id}} thì phải chép theo
     * liên kết; case bù đã thay {{id}} bằng giá trị cố định (mục biên id = 0) thì không chép.
     */
    @Test
    void fill_copiesDependencyOnlyWhilePlaceholderStillUsed() {
        Endpoint endpoint = endpoint("/products/{id}", "PUT", false, """
                {"parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer","format":"int64"}}],
                 "requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["name"],
                   "properties":{"name":{"type":"string","format":"text"}}}}}}}""");
        TestCase baseline = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).name("Positive")
                .source(TestCaseSource.AI_GENERATED).expectedStatus(200).requestBody("{\"name\":\"Ao\"}")
                .resolvedPath("/products/{{id}}").pathParamFallbacks("{\"id\":\"1\"}").createdAt(Instant.now()).build();
        TestCase producer = TestCase.builder().id(UUID.randomUUID()).name("Tao san pham").build();
        TestCaseDependency chain = TestCaseDependency.builder().testCase(baseline).dependsOnTestCase(producer)
                .jsonPath("$.id").placeholderName("id").build();
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of(baseline));
        when(testCaseDependencyRepository.findAllByTestCase(baseline)).thenReturn(List.of(chain));

        coverageGapFillService.fill(projectId, endpoint.getId(), new CoverageFillRequest(List.of(
                "NEGATIVE_MISSING_FIELD|BODY|name|", "BOUNDARY_ZERO|PATH|id|0")));

        List<TestCase> saved = savedCases();
        TestCase missingName = saved.stream().filter(tc -> tc.getRequestBody().equals("{}")).findFirst().orElseThrow();
        TestCase idZero = saved.stream().filter(tc -> tc.getResolvedPath().equals("/products/0")).findFirst().orElseThrow();
        assertThat(idZero.getExpectedStatus()).isEqualTo(404);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestCaseDependency>> captor = ArgumentCaptor.forClass(List.class);
        verify(testCaseDependencyRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(dep -> {
            assertThat(dep.getTestCase()).isSameAs(missingName);
            assertThat(dep.getDependsOnTestCase()).isSameAs(producer);
            assertThat(dep.getPlaceholderName()).isEqualTo("id");
            assertThat(dep.getJsonPath()).isEqualTo("$.id");
        });
    }

    @Test
    void fill_unknownEndpoint_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(endpointRepository.findByIdAndProject(unknown, project)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> coverageGapFillService.fill(projectId, unknown, null))
                .isInstanceOf(EndpointNotFoundException.class);
    }

    @Test
    void checklistKeysUsedInTests_matchPlannerKeys() {
        // Khoá viết tay trong các test trên phải khớp đúng khoá planner sinh - tránh test "pass" vì lọc nhầm rỗng.
        TestCaseChecklistPlanner planner = new TestCaseChecklistPlanner();
        List<String> keys = planner.planAll(planner.readSpec(Endpoint.builder().path("/users").schema(EMAIL_SCHEMA).build()), true)
                .stream().map(TestCaseChecklistPlanner::key).toList();

        assertThat(keys).contains("NEGATIVE_NULL_FIELD|BODY|email|", "NEGATIVE_MISSING_FIELD|BODY|email|",
                "SECURITY_MISSING_TOKEN|||", "SECURITY_INVALID_TOKEN|||");
        assertThat(ChecklistItemType.valueOf("BOUNDARY_ZERO")).isNotNull();
    }

    private TestResultRepository.TestCaseLatestResult latest(UUID testCaseId, TestResultStatus status) {
        return new TestResultRepository.TestCaseLatestResult() {
            @Override
            public UUID getTestCaseId() {
                return testCaseId;
            }

            @Override
            public TestResultStatus getStatus() {
                return status;
            }
        };
    }
}
