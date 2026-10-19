package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.response.CoverageDimensionResponse;
import com.aiapitesting.backend.dto.response.CoverageDimensionStatus;
import com.aiapitesting.backend.dto.response.CoverageRunStatus;
import com.aiapitesting.backend.dto.response.EndpointCoverageResponse;
import com.aiapitesting.backend.dto.response.ProjectCoverageResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.entity.TestCaseDependency;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndpointCoverageServiceTest {

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

    // Module 15j - bản thật (không mock): coverage chấm bằng CHÍNH checklist của planner, mock thì test vô nghĩa.
    @Spy
    private TestCaseChecklistPlanner testCaseChecklistPlanner = new TestCaseChecklistPlanner();

    @InjectMocks
    private EndpointCoverageService endpointCoverageService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = Project.builder().id(projectId).build();
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
    }

    // ---- Dựng dữ liệu ----

    /** Dựng Endpoint.schema đúng y hệt format EndpointImportService.buildSchemaJson() tạo ra - dùng
     * chính các lớp model swagger-parser để tránh đoán sai tên field JSON. */
    private String buildSchemaJson(RequestBody requestBody, List<String> statusCodes) {
        return buildSchemaJson(null, requestBody, statusCodes);
    }

    private String buildSchemaJson(List<Parameter> parameters, RequestBody requestBody, List<String> statusCodes) {
        Map<String, Object> trimmed = new LinkedHashMap<>();
        if (parameters != null) {
            trimmed.put("parameters", parameters);
        }
        if (requestBody != null) {
            trimmed.put("requestBody", requestBody);
        }
        if (statusCodes != null) {
            trimmed.put("responseStatusCodes", statusCodes);
        }
        try {
            return objectMapper.writeValueAsString(trimmed);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RequestBody jsonBody(Schema<?> schema) {
        MediaType mediaType = new MediaType().schema(schema);
        Content content = new Content().addMediaType("application/json", mediaType);
        return new RequestBody().content(content);
    }

    /** Body {email: string (required)} - spec nhỏ nhất có đủ mục Negative để kiểm. */
    private String emailRequiredSchema(List<String> statusCodes) {
        Schema<?> bodySchema = new Schema<>().type("object").required(List.of("email")).properties(
                Map.of("email", new Schema<>().type("string").format("email")));
        return buildSchemaJson(jsonBody(bodySchema), statusCodes);
    }

    private Endpoint endpoint(String path, String method, boolean requiresAuth, String schemaJson) {
        return Endpoint.builder()
                .id(UUID.randomUUID())
                .project(project)
                .path(path)
                .method(method)
                .requiresAuth(requiresAuth)
                .schema(schemaJson)
                .createdAt(Instant.now())
                .build();
    }

    private TestCase testCase(Endpoint endpoint, String name, Integer expectedStatus, String requestBody) {
        return testCase(endpoint, name, expectedStatus, requestBody, TestCaseAuthOverride.DEFAULT);
    }

    private TestCase testCase(Endpoint endpoint, String name, Integer expectedStatus, String requestBody, TestCaseAuthOverride authOverride) {
        return TestCase.builder()
                .id(UUID.randomUUID())
                .endpoint(endpoint)
                .name(name)
                .source(TestCaseSource.AI_GENERATED)
                .expectedStatus(expectedStatus)
                .requestBody(requestBody)
                .resolvedPath(endpoint.getPath())
                .authOverride(authOverride)
                .createdAt(Instant.now())
                .build();
    }

    /** Stub chung: project chỉ có các endpoint/test case/dependency truyền vào; kết quả chạy mặc định rỗng. */
    private void stubProject(List<Endpoint> endpoints, List<TestCase> testCases, List<TestCaseDependency> dependencies) {
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(endpoints);
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(testCases);
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(dependencies);
    }

    private EndpointCoverageResponse coverageOf(Endpoint endpoint) {
        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(Optional.of(endpoint));
        return endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());
    }

    private CoverageDimensionResponse dimension(EndpointCoverageResponse result, String checklistType, String labelPart) {
        return result.dimensions().stream()
                .filter(d -> checklistType.equals(d.checklistType()) && (labelPart == null || d.label().contains(labelPart)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Không có dòng " + checklistType + " chứa '" + labelPart + "'"));
    }

    private CoverageDimensionResponse dimensionByCategory(EndpointCoverageResponse result, String category, String labelPart) {
        return result.dimensions().stream()
                .filter(d -> category.equals(d.category()) && d.label().contains(labelPart))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Không có dòng " + category + " chứa '" + labelPart + "'"));
    }

    // ---- Đọc spec thật ----

    /**
     * Dùng CHÍNH schema_definition thật lấy từ DB Docker (dự án shop-api-target, endpoint
     * POST /api/products, verify thủ công ngày 2026-10-14) - bắt đúng bug thật đã gặp: swagger-parser
     * ghi thêm field "summary" vào JSON (qua EndpointImportService.buildSchemaJson()) mà fixture tự
     * dựng không có, nên UnrecognizedPropertyException của Jackson không lộ ra ở unit test với fixture
     * tự tạo - chỉ lộ khi chạy với dữ liệu thật qua Docker.
     */
    @Test
    void getEndpointCoverage_realSchemaFromProduction_checklistHasMissingFieldItemPerRequiredField() throws Exception {
        String schemaJson = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/test/resources/real-schema-post-products.json")));
        Endpoint endpoint = endpoint("/api/products", "POST", false, schemaJson);
        stubProject(List.of(endpoint), List.of(), List.of());

        EndpointCoverageResponse result = coverageOf(endpoint);

        assertThat(result.schemaReadable()).isTrue();
        assertThat(result.dimensions())
                .filteredOn(d -> "NEGATIVE_MISSING_FIELD".equals(d.checklistType()))
                .extracting(CoverageDimensionResponse::label)
                .hasSize(3)
                .anyMatch(l -> l.contains("'name'")).anyMatch(l -> l.contains("'price'")).anyMatch(l -> l.contains("'stock'"));
        assertThat(result.dimensions()).anyMatch(d -> d.category().equals("STATUS_CODE") && d.label().contains("201"));
        assertThat(result.testCaseCount()).isZero();
        assertThat(result.coveragePercent()).isZero();
    }

    // ---- Phạm vi đo (loại test case người dùng chọn) ----

    @Test
    void getEndpointCoverage_scopeOnlyPositive_dropsOtherCategoriesAndSpecRowsDoNotCountTowardPercent() {
        Endpoint endpoint = endpoint("/users", "POST", true, emailRequiredSchema(List.of("201", "400")));
        endpoint.setScopePositive(true);
        endpoint.setScopeNegative(false);
        endpoint.setScopeBoundary(false);
        endpoint.setScopeSecurity(false);
        TestCase positive = testCase(endpoint, "Tạo user hợp lệ", 201, "{\"email\":\"a@b.co\"}");
        stubProject(List.of(endpoint), List.of(positive), List.of());

        EndpointCoverageResponse result = coverageOf(endpoint);

        assertThat(result.scope().custom()).isTrue();
        assertThat(result.dimensions()).noneMatch(d -> List.of("NEGATIVE", "BOUNDARY", "SECURITY").contains(d.category()));
        assertThat(result.dimensions()).anyMatch(d -> d.category().equals("POSITIVE"));
        // Mã 400 của spec chưa có test nhưng không thuộc loại nào nên không kéo % xuống.
        assertThat(result.dimensions()).anyMatch(d -> d.category().equals("STATUS_CODE") && d.status() == CoverageDimensionStatus.MISSING);
        assertThat(result.coveragePercent()).isEqualTo(100.0);
    }

    @Test
    void getEndpointCoverage_neverChoseScope_treatedAsAllFourCategories() {
        Endpoint endpoint = endpoint("/users", "POST", true, emailRequiredSchema(List.of("201")));
        stubProject(List.of(endpoint), List.of(), List.of());

        EndpointCoverageResponse result = coverageOf(endpoint);

        assertThat(result.scope()).isEqualTo(com.aiapitesting.backend.dto.response.CoverageScope.FULL);
        assertThat(result.dimensions()).extracting(CoverageDimensionResponse::category)
                .contains("POSITIVE", "NEGATIVE", "SECURITY");
    }

    @Test
    void updateScope_noCategoryChosen_rejected() {
        Endpoint endpoint = endpoint("/users", "POST", true, emailRequiredSchema(List.of("201")));
        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(Optional.of(endpoint));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> endpointCoverageService.updateScope(projectId, endpoint.getId(),
                        new com.aiapitesting.backend.dto.request.UpdateCoverageScopeRequest(false, false, false, false)))
                .isInstanceOf(com.aiapitesting.backend.exception.InvalidRequestException.class);
    }

    @Test
    void updateScope_saved_andCoverageRecomputedWithNewScope() {
        Endpoint endpoint = endpoint("/users", "POST", true, emailRequiredSchema(List.of("201")));
        stubProject(List.of(endpoint), List.of(), List.of());
        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(Optional.of(endpoint));

        EndpointCoverageResponse result = endpointCoverageService.updateScope(projectId, endpoint.getId(),
                new com.aiapitesting.backend.dto.request.UpdateCoverageScopeRequest(true, true, false, false));

        assertThat(endpoint.getScopeNegative()).isTrue();
        assertThat(endpoint.getScopeSecurity()).isFalse();
        assertThat(result.dimensions()).noneMatch(d -> d.category().equals("SECURITY") || d.category().equals("BOUNDARY"));
        assertThat(result.dimensions()).anyMatch(d -> d.category().equals("NEGATIVE"));
    }

    // ---- Chấm mục checklist ----

    @Test
    void getEndpointCoverage_requiredFieldMissingFromNegativeCase_isCovered() {
        Endpoint endpoint = endpoint("/users", "POST", false, emailRequiredSchema(List.of("200", "400")));
        TestCase negativeMissingEmail = testCase(endpoint, "Negative - thieu email", 400, "{}");
        TestCase positive = testCase(endpoint, "Positive", 200, "{\"email\":\"a@b.com\"}");
        stubProject(List.of(endpoint), List.of(negativeMissingEmail, positive), List.of());

        EndpointCoverageResponse result = coverageOf(endpoint);

        assertThat(dimension(result, "NEGATIVE_MISSING_FIELD", "'email'").status()).isEqualTo(CoverageDimensionStatus.COVERED);
        assertThat(dimension(result, "POSITIVE_BASELINE", null).status()).isEqualTo(CoverageDimensionStatus.COVERED);
        assertThat(dimensionByCategory(result, "STATUS_CODE", "200").status()).isEqualTo(CoverageDimensionStatus.COVERED);
        assertThat(dimensionByCategory(result, "STATUS_CODE", "400").status()).isEqualTo(CoverageDimensionStatus.COVERED);
        assertThat(result.testCaseCount()).isEqualTo(2);
    }

    @Test
    void getEndpointCoverage_requiredFieldNeverOmitted_isMissingWithWhyAndFillableKey() {
        Endpoint endpoint = endpoint("/users", "POST", false, emailRequiredSchema(List.of("200")));
        TestCase onlyPositive = testCase(endpoint, "Positive", 200, "{\"email\":\"a@b.com\"}");
        stubProject(List.of(endpoint), List.of(onlyPositive), List.of());

        CoverageDimensionResponse missingEmail = dimension(coverageOf(endpoint), "NEGATIVE_MISSING_FIELD", "'email'");

        assertThat(missingEmail.status()).isEqualTo(CoverageDimensionStatus.MISSING);
        assertThat(missingEmail.detail()).isNotBlank();
        assertThat(missingEmail.whyNeeded()).contains("required");
        assertThat(missingEmail.itemKey()).isEqualTo("NEGATIVE_MISSING_FIELD|BODY|email|");
        assertThat(missingEmail.nearMisses()).isEmpty();
        assertThat(missingEmail.runStatus()).isNull();
    }

    /**
     * Module 15j - bằng chứng cho khoảng trống: case tên "thiếu email" nhưng thiếu luôn cả 'age' - API trả 400
     * không chứng minh được nó kiểm 'email' nên KHÔNG tính, và phải nói rõ vì sao (không chỉ báo "thiếu").
     */
    @Test
    void getEndpointCoverage_caseViolatingTwoRules_isNotCountedButReportedAsNearMissWithReason() {
        Schema<?> bodySchema = new Schema<>().type("object").required(List.of("email", "age")).properties(Map.of(
                "email", new Schema<>().type("string").format("email"),
                "age", new Schema<>().type("integer").minimum(BigDecimal.valueOf(18))));
        Endpoint endpoint = endpoint("/users", "POST", false, buildSchemaJson(jsonBody(bodySchema), List.of("201")));
        TestCase missingBoth = testCase(endpoint, "Negative - thieu email", 400, "{}");
        stubProject(List.of(endpoint), List.of(missingBoth), List.of());

        CoverageDimensionResponse missingEmail = dimension(coverageOf(endpoint), "NEGATIVE_MISSING_FIELD", "'email'");

        assertThat(missingEmail.status()).isEqualTo(CoverageDimensionStatus.MISSING);
        assertThat(missingEmail.nearMisses()).singleElement().satisfies(nm -> {
            assertThat(nm.testCaseId()).isEqualTo(missingBoth.getId());
            assertThat(nm.testCaseName()).isEqualTo("Negative - thieu email");
            assertThat(nm.reason()).contains("'age'").contains("1 lỗi");
        });
        assertThat(missingEmail.detail()).contains("gần đạt");
    }

    @Test
    void getEndpointCoverage_boundaryMiddleValue_isMissingAndNotANearMiss() {
        Schema<?> quantitySchema = new Schema<>().type("integer").minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(100));
        Endpoint endpoint = endpoint("/orders", "POST", false,
                buildSchemaJson(jsonBody(new Schema<>().type("object").properties(Map.of("quantity", quantitySchema))), List.of("200")));
        TestCase middleValue = testCase(endpoint, "Positive", 200, "{\"quantity\":50}");
        stubProject(List.of(endpoint), List.of(middleValue), List.of());

        CoverageDimensionResponse atMinimum = dimension(coverageOf(endpoint), "BOUNDARY_AT_MINIMUM", "'quantity'");

        // quantity=50 giữa khoảng 1-100 không phải test biên, cũng không "gần đạt" mục biên nào.
        assertThat(atMinimum.status()).isEqualTo(CoverageDimensionStatus.MISSING);
        assertThat(atMinimum.nearMisses()).isEmpty();
    }

    @Test
    void getEndpointCoverage_boundaryAtMinimumAndAboveMaximum_areCoveredWithProvenance() {
        Schema<?> quantitySchema = new Schema<>().type("integer").minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(100));
        Endpoint endpoint = endpoint("/orders", "POST", false,
                buildSchemaJson(jsonBody(new Schema<>().type("object").properties(Map.of("quantity", quantitySchema))), List.of("200")));
        TestCase atMin = testCase(endpoint, "Boundary - quantity = 1", 200, "{\"quantity\":1}");
        TestCase aboveMax = testCase(endpoint, "Boundary - quantity qua lon", 400, "{\"quantity\":999}");
        stubProject(List.of(endpoint), List.of(atMin, aboveMax), List.of());

        EndpointCoverageResponse result = coverageOf(endpoint);

        CoverageDimensionResponse atMinimum = dimension(result, "BOUNDARY_AT_MINIMUM", "'quantity'");
        assertThat(atMinimum.status()).isEqualTo(CoverageDimensionStatus.COVERED);
        assertThat(atMinimum.coveringTestCases()).containsExactly(
                new CoverageDimensionResponse.TestCaseRef(atMin.getId(), atMin.getName(), null));
        assertThat(dimension(result, "BOUNDARY_ABOVE_MAXIMUM", "'quantity'").status()).isEqualTo(CoverageDimensionStatus.COVERED);
    }

    /**
     * Fix bug thực tế 15g/15h (giữ làm test hồi quy): biên của tham số QUERY - giá trị ở pathParamFallbacks
     * hoặc ghi thẳng trong resolvedPath đều phải đọc được, không chỉ requestBody.
     */
    @Test
    void getEndpointCoverage_boundaryOnQueryParam_readsFallbacksAndLiteralUrlValues() {
        Schema<?> quantitySchema = new Schema<>().type("integer").minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(100));
        Parameter quantityParam = new Parameter().name("quantity").in("query").required(true).schema(quantitySchema);
        Endpoint endpoint = endpoint("/stock", "PATCH", false, buildSchemaJson(List.of(quantityParam), null, List.of("200")));
        TestCase atMinViaFallback = TestCase.builder()
                .id(UUID.randomUUID()).endpoint(endpoint).name("Boundary - quantity = 1")
                .source(TestCaseSource.AI_GENERATED).expectedStatus(200)
                .resolvedPath("/stock?quantity={{quantity}}").pathParamFallbacks("{\"quantity\":\"1\"}")
                .authOverride(TestCaseAuthOverride.DEFAULT).createdAt(Instant.now()).build();
        TestCase belowMinInUrl = TestCase.builder()
                .id(UUID.randomUUID()).endpoint(endpoint).name("Boundary - quantity = 0")
                .source(TestCaseSource.AI_GENERATED).expectedStatus(400)
                .resolvedPath("/stock?quantity=0")
                .authOverride(TestCaseAuthOverride.DEFAULT).createdAt(Instant.now()).build();
        stubProject(List.of(endpoint), List.of(atMinViaFallback, belowMinInUrl), List.of());

        EndpointCoverageResponse result = coverageOf(endpoint);

        assertThat(dimension(result, "BOUNDARY_AT_MINIMUM", "'quantity'").status()).isEqualTo(CoverageDimensionStatus.COVERED);
        assertThat(dimension(result, "BOUNDARY_BELOW_MINIMUM", "'quantity'").status()).isEqualTo(CoverageDimensionStatus.COVERED);
    }

    /**
     * Module 15j - tính chất cốt lõi: coverage và checklist lúc sinh là CÙNG 1 thước đo. Dựng đủ mọi case
     * checklist (bằng chính planner.fill) → mọi dòng checklist của Coverage phải COVERED, và số dòng
     * checklist = đúng số mục planner lập (không thêm, không bớt).
     */
    @Test
    void getEndpointCoverage_checklistRowsAreExactlyPlannerItems_andFullChecklistIsFullyCovered() {
        String schemaJson = """
                {"parameters":[{"name":"minPrice","in":"query","schema":{"type":"integer","minimum":0}}],
                 "requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["email","age"],
                   "properties":{"email":{"type":"string","format":"email"},
                                 "age":{"type":"integer","minimum":18,"maximum":60},
                                 "name":{"type":"string","minLength":2,"maxLength":5},
                                 "role":{"type":"string","enum":["USER","ADMIN"]}}}}}}}""";
        Endpoint endpoint = endpoint("/users", "POST", false, schemaJson);
        TestCaseChecklistPlanner planner = new TestCaseChecklistPlanner();
        TestCaseChecklistPlanner.Spec spec = planner.readSpec(endpoint);
        GeneratedTestCase baseline = new GeneratedTestCase(
                "Positive", "d", Map.of(), "{\"email\":\"an@gmail.com\",\"age\":25,\"name\":\"abc\",\"role\":\"USER\"}", 201,
                "/users?minPrice={{minPrice}}", Map.of("minPrice", "10"), null, null);
        List<TestCaseChecklistPlanner.ChecklistItem> items = planner.planAll(spec, false);

        List<TestCase> cases = new ArrayList<>();
        for (TestCaseChecklistPlanner.ChecklistItem item : items) {
            if (item.notApplicableReason() != null) {
                continue;
            }
            GeneratedTestCase g = item.location() == null ? baseline : planner.fill(spec, item, baseline);
            assertThat(g).as(item.description()).isNotNull();
            cases.add(TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).name(g.name())
                    .source(TestCaseSource.AI_GENERATED).expectedStatus(g.expectedStatus())
                    .requestBody(g.requestBody()).resolvedPath(g.resolvedPath())
                    .pathParamFallbacks(toJson(g.pathParamFallbacks()))
                    .authOverride(TestCaseAuthOverride.DEFAULT).createdAt(Instant.now()).build());
        }
        stubProject(List.of(endpoint), cases, List.of());

        EndpointCoverageResponse result = coverageOf(endpoint);

        List<CoverageDimensionResponse> checklistRows = result.dimensions().stream().filter(d -> d.checklistType() != null).toList();
        assertThat(checklistRows).hasSize(items.size());
        assertThat(checklistRows)
                .filteredOn(d -> d.status() != CoverageDimensionStatus.NOT_APPLICABLE)
                .isNotEmpty()
                .allMatch(d -> d.status() == CoverageDimensionStatus.COVERED);
    }

    // ---- Xác thực ----

    @Test
    void getEndpointCoverage_authRequired_missingTokenCaseCoversSecurityItem_403AlwaysNotApplicable() {
        Endpoint endpoint = endpoint("/orders", "GET", true, buildSchemaJson(null, List.of("200", "401")));
        TestCase noToken = testCase(endpoint, "Security - thieu token", 401, null, TestCaseAuthOverride.NONE);
        stubProject(List.of(endpoint), List.of(noToken), List.of());

        EndpointCoverageResponse result = coverageOf(endpoint);

        assertThat(dimension(result, "SECURITY_MISSING_TOKEN", null).status()).isEqualTo(CoverageDimensionStatus.COVERED);
        assertThat(dimension(result, "SECURITY_INVALID_TOKEN", null).status()).isEqualTo(CoverageDimensionStatus.MISSING);
        CoverageDimensionResponse auth403 = dimensionByCategory(result, "AUTH", "403");
        assertThat(auth403.status()).isEqualTo(CoverageDimensionStatus.NOT_APPLICABLE);
        assertThat(auth403.detail()).contains("vai trò");
    }

    @Test
    void getEndpointCoverage_authNotRequired_securityItemsNotApplicable_simpleGetFullyCovered() {
        Endpoint endpoint = endpoint("/health", "GET", false, buildSchemaJson(null, List.of("200")));
        TestCase positive = testCase(endpoint, "Positive", 200, null);
        stubProject(List.of(endpoint), List.of(positive), List.of());

        EndpointCoverageResponse result = coverageOf(endpoint);

        assertThat(result.dimensions())
                .filteredOn(d -> d.category().equals("SECURITY") || d.category().equals("AUTH"))
                .allMatch(d -> d.status() == CoverageDimensionStatus.NOT_APPLICABLE);
        // Còn đúng 2 dòng liên quan: dữ liệu hợp lệ + mã 200, cả 2 đã có case khớp.
        assertThat(result.coveragePercent()).isEqualTo(100.0);
    }

    /**
     * Module 15j - "mỗi case chỉ 1 lỗi" áp cho cả cách gọi: endpoint yêu cầu xác thực mà case cố ý bỏ token
     * thì API trả 401 bất kể body - case đó không được tính cho mục thiếu field, cũng không làm bằng chứng.
     */
    @Test
    void getEndpointCoverage_caseWithoutTokenOnAuthEndpoint_countsOnlyForSecurityItem() {
        Endpoint endpoint = endpoint("/users", "POST", true, emailRequiredSchema(List.of("201")));
        TestCase noTokenNoEmail = testCase(endpoint, "Security - thieu token", 401, "{}", TestCaseAuthOverride.NONE);
        stubProject(List.of(endpoint), List.of(noTokenNoEmail), List.of());

        EndpointCoverageResponse result = coverageOf(endpoint);

        CoverageDimensionResponse missingEmail = dimension(result, "NEGATIVE_MISSING_FIELD", "'email'");
        assertThat(missingEmail.status()).isEqualTo(CoverageDimensionStatus.MISSING);
        assertThat(missingEmail.nearMisses()).isEmpty();
        assertThat(dimension(result, "SECURITY_MISSING_TOKEN", null).status()).isEqualTo(CoverageDimensionStatus.COVERED);
    }

    // ---- Cờ "bù được ngay bây giờ" (nút Bổ sung) ----

    /** email + age đều bắt buộc: chưa có test nào thì không có dữ liệu hợp lệ để mượn - mọi mục thiếu đều không bù được. */
    private String twoRequiredFieldsSchema() {
        Schema<?> bodySchema = new Schema<>().type("object").required(List.of("email", "age")).properties(Map.of(
                "email", new Schema<>().type("string").format("email"),
                "age", new Schema<>().type("integer").format("int32")));
        return buildSchemaJson(jsonBody(bodySchema), List.of("201"));
    }

    @Test
    void getEndpointCoverage_noCases_otherRequiredInputs_nothingFillableAndReasonGiven() {
        Endpoint endpoint = endpoint("/users", "POST", false, twoRequiredFieldsSchema());
        stubProject(List.of(endpoint), List.of(), List.of());

        EndpointCoverageResponse result = coverageOf(endpoint);

        assertThat(result.dimensions()).filteredOn(d -> d.itemKey() != null && d.status() == CoverageDimensionStatus.MISSING)
                .isNotEmpty()
                .allSatisfy(d -> {
                    assertThat(d.fillable()).isFalse();
                    assertThat(d.fillBlockedReason()).isEqualTo(TestCaseChecklistPlanner.NO_BASELINE_REASON);
                });
    }

    @Test
    void getEndpointCoverage_withValidBaseline_missingItemsBecomeFillable() {
        Endpoint endpoint = endpoint("/users", "POST", false, twoRequiredFieldsSchema());
        TestCase positive = testCase(endpoint, "Positive", 201, "{\"email\":\"a@b.com\",\"age\":25}");
        stubProject(List.of(endpoint), List.of(positive), List.of());

        EndpointCoverageResponse result = coverageOf(endpoint);

        CoverageDimensionResponse missingEmail = dimension(result, "NEGATIVE_MISSING_FIELD", "'email'");
        assertThat(missingEmail.status()).isEqualTo(CoverageDimensionStatus.MISSING);
        assertThat(missingEmail.fillable()).isTrue();
        assertThat(missingEmail.fillBlockedReason()).isNull();
        // Đã đạt thì không có gì để bù: không gắn cờ, không có lý do.
        CoverageDimensionResponse baselineRow = dimension(result, "POSITIVE_BASELINE", null);
        assertThat(baselineRow.status()).isEqualTo(CoverageDimensionStatus.COVERED);
        assertThat(baselineRow.fillable()).isFalse();
        assertThat(baselineRow.fillBlockedReason()).isNull();
    }

    /** Endpoint chỉ có tham số path id, chưa có test nào: biên của id dựng được từ đặc tả; Positive thì cần id thật. */
    @Test
    void getEndpointCoverage_noCases_pathIdOnlyEndpoint_boundaryFillableFromSpecPositiveNot() {
        Endpoint endpoint = endpoint("/products/{id}", "DELETE", false, buildSchemaJson(
                List.of(new Parameter().name("id").in("path").required(true)
                        .schema(new Schema<>().type("integer").format("int64"))), null, List.of("204", "404")));
        stubProject(List.of(endpoint), List.of(), List.of());

        EndpointCoverageResponse result = coverageOf(endpoint);

        assertThat(result.testCaseCount()).isZero();
        assertThat(result.dimensions()).filteredOn(d -> d.category().equals("BOUNDARY"))
                .hasSize(5)
                .allSatisfy(d -> {
                    assertThat(d.status()).isEqualTo(CoverageDimensionStatus.MISSING);
                    assertThat(d.fillable()).isTrue();
                });
        CoverageDimensionResponse positive = dimension(result, "POSITIVE_BASELINE", null);
        assertThat(positive.fillable()).isFalse();
        assertThat(positive.fillBlockedReason()).isEqualTo(TestCaseChecklistPlanner.NO_BASELINE_REASON);
        // Mã trạng thái / quan hệ endpoint không có cách bù tự động: không gắn cờ.
        assertThat(dimensionByCategory(result, "STATUS_CODE", "204").fillable()).isFalse();
    }

    // ---- Trạng thái chạy ----

    @Test
    void getEndpointCoverage_runStatus_reflectsLatestResultOfCoveringCases() {
        Endpoint endpoint = endpoint("/users", "POST", false, emailRequiredSchema(List.of("201", "400")));
        TestCase positive = testCase(endpoint, "Positive", 201, "{\"email\":\"a@b.com\"}");
        TestCase missingEmail = testCase(endpoint, "Negative - thieu email", 400, "{}");
        stubProject(List.of(endpoint), List.of(positive, missingEmail), List.of());
        when(testResultRepository.findLatestResultsByProject(project)).thenReturn(List.of(
                latest(positive.getId(), TestResultStatus.PASSED),
                latest(missingEmail.getId(), TestResultStatus.FAILED)));

        EndpointCoverageResponse result = coverageOf(endpoint);

        CoverageDimensionResponse baselineRow = dimension(result, "POSITIVE_BASELINE", null);
        assertThat(baselineRow.runStatus()).isEqualTo(CoverageRunStatus.PASSED);
        assertThat(baselineRow.coveringTestCases()).singleElement()
                .satisfies(ref -> assertThat(ref.latestResult()).isEqualTo(TestResultStatus.PASSED));
        // Đã chạy và API lệch kỳ vọng - vẫn tính "đã kiểm", nhưng báo FAILED để không bị che.
        assertThat(dimension(result, "NEGATIVE_MISSING_FIELD", "'email'").runStatus()).isEqualTo(CoverageRunStatus.FAILED);
        assertThat(result.executedPercent()).isLessThanOrEqualTo(result.coveragePercent()).isPositive();
    }

    @Test
    void getEndpointCoverage_coveredButNeverRunOrOnlyErrored_isNotCountedAsExecuted() {
        Endpoint endpoint = endpoint("/users", "POST", false, emailRequiredSchema(List.of("201")));
        TestCase positive = testCase(endpoint, "Positive", 201, "{\"email\":\"a@b.com\"}");
        TestCase missingEmail = testCase(endpoint, "Negative - thieu email", 400, "{}");
        stubProject(List.of(endpoint), List.of(positive, missingEmail), List.of());
        when(testResultRepository.findLatestResultsByProject(project)).thenReturn(List.of(
                latest(positive.getId(), TestResultStatus.ERROR)));

        EndpointCoverageResponse result = coverageOf(endpoint);

        assertThat(dimension(result, "POSITIVE_BASELINE", null).runStatus()).isEqualTo(CoverageRunStatus.NO_VERDICT);
        assertThat(dimension(result, "NEGATIVE_MISSING_FIELD", "'email'").runStatus()).isEqualTo(CoverageRunStatus.NOT_RUN);
        assertThat(result.coveragePercent()).isPositive();
        assertThat(result.executedPercent()).isZero();
    }

    // ---- Module 16 - fail do môi trường/token không được tính là "API lệch kỳ vọng" ----

    @Test
    void getEndpointCoverage_latestFailCausedByEnvironment_isNoVerdictNotFailed() {
        Endpoint endpoint = endpoint("/users", "POST", false, emailRequiredSchema(List.of("201")));
        TestCase positive = testCase(endpoint, "Positive", 201, "{\"email\":\"a@b.com\"}");
        stubProject(List.of(endpoint), List.of(positive), List.of());
        when(testResultRepository.findLatestResultsByProject(project)).thenReturn(List.of(
                latest(positive.getId(), TestResultStatus.FAILED, FailureCause.AUTH_CONFIG)));

        CoverageDimensionResponse row = dimension(coverageOf(endpoint), "POSITIVE_BASELINE", null);

        assertThat(row.status()).isEqualTo(CoverageDimensionStatus.COVERED);
        assertThat(row.runStatus()).isEqualTo(CoverageRunStatus.NO_VERDICT);
        // Chi tiết kết quả (Fail) vẫn hiện nguyên - chỉ KHÔNG gộp thành "đã kiểm và thấy API lệch".
        assertThat(row.coveringTestCases()).singleElement()
                .satisfies(ref -> assertThat(ref.latestResult()).isEqualTo(TestResultStatus.FAILED));
    }

    @Test
    void getEndpointCoverage_latestFailCausedByStableFailure_stillFailed() {
        Endpoint endpoint = endpoint("/users", "POST", false, emailRequiredSchema(List.of("201")));
        TestCase positive = testCase(endpoint, "Positive", 201, "{\"email\":\"a@b.com\"}");
        stubProject(List.of(endpoint), List.of(positive), List.of());
        when(testResultRepository.findLatestResultsByProject(project)).thenReturn(List.of(
                latest(positive.getId(), TestResultStatus.FAILED, FailureCause.STABLE_FAILURE)));

        assertThat(dimension(coverageOf(endpoint), "POSITIVE_BASELINE", null).runStatus()).isEqualTo(CoverageRunStatus.FAILED);
    }

    // ---- Module 16 gđ2 - kết quả của test không ổn định không phải bằng chứng chắc chắn ----

    @Test
    void getEndpointCoverage_latestPassOfAnUnstableTest_isNoVerdictNotPassed() {
        Endpoint endpoint = endpoint("/users", "POST", false, emailRequiredSchema(List.of("201")));
        TestCase positive = testCase(endpoint, "Positive", 201, "{\"email\":\"a@b.com\"}");
        stubProject(List.of(endpoint), List.of(positive), List.of());
        when(testResultRepository.findLatestResultsByProject(project)).thenReturn(List.of(
                latest(positive.getId(), TestResultStatus.PASSED, null, true)));

        assertThat(dimension(coverageOf(endpoint), "POSITIVE_BASELINE", null).runStatus()).isEqualTo(CoverageRunStatus.NO_VERDICT);
    }

    @Test
    void getEndpointCoverage_latestFailOfAnUnstableTest_isNoVerdictNotFailed() {
        Endpoint endpoint = endpoint("/users", "POST", false, emailRequiredSchema(List.of("201")));
        TestCase positive = testCase(endpoint, "Positive", 201, "{\"email\":\"a@b.com\"}");
        stubProject(List.of(endpoint), List.of(positive), List.of());
        when(testResultRepository.findLatestResultsByProject(project)).thenReturn(List.of(
                latest(positive.getId(), TestResultStatus.FAILED, FailureCause.FLAKY, true)));

        assertThat(dimension(coverageOf(endpoint), "POSITIVE_BASELINE", null).runStatus()).isEqualTo(CoverageRunStatus.NO_VERDICT);
    }

    @Test
    void getEndpointCoverage_latestPassOfAStableTest_staysPassed() {
        Endpoint endpoint = endpoint("/users", "POST", false, emailRequiredSchema(List.of("201")));
        TestCase positive = testCase(endpoint, "Positive", 201, "{\"email\":\"a@b.com\"}");
        stubProject(List.of(endpoint), List.of(positive), List.of());
        when(testResultRepository.findLatestResultsByProject(project)).thenReturn(List.of(
                latest(positive.getId(), TestResultStatus.PASSED, null, false)));

        assertThat(dimension(coverageOf(endpoint), "POSITIVE_BASELINE", null).runStatus()).isEqualTo(CoverageRunStatus.PASSED);
    }

    // ---- Mã trạng thái ----

    @Test
    void getEndpointCoverage_statusCodeRangeAndDefault_rangeMatchesFamily_defaultNotApplicable() {
        Endpoint endpoint = endpoint("/health", "GET", false, buildSchemaJson(null, List.of("200", "4XX", "default")));
        TestCase notFound = testCase(endpoint, "Negative - khong ton tai", 404, null);
        stubProject(List.of(endpoint), List.of(notFound), List.of());

        EndpointCoverageResponse result = coverageOf(endpoint);

        assertThat(dimensionByCategory(result, "STATUS_CODE", "4XX").status()).isEqualTo(CoverageDimensionStatus.COVERED);
        assertThat(dimensionByCategory(result, "STATUS_CODE", "default").status()).isEqualTo(CoverageDimensionStatus.NOT_APPLICABLE);
        assertThat(dimensionByCategory(result, "STATUS_CODE", "200").status()).isEqualTo(CoverageDimensionStatus.MISSING);
    }

    // ---- Quan hệ giữa endpoint ----

    @Test
    void getEndpointCoverage_relation_coveredOnlyWhenACaseChainsIdFromProducerPost() {
        Endpoint create = endpoint("/products", "POST", false, buildSchemaJson(null, List.of("201")));
        Endpoint getOne = endpoint("/products/{id}", "GET", false, buildSchemaJson(null, List.of("200")));
        TestCase createCase = testCase(create, "Positive - tao san pham", 201, "{}");
        TestCase getChained = testCase(getOne, "Positive - lay san pham vua tao", 200, null);
        TestCaseDependency chain = TestCaseDependency.builder()
                .id(UUID.randomUUID()).testCase(getChained).dependsOnTestCase(createCase)
                .jsonPath("$.id").placeholderName("id").createdAt(Instant.now()).build();
        stubProject(List.of(create, getOne), List.of(createCase, getChained), List.of(chain));

        CoverageDimensionResponse relation = dimensionByCategory(coverageOf(getOne), "RELATION", "'id'");

        assertThat(relation.status()).isEqualTo(CoverageDimensionStatus.COVERED);
        assertThat(relation.label()).contains("POST /products");
        assertThat(relation.coveringTestCases()).extracting(CoverageDimensionResponse.TestCaseRef::id)
                .containsExactly(getChained.getId());
        assertThat(relation.itemKey()).isNull();
    }

    @Test
    void getEndpointCoverage_relation_missingWithoutChain_absentWhenNoProducerPost() {
        Endpoint create = endpoint("/products", "POST", false, buildSchemaJson(null, List.of("201")));
        Endpoint getOne = endpoint("/products/{id}", "GET", false, buildSchemaJson(null, List.of("200")));
        Endpoint orphan = endpoint("/users/{id}", "GET", false, buildSchemaJson(null, List.of("200")));
        TestCase guessedId = testCase(getOne, "Positive - id doan san", 200, null);
        stubProject(List.of(create, getOne, orphan), List.of(guessedId), List.of());
        when(endpointRepository.findByIdAndProject(orphan.getId(), project)).thenReturn(Optional.of(orphan));

        CoverageDimensionResponse relation = dimensionByCategory(coverageOf(getOne), "RELATION", "'id'");
        assertThat(relation.status()).isEqualTo(CoverageDimensionStatus.MISSING);
        assertThat(relation.whyNeeded()).contains("Test Data Chaining");

        EndpointCoverageResponse orphanResult = endpointCoverageService.getEndpointCoverage(projectId, orphan.getId());
        assertThat(orphanResult.dimensions()).noneMatch(d -> d.category().equals("RELATION"));
    }

    // ---- Tổng hợp project ----

    @Test
    void getProjectCoverage_noRunYet_executedPercentIsZero() {
        Endpoint covered = endpoint("/products", "GET", false, buildSchemaJson(null, List.of("200")));
        Endpoint uncovered = endpoint("/payment", "DELETE", true, buildSchemaJson(null, List.of("204")));
        TestCase coveredCase = testCase(covered, "Positive", 200, null);
        stubProject(List.of(covered, uncovered), List.of(coveredCase), List.of());

        ProjectCoverageResponse result = endpointCoverageService.getProjectCoverage(projectId);

        // Chưa có lượt chạy nào: có test nhưng chưa kiểm thật.
        assertThat(result.overallExecutedPercent()).isZero();
    }

    @Test
    void getEndpointCoverage_unparseableSchema_marksSchemaUnreadableInsteadOfVacuous100Percent() {
        // Module 15f - không đọc được schema thì phải báo rõ, không ngầm hiểu "không có gì cần kiểm".
        Endpoint endpoint = endpoint("/broken", "POST", false, "{ khong phai json hop le");
        stubProject(List.of(endpoint), List.of(), List.of());

        assertThat(coverageOf(endpoint).schemaReadable()).isFalse();
    }

    @Test
    void getEndpointCoverage_validSchemaWithNoConstraints_schemaReadableTrue() {
        Endpoint endpoint = endpoint("/health", "GET", false, buildSchemaJson(null, List.of("200")));
        stubProject(List.of(endpoint), List.of(), List.of());

        assertThat(coverageOf(endpoint).schemaReadable()).isTrue();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private TestResultRepository.TestCaseLatestResult latest(UUID testCaseId, TestResultStatus status) {
        return latest(testCaseId, status, null);
    }

    private TestResultRepository.TestCaseLatestResult latest(UUID testCaseId, TestResultStatus status, FailureCause cause) {
        return latest(testCaseId, status, cause, null);
    }

    private TestResultRepository.TestCaseLatestResult latest(UUID testCaseId, TestResultStatus status, FailureCause cause, Boolean unstable) {
        return new TestResultRepository.TestCaseLatestResult() {
            @Override
            public Boolean getUnstable() {
                return unstable;
            }

            @Override
            public UUID getTestCaseId() {
                return testCaseId;
            }

            @Override
            public TestResultStatus getStatus() {
                return status;
            }

            @Override
            public FailureCause getFailureCause() {
                return cause;
            }
        };
    }
}
