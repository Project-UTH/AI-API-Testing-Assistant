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
    private com.aiapitesting.backend.repository.AuthProfileRepository authProfileRepository;

    @Mock
    private com.aiapitesting.backend.repository.EndpointRoleRepository endpointRoleRepository;

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

        // Thiếu email + email null (Security không áp dụng vì endpoint không yêu cầu xác thực; không còn mục "sai kiểu" cho
        // trường chuỗi - Module 17 1.2).
        assertThat(response.createdCount()).isEqualTo(2);
        assertThat(response.notFilled()).isEmpty();
        assertThat(response.baselineTestCaseName()).isEqualTo("Positive - hop le");
        List<TestCase> saved = savedCases();
        assertThat(saved).hasSize(2).allSatisfy(tc -> {
            assertThat(tc.getGenerationOrigin()).isEqualTo(TestCaseGenerationOrigin.SYSTEM_FILLED);
            assertThat(tc.getSource()).isEqualTo(TestCaseSource.AI_GENERATED);
            assertThat(tc.getExpectedStatus()).isEqualTo(400);
            assertThat(tc.getDescription()).contains("trang Coverage").contains("Positive - hop le");
            assertThat(tc.getChecklistItem()).isNotBlank();
            assertThat(tc.getRationale()).isNotBlank();
            assertThat(tc.getReliabilityStatus()).isEqualTo(TestCaseReliability.RELIABLE);
        });
        assertThat(saved).extracting(TestCase::getRequestBody)
                .containsExactlyInAnyOrder("{}", "{\"email\":null}");
    }

    // ---- Module 17 (17a) - ma trận phân quyền ----

    private com.aiapitesting.backend.entity.AuthProfile profile(String name, String roleKey) {
        com.aiapitesting.backend.entity.AuthProfile p = com.aiapitesting.backend.entity.AuthProfile.builder()
                .id(UUID.randomUUID()).project(project).name(name).roleKey(roleKey)
                .authType(com.aiapitesting.backend.entity.TargetAuthType.BEARER_TOKEN).build();
        when(authProfileRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(p));
        return p;
    }

    private com.aiapitesting.backend.entity.EndpointRole rule(Endpoint endpoint, com.aiapitesting.backend.entity.AuthProfile profile,
                                                              com.aiapitesting.backend.entity.AuthzExpectation expectation) {
        com.aiapitesting.backend.entity.EndpointRole r = com.aiapitesting.backend.entity.EndpointRole.builder()
                .id(UUID.randomUUID()).endpoint(endpoint).authProfileId(profile.getId()).expectation(expectation).build();
        when(endpointRoleRepository.findAllByEndpoint(endpoint)).thenReturn(List.of(r));
        return r;
    }

    @Test
    void fill_authzDeny_copiesBaselineWithProfileAndExpects403_onlyThatItem() {
        Endpoint endpoint = endpoint("/admin/users", "POST", false, EMAIL_SCHEMA);
        TestCase positive = testCase(endpoint, "Tạo user hợp lệ", 201, "{\"email\":\"an@gmail.com\"}");
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of(positive));
        var candidate = profile("Ứng viên", "CANDIDATE");
        rule(endpoint, candidate, com.aiapitesting.backend.entity.AuthzExpectation.DENY);

        CoverageFillResponse response = coverageGapFillService.fill(projectId, endpoint.getId(),
                new com.aiapitesting.backend.dto.request.CoverageFillRequest(List.of("AUTHZ_DENIED|||CANDIDATE")));

        assertThat(response.createdCount()).isEqualTo(1);
        List<TestCase> saved = savedCases();
        assertThat(saved).singleElement().satisfies(tc -> {
            assertThat(tc.getAuthProfileId()).isEqualTo(candidate.getId());
            assertThat(tc.getExpectedStatus()).isEqualTo(403);
            assertThat(tc.getRequestBody()).isEqualTo("{\"email\":\"an@gmail.com\"}");
            assertThat(tc.getAuthOverride()).isEqualTo(TestCaseAuthOverride.DEFAULT);
            // MANUAL để lần "Sinh Test Case" sau (chỉ xoá nhóm AI/Security chưa khoá) không xoá mất case phân quyền.
            assertThat(tc.getSource()).isEqualTo(TestCaseSource.MANUAL);
            assertThat(tc.getGenerationOrigin()).isEqualTo(TestCaseGenerationOrigin.SYSTEM_FILLED);
        });
    }

    @Test
    void fill_authzAllow_keepsBaselineSuccessStatus_andSkipsCellsAlreadyCovered() {
        Endpoint endpoint = endpoint("/admin/users", "POST", false, EMAIL_SCHEMA);
        TestCase positive = testCase(endpoint, "Tạo user hợp lệ", 201, "{\"email\":\"an@gmail.com\"}");
        var hr = profile("HR", "HR");
        TestCase alreadyThere = testCase(endpoint, "HR tạo user", 201, "{\"email\":\"an@gmail.com\"}");
        alreadyThere.setAuthProfileId(hr.getId());
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of(positive, alreadyThere));
        rule(endpoint, hr, com.aiapitesting.backend.entity.AuthzExpectation.ALLOW);

        CoverageFillResponse response = coverageGapFillService.fill(projectId, endpoint.getId(),
                new com.aiapitesting.backend.dto.request.CoverageFillRequest(List.of("AUTHZ_ALLOWED|||HR")));

        // Ô đã có test gọi bằng vai trò HR kỳ vọng 2xx -> không tạo trùng.
        assertThat(response.createdCount()).isZero();
    }

    @Test
    void fill_authzAllow_withoutAnyUsableBaseline_reportedNotFilled() {
        Endpoint endpoint = endpoint("/admin/users", "POST", false, EMAIL_SCHEMA);
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of());
        var hr = profile("HR", "HR");
        rule(endpoint, hr, com.aiapitesting.backend.entity.AuthzExpectation.ALLOW);

        CoverageFillResponse response = coverageGapFillService.fill(projectId, endpoint.getId(),
                new com.aiapitesting.backend.dto.request.CoverageFillRequest(List.of("AUTHZ_ALLOWED|||HR")));

        assertThat(response.createdCount()).isZero();
        assertThat(response.notFilled()).singleElement().satisfies(nf -> assertThat(nf.reason()).contains("test case làm gốc"));
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

    /**
     * Không có case gốc hợp lệ VÀ endpoint còn đầu vào bắt buộc khác (email + age) thì KHÔNG bịa dữ liệu cho
     * field còn lại - báo rõ từng mục và cách khắc phục, không tạo case sai âm thầm.
     */
    @Test
    void fill_noValidBaselineAndOtherRequiredInputs_reportsEveryItemAsNotFilledWithReason() {
        Endpoint endpoint = endpoint("/users", "POST", false, """
                {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["email","age"],
                  "properties":{"email":{"type":"string","format":"email"},"age":{"type":"integer","format":"int32"}}}}}}}""");
        TestCase onlyNegative = testCase(endpoint, "Negative - thieu email", 400, "{}");
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of(onlyNegative));

        CoverageFillResponse response = coverageGapFillService.fill(projectId, endpoint.getId(), null);

        assertThat(response.createdCount()).isZero();
        assertThat(response.fromSpecCount()).isZero();
        assertThat(response.notFilled()).isNotEmpty()
                .allSatisfy(nf -> assertThat(nf.reason()).isEqualTo(CoverageGapFillService.NO_BASELINE_REASON));
        assertThat(response.baselineTestCaseName()).isNull();
        verify(testCaseRepository, never()).saveAll(anyList());
    }

    /**
     * Endpoint chỉ có 1 tham số path (vd DELETE /products/{id}), chưa có test case nào: các mục kỳ vọng LỖI không cần
     * dữ liệu từ field nào khác nên dựng thẳng từ đặc tả; riêng mục Positive cần id thật nên vẫn không tự tạo.
     */
    @Test
    void fill_noCasesAtAll_pathIdOnlyEndpoint_buildsErrorItemsFromSpecAndReportsPositive() {
        Endpoint endpoint = endpoint("/products/{id}", "DELETE", false, """
                {"parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer","format":"int64"}}],
                 "responseStatusCodes":["204","404"]}""");
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of());

        CoverageFillResponse response = coverageGapFillService.fill(projectId, endpoint.getId(), null);

        // 1 sai kiểu + 5 biên của id (lớn nhất int64, vượt, dưới nhỏ nhất, 0, -1).
        assertThat(response.createdCount()).isEqualTo(6);
        assertThat(response.fromSpecCount()).isEqualTo(6);
        assertThat(response.baselineTestCaseName()).isNull();
        assertThat(response.notFilled()).singleElement().satisfies(nf -> {
            assertThat(nf.itemKey()).isEqualTo("POSITIVE_BASELINE|||");
            assertThat(nf.reason()).isEqualTo(CoverageGapFillService.NO_BASELINE_REASON);
        });
        List<TestCase> saved = savedCases();
        assertThat(saved).extracting(TestCase::getResolvedPath).containsExactlyInAnyOrder(
                "/products/khong-phai-so", "/products/9223372036854775807", "/products/9223372036854775808",
                "/products/-9223372036854775809", "/products/0", "/products/-1");
        assertThat(saved).allSatisfy(tc -> {
            assertThat(tc.getGenerationOrigin()).isEqualTo(TestCaseGenerationOrigin.SYSTEM_FILLED);
            assertThat(tc.getRequestBody()).isNull();
            assertThat(tc.getDescription()).contains("dựng từ đặc tả").doesNotContain("copy case gốc");
        });
        // Id không tồn tại kỳ vọng 404; id sai kiểu/vượt giới hạn kiểu kỳ vọng 400.
        assertThat(saved).filteredOn(tc -> tc.getResolvedPath().equals("/products/0"))
                .singleElement().satisfies(tc -> assertThat(tc.getExpectedStatus()).isEqualTo(404));
        assertThat(saved).filteredOn(tc -> tc.getResolvedPath().equals("/products/9223372036854775808"))
                .singleElement().satisfies(tc -> assertThat(tc.getExpectedStatus()).isEqualTo(400));
        // Không có case gốc nên không đụng tới liên kết dữ liệu.
        verify(testCaseDependencyRepository, never()).saveAll(anyList());
    }

    @Test
    void fill_noCasesAtAll_singleRequiredField_buildsNegativeCasesFromSpec() {
        Endpoint endpoint = endpoint("/users", "POST", false, EMAIL_SCHEMA);
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of());

        CoverageFillResponse response = coverageGapFillService.fill(projectId, endpoint.getId(), null);

        assertThat(response.createdCount()).isEqualTo(2);
        assertThat(response.notFilled()).singleElement().satisfies(nf -> assertThat(nf.itemKey()).isEqualTo("POSITIVE_BASELINE|||"));
        assertThat(savedCases()).extracting(TestCase::getRequestBody)
                .containsExactlyInAnyOrder("{}", "{\"email\":null}");
        assertThat(savedCases()).allSatisfy(tc -> assertThat(tc.getExpectedStatus()).isEqualTo(400));
    }

    /** Endpoint cần token, chưa có test nào, không có tham số path: 2 mục Security dựng được từ đặc tả (chỉ đổi cách gọi). */
    @Test
    void fill_noCasesAtAll_authEndpointWithoutPathParams_buildsSecurityCasesFromSpec() {
        Endpoint endpoint = endpoint("/orders", "GET", true, "{\"responseStatusCodes\":[\"200\",\"401\"]}");
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of());

        coverageGapFillService.fill(projectId, endpoint.getId(), new CoverageFillRequest(List.of(
                "SECURITY_MISSING_TOKEN|||", "SECURITY_INVALID_TOKEN|||")));

        assertThat(savedCases()).hasSize(2).allSatisfy(tc -> {
            assertThat(tc.getSource()).isEqualTo(TestCaseSource.SECURITY);
            assertThat(tc.getExpectedStatus()).isEqualTo(401);
            assertThat(tc.getResolvedPath()).isEqualTo("/orders");
        });
    }

    /** Tham số path chưa có giá trị thật: gọi "/orders/{id}" nguyên văn không chạy được, nên không dựng case Security. */
    @Test
    void fill_noCasesAtAll_authEndpointWithPathParam_securityNotBuiltBecauseIdUnknown() {
        Endpoint endpoint = endpoint("/orders/{id}", "GET", true, """
                {"parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer","format":"int64"}}]}""");
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of());

        CoverageFillResponse response = coverageGapFillService.fill(projectId, endpoint.getId(),
                new CoverageFillRequest(List.of("SECURITY_MISSING_TOKEN|||")));

        assertThat(response.createdCount()).isZero();
        assertThat(response.notFilled()).singleElement()
                .satisfies(nf -> assertThat(nf.reason()).isEqualTo(CoverageGapFillService.NO_BASELINE_REASON));
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

            @Override
            public com.aiapitesting.backend.entity.FailureCause getFailureCause() {
                return null;
            }

            @Override
            public Boolean getUnstable() {
                return null;
            }
        };
    }
}
