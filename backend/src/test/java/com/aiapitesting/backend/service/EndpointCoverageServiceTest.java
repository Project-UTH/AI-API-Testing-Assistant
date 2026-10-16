package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.CoverageDimensionResponse;
import com.aiapitesting.backend.dto.response.CoverageDimensionStatus;
import com.aiapitesting.backend.dto.response.EndpointCoverageResponse;
import com.aiapitesting.backend.dto.response.ProjectCoverageResponse;
import com.aiapitesting.backend.dto.response.RiskLevel;
import com.aiapitesting.backend.entity.Endpoint;
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
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Dựng Endpoint.schema đúng y hệt format EndpointImportService.buildSchemaJson() tạo ra - dùng
     * chính các lớp model swagger-parser để tránh đoán sai tên field JSON. */
    private String buildSchemaJson(RequestBody requestBody, List<String> statusCodes) {
        Map<String, Object> trimmed = new LinkedHashMap<>();
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

    private TestCase testCase(Endpoint endpoint, TestCaseSource source, Integer expectedStatus, String requestBody) {
        return testCase(endpoint, source, expectedStatus, requestBody, TestCaseAuthOverride.DEFAULT);
    }

    private TestCase testCase(Endpoint endpoint, TestCaseSource source, Integer expectedStatus, String requestBody, TestCaseAuthOverride authOverride) {
        return TestCase.builder()
                .id(UUID.randomUUID())
                .endpoint(endpoint)
                .name("tc")
                .source(source)
                .expectedStatus(expectedStatus)
                .requestBody(requestBody)
                .authOverride(authOverride)
                .createdAt(Instant.now())
                .build();
    }

    /**
     * Dùng CHÍNH schema_definition thật lấy từ DB Docker (dự án shop-api-target, endpoint
     * POST /api/products, verify thủ công ngày 2026-10-14) - bắt đúng bug thật đã gặp: swagger-parser
     * ghi thêm field "summary" vào JSON (qua EndpointImportService.buildSchemaJson()) mà fixture tự
     * dựng ở các test khác (buildSchemaJson() helper trong chính file test này) không có, nên
     * UnrecognizedPropertyException của Jackson (FAIL_ON_UNKNOWN_PROPERTIES mặc định bật) không lộ ra
     * ở unit test với fixture tự tạo - chỉ lộ khi chạy với dữ liệu thật qua Docker.
     */
    @Test
    void getEndpointCoverage_realSchemaFromProduction_extractsRequiredFieldsAndStatusCode() throws Exception {
        String schemaJson = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/test/resources/real-schema-post-products.json")));
        Endpoint endpoint = endpoint("/api/products", "POST", false, schemaJson);

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of());
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        List<String> requiredFieldLabels = result.dimensions().stream()
                .filter(d -> d.category().equals("REQUIRED_FIELD")).map(CoverageDimensionResponse::label).toList();
        assertThat(requiredFieldLabels).hasSize(3);
        assertThat(requiredFieldLabels.toString()).contains("name").contains("price").contains("stock");

        assertThat(result.dimensions().stream().anyMatch(d -> d.category().equals("STATUS_CODE") && d.label().contains("201"))).isTrue();
    }

    @Test
    void getEndpointCoverage_requiredFieldMissingFromNegativeCase_isCovered() {
        Schema<?> bodySchema = new Schema<>().required(List.of("email")).properties(
                Map.of("email", new Schema<>().type("string")));
        String schemaJson = buildSchemaJson(jsonBody(bodySchema), List.of("200", "400"));
        Endpoint endpoint = endpoint("/users", "POST", false, schemaJson);

        TestCase negativeMissingEmail = testCase(endpoint, TestCaseSource.AI_GENERATED, 400, "{}");
        TestCase positive = testCase(endpoint, TestCaseSource.AI_GENERATED, 200, "{\"email\":\"a@b.com\"}");

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(negativeMissingEmail, positive));
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        CoverageDimensionResponse requiredFieldDim = result.dimensions().stream()
                .filter(d -> d.category().equals("REQUIRED_FIELD")).findFirst().orElseThrow();
        assertThat(requiredFieldDim.status()).isEqualTo(CoverageDimensionStatus.COVERED);

        CoverageDimensionResponse status200 = result.dimensions().stream()
                .filter(d -> d.category().equals("STATUS_CODE") && d.label().contains("200")).findFirst().orElseThrow();
        assertThat(status200.status()).isEqualTo(CoverageDimensionStatus.COVERED);
        CoverageDimensionResponse status400 = result.dimensions().stream()
                .filter(d -> d.category().equals("STATUS_CODE") && d.label().contains("400")).findFirst().orElseThrow();
        assertThat(status400.status()).isEqualTo(CoverageDimensionStatus.COVERED);
    }

    @Test
    void getEndpointCoverage_requiredFieldNeverOmitted_isMissing() {
        Schema<?> bodySchema = new Schema<>().required(List.of("email")).properties(
                Map.of("email", new Schema<>().type("string")));
        String schemaJson = buildSchemaJson(jsonBody(bodySchema), List.of("200"));
        Endpoint endpoint = endpoint("/users", "POST", false, schemaJson);

        TestCase onlyPositive = testCase(endpoint, TestCaseSource.AI_GENERATED, 200, "{\"email\":\"a@b.com\"}");

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(onlyPositive));
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        CoverageDimensionResponse requiredFieldDim = result.dimensions().stream()
                .filter(d -> d.category().equals("REQUIRED_FIELD")).findFirst().orElseThrow();
        assertThat(requiredFieldDim.status()).isEqualTo(CoverageDimensionStatus.MISSING);
        assertThat(requiredFieldDim.detail()).isNotBlank();
    }

    @Test
    void getEndpointCoverage_authRequired401Covered_403AlwaysNotApplicable() {
        String schemaJson = buildSchemaJson(null, List.of("200", "401"));
        Endpoint endpoint = endpoint("/orders", "GET", true, schemaJson);
        TestCase securityCase = testCase(endpoint, TestCaseSource.SECURITY, 401, null, TestCaseAuthOverride.NONE);

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(securityCase));
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        CoverageDimensionResponse auth401 = result.dimensions().stream()
                .filter(d -> d.label().contains("401")).findFirst().orElseThrow();
        assertThat(auth401.status()).isEqualTo(CoverageDimensionStatus.COVERED);

        CoverageDimensionResponse auth403 = result.dimensions().stream()
                .filter(d -> d.label().contains("403")).findFirst().orElseThrow();
        assertThat(auth403.status()).isEqualTo(CoverageDimensionStatus.NOT_APPLICABLE);
        assertThat(auth403.detail()).contains("vai trò");
    }

    @Test
    void getEndpointCoverage_authNotRequired_401And403BothNotApplicable() {
        String schemaJson = buildSchemaJson(null, List.of("200"));
        Endpoint endpoint = endpoint("/health", "GET", false, schemaJson);

        TestCase positive = testCase(endpoint, TestCaseSource.AI_GENERATED, 200, null);

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(positive));
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        assertThat(result.dimensions().stream().filter(d -> d.category().equals("AUTH")))
                .allMatch(d -> d.status() == CoverageDimensionStatus.NOT_APPLICABLE);
        // Dimension duy nhất còn lại là STATUS_CODE "200", đã có case khớp -> 100% (không phải
        // vacuous - khai báo 1 status code và có đúng 1 case khớp status đó).
        assertThat(result.coveragePercent()).isEqualTo(100.0);
    }

    @Test
    void getEndpointCoverage_deleteMethodWithAuthAndDependentsAndFailHistory_scoresHigherRisk() {
        String schemaJson = buildSchemaJson(null, List.of("204"));
        Endpoint deleteEndpoint = endpoint("/users/{id}", "DELETE", true, schemaJson);
        Endpoint dependentEndpoint = endpoint("/orders", "POST", false, buildSchemaJson(null, List.of("201")));

        TestCase sourceCase = testCase(deleteEndpoint, TestCaseSource.AI_GENERATED, 204, null);
        TestCase consumerCase = testCase(dependentEndpoint, TestCaseSource.AI_GENERATED, 201, null);
        TestCaseDependency dependency = TestCaseDependency.builder()
                .id(UUID.randomUUID()).testCase(consumerCase).dependsOnTestCase(sourceCase)
                .jsonPath("$.id").placeholderName("userId").createdAt(Instant.now()).build();

        TestResultRepository.EndpointStatusCount failedRow = endpointStatusCount(deleteEndpoint.getId(), TestResultStatus.FAILED, 4);
        TestResultRepository.EndpointStatusCount passedRow = endpointStatusCount(deleteEndpoint.getId(), TestResultStatus.PASSED, 6);

        when(endpointRepository.findByIdAndProject(deleteEndpoint.getId(), project)).thenReturn(java.util.Optional.of(deleteEndpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(deleteEndpoint, dependentEndpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(sourceCase, consumerCase));
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of(dependency));
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of(failedRow, passedRow));

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, deleteEndpoint.getId());

        // DELETE(+20) + auth(+20) + 1 dependent(+10) + fail rate 40%(+8) = 58 -> HIGH
        assertThat(result.riskScore()).isEqualTo(58);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.riskReasons()).isNotEmpty();
    }

    @Test
    void getProjectCoverage_riskWeightedPercent_lowerThanFlatWhenHighRiskEndpointUncovered() {
        // GET /products: risk thấp (method GET = 0 điểm) nhưng ĐÃ có case khớp status 200 -> 100% covered.
        Endpoint lowRisk = endpoint("/products", "GET", false, buildSchemaJson(null, List.of("200")));
        // DELETE /payment: risk cao (DELETE + auth), có 1 status code khai báo nhưng KHÔNG có test case nào -> 0% covered.
        Endpoint highRisk = endpoint("/payment", "DELETE", true, buildSchemaJson(null, List.of("204")));
        TestCase lowRiskCovered = testCase(lowRisk, TestCaseSource.AI_GENERATED, 200, null);

        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(lowRisk, highRisk));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(lowRiskCovered));
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        ProjectCoverageResponse result = endpointCoverageService.getProjectCoverage(projectId);

        // highRisk hoàn toàn chưa test (204 MISSING, auth 401 MISSING) -> risk-weighted phải thấp hơn
        // rõ rệt so với overallCoveragePercent phẳng (vốn bị lowRisk kéo lên vì vacuously 100%).
        assertThat(result.overallRiskWeightedCoveragePercent()).isLessThan(result.overallCoveragePercent());
    }

    @Test
    void getEndpointCoverage_unparseableSchema_marksSchemaUnreadableInsteadOfVacuous100Percent() {
        // Module 15f - trước đây relevant==0 (vì extractSchemaFacts() nuốt lỗi trả rỗng) khiến
        // coveragePercent() ngầm trả 100.0, lẫn lộn "không có gì cần kiểm" với "không đọc được gì
        // cả". Giờ phải báo rõ schemaReadable=false để FE không tin số % này.
        Endpoint endpoint = endpoint("/broken", "POST", false, "{ khong phai json hop le");

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of());
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        assertThat(result.schemaReadable()).isFalse();
    }

    @Test
    void getEndpointCoverage_validSchemaWithNoConstraints_schemaReadableTrue() {
        String schemaJson = buildSchemaJson(null, List.of("200"));
        Endpoint endpoint = endpoint("/health", "GET", false, schemaJson);

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of());
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        assertThat(result.schemaReadable()).isTrue();
    }

    @Test
    void getEndpointCoverage_coveredDimension_reportsExactCoveringTestCaseAsProvenance() {
        Schema<?> bodySchema = new Schema<>().required(List.of("email")).properties(
                Map.of("email", new Schema<>().type("string")));
        String schemaJson = buildSchemaJson(jsonBody(bodySchema), List.of("200", "400"));
        Endpoint endpoint = endpoint("/users", "POST", false, schemaJson);

        TestCase negativeMissingEmail = TestCase.builder()
                .id(UUID.randomUUID()).endpoint(endpoint).name("Negative - thieu email")
                .source(TestCaseSource.AI_GENERATED).expectedStatus(400).requestBody("{}")
                .authOverride(TestCaseAuthOverride.DEFAULT).createdAt(Instant.now()).build();
        TestCase unrelatedPositive = testCase(endpoint, TestCaseSource.AI_GENERATED, 200, "{\"email\":\"a@b.com\"}");

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(negativeMissingEmail, unrelatedPositive));
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        CoverageDimensionResponse requiredFieldDim = result.dimensions().stream()
                .filter(d -> d.category().equals("REQUIRED_FIELD")).findFirst().orElseThrow();
        assertThat(requiredFieldDim.coveringTestCases()).hasSize(1);
        assertThat(requiredFieldDim.coveringTestCases().get(0).id()).isEqualTo(negativeMissingEmail.getId());
        assertThat(requiredFieldDim.coveringTestCases().get(0).name()).isEqualTo("Negative - thieu email");
    }

    @Test
    void getEndpointCoverage_boundaryFieldOnlyMiddleValue_isMissingNotCovered() {
        // Module 15g - sửa lỗ hổng: trước đây chỉ cần field CÓ MẶT là COVERED, không so giá trị với
        // min/max thật. quantity=50 (giữa khoảng 1-100) KHÔNG phải test biên thật.
        Schema<?> quantitySchema = new Schema<>().type("integer")
                .minimum(java.math.BigDecimal.ONE).maximum(java.math.BigDecimal.valueOf(100));
        Schema<?> bodySchema = new Schema<>().properties(Map.of("quantity", quantitySchema));
        String schemaJson = buildSchemaJson(jsonBody(bodySchema), List.of("200"));
        Endpoint endpoint = endpoint("/orders", "POST", false, schemaJson);
        TestCase middleValue = testCase(endpoint, TestCaseSource.AI_GENERATED, 200, "{\"quantity\":50}");

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(middleValue));
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        CoverageDimensionResponse boundaryDim = result.dimensions().stream()
                .filter(d -> d.category().equals("BOUNDARY")).findFirst().orElseThrow();
        assertThat(boundaryDim.status()).isEqualTo(CoverageDimensionStatus.MISSING);
        assertThat(boundaryDim.detail()).contains("chưa case nào chạm giá trị");
        assertThat(boundaryDim.coveringTestCases()).isEmpty();
    }

    @Test
    void getEndpointCoverage_boundaryFieldAtMinimum_isCovered() {
        Schema<?> quantitySchema = new Schema<>().type("integer")
                .minimum(java.math.BigDecimal.ONE).maximum(java.math.BigDecimal.valueOf(100));
        Schema<?> bodySchema = new Schema<>().properties(Map.of("quantity", quantitySchema));
        String schemaJson = buildSchemaJson(jsonBody(bodySchema), List.of("200"));
        Endpoint endpoint = endpoint("/orders", "POST", false, schemaJson);
        TestCase atMin = testCase(endpoint, TestCaseSource.AI_GENERATED, 200, "{\"quantity\":1}");

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(atMin));
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        CoverageDimensionResponse boundaryDim = result.dimensions().stream()
                .filter(d -> d.category().equals("BOUNDARY")).findFirst().orElseThrow();
        assertThat(boundaryDim.status()).isEqualTo(CoverageDimensionStatus.COVERED);
        assertThat(boundaryDim.coveringTestCases()).containsExactly(
                new CoverageDimensionResponse.TestCaseRef(atMin.getId(), atMin.getName()));
    }

    @Test
    void getEndpointCoverage_boundaryFieldAboveMaximum_isCovered() {
        Schema<?> quantitySchema = new Schema<>().type("integer")
                .minimum(java.math.BigDecimal.ONE).maximum(java.math.BigDecimal.valueOf(100));
        Schema<?> bodySchema = new Schema<>().properties(Map.of("quantity", quantitySchema));
        String schemaJson = buildSchemaJson(jsonBody(bodySchema), List.of("200"));
        Endpoint endpoint = endpoint("/orders", "POST", false, schemaJson);
        TestCase aboveMax = testCase(endpoint, TestCaseSource.AI_GENERATED, 400, "{\"quantity\":999}");

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(aboveMax));
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        CoverageDimensionResponse boundaryDim = result.dimensions().stream()
                .filter(d -> d.category().equals("BOUNDARY")).findFirst().orElseThrow();
        assertThat(boundaryDim.status()).isEqualTo(CoverageDimensionStatus.COVERED);
    }

    @Test
    void getEndpointCoverage_boundaryFieldOnQueryParam_checksPathParamFallbacksNotBody() {
        // Fix bug thuc te (phat hien khi test bang Docker + du lieu that 2026-10-15): truoc day
        // dimension BOUNDARY chi doc requestBody du boundaryRules co the chua field khai o
        // query/path param - field loai nay khong bao gio COVERED duoc. "quantity" la QUERY param
        // (khong phai body field), gia tri thuc nam trong pathParamFallbacks cua test case.
        Schema<?> quantitySchema = new Schema<>().type("integer")
                .minimum(java.math.BigDecimal.ONE).maximum(java.math.BigDecimal.valueOf(100));
        Parameter quantityParam = new Parameter().name("quantity").in("query").required(true).schema(quantitySchema);
        Map<String, Object> trimmed = new LinkedHashMap<>();
        trimmed.put("parameters", List.of(quantityParam));
        String schemaJson;
        try {
            schemaJson = objectMapper.writeValueAsString(trimmed);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Endpoint endpoint = endpoint("/stock", "PATCH", false, schemaJson);

        TestCase atMinViaQueryParam = TestCase.builder()
                .id(UUID.randomUUID()).endpoint(endpoint).name("Boundary - quantity bien nho nhat")
                .source(TestCaseSource.AI_GENERATED).expectedStatus(200)
                .pathParamFallbacks("{\"quantity\":\"1\"}")
                .authOverride(TestCaseAuthOverride.DEFAULT).createdAt(Instant.now()).build();

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(atMinViaQueryParam));
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        CoverageDimensionResponse boundaryDim = result.dimensions().stream()
                .filter(d -> d.category().equals("BOUNDARY")).findFirst().orElseThrow();
        assertThat(boundaryDim.status()).isEqualTo(CoverageDimensionStatus.COVERED);
        assertThat(boundaryDim.coveringTestCases()).hasSize(1);
    }

    /**
     * Module 15h (sửa lỗi có sẵn) - giá trị biên của query param ghi thẳng trong resolvedPath (đúng
     * cách prompt dặn AI), pathParamFallbacks trống. Trước đây Coverage chỉ đọc fallbacks nên báo
     * MISSING dù đã có case test biên.
     */
    @Test
    void getEndpointCoverage_boundaryValueWrittenLiterallyInUrl_isCovered() {
        Schema<?> quantitySchema = new Schema<>().type("integer")
                .minimum(java.math.BigDecimal.ONE).maximum(java.math.BigDecimal.valueOf(100));
        Parameter quantityParam = new Parameter().name("quantity").in("query").required(true).schema(quantitySchema);
        Map<String, Object> trimmed = new LinkedHashMap<>();
        trimmed.put("parameters", List.of(quantityParam));
        String schemaJson;
        try {
            schemaJson = objectMapper.writeValueAsString(trimmed);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Endpoint endpoint = endpoint("/stock", "PATCH", false, schemaJson);

        TestCase belowMinInUrl = TestCase.builder()
                .id(UUID.randomUUID()).endpoint(endpoint).name("Boundary - quantity = 0")
                .source(TestCaseSource.AI_GENERATED).expectedStatus(400)
                .resolvedPath("/stock?quantity=0")
                .authOverride(TestCaseAuthOverride.DEFAULT).createdAt(Instant.now()).build();

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(belowMinInUrl));
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        CoverageDimensionResponse boundaryDim = result.dimensions().stream()
                .filter(d -> d.category().equals("BOUNDARY")).findFirst().orElseThrow();
        assertThat(boundaryDim.status()).isEqualTo(CoverageDimensionStatus.COVERED);
    }

    /**
     * Module 15h - tính chất "checklist đạt thì Coverage cũng COVERED": dựng đủ mọi case checklist
     * (bằng chính planner.fill), đưa vào Coverage Matrix, mọi dimension REQUIRED_FIELD/BOUNDARY phải
     * COVERED. Nếu 2 bên hiểu "đủ" khác nhau, test này vỡ - tránh cảnh demo "chỗ này đủ, chỗ kia thiếu".
     */
    @Test
    void getEndpointCoverage_casesSatisfyingWholeChecklist_coverEveryRequiredAndBoundaryDimension() {
        String schemaJson = """
                {"parameters":[{"name":"minPrice","in":"query","schema":{"type":"integer","minimum":0}}],
                 "requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["email","age"],
                   "properties":{"email":{"type":"string"},
                                 "age":{"type":"integer","minimum":18,"maximum":60},
                                 "name":{"type":"string","minLength":2,"maxLength":5},
                                 "role":{"type":"string","enum":["USER","ADMIN"]}}}}}}}""";
        Endpoint endpoint = endpoint("/users", "POST", false, schemaJson);
        TestCaseChecklistPlanner planner = new TestCaseChecklistPlanner();
        TestCaseChecklistPlanner.Spec spec = planner.readSpec(endpoint);
        com.aiapitesting.backend.dto.ai.GeneratedTestCase baseline = new com.aiapitesting.backend.dto.ai.GeneratedTestCase(
                "Positive", "d", Map.of(), "{\"email\":\"an@gmail.com\",\"age\":25,\"name\":\"abc\",\"role\":\"USER\"}", 201,
                "/users?minPrice={{minPrice}}", Map.of("minPrice", "10"), null, null);

        List<TestCase> cases = new java.util.ArrayList<>();
        for (TestCaseChecklistPlanner.ChecklistItem item : planner.plan(spec, true, true, true)) {
            com.aiapitesting.backend.dto.ai.GeneratedTestCase g = item.location() == null ? baseline : planner.fill(spec, item, baseline);
            assertThat(planner.satisfies(spec, item, g)).as(item.description()).isTrue();
            String fallbacks;
            try {
                fallbacks = objectMapper.writeValueAsString(g.pathParamFallbacks());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            cases.add(TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).name(g.name())
                    .source(TestCaseSource.AI_GENERATED).expectedStatus(g.expectedStatus())
                    .requestBody(g.requestBody()).resolvedPath(g.resolvedPath()).pathParamFallbacks(fallbacks)
                    .authOverride(TestCaseAuthOverride.DEFAULT).createdAt(Instant.now()).build());
        }

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(cases);
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        assertThat(result.dimensions())
                .filteredOn(d -> d.category().equals("REQUIRED_FIELD") || d.category().equals("BOUNDARY"))
                .hasSize(6) // 2 required (email, age) + 4 boundary (age, name, role, minPrice)
                .allMatch(d -> d.status() == CoverageDimensionStatus.COVERED);
    }

    @Test
    void getEndpointCoverage_enumOnlyField_anyValuePresentIsCovered() {
        // Field chỉ có enum (không min/max/length) giữ hành vi cũ - có mặt là đủ, vì enum không có
        // khái niệm "biên" số/độ dài rõ ràng.
        Schema<?> statusSchema = new Schema<>().type("string")._enum(List.of("ACTIVE", "INACTIVE"));
        Schema<?> bodySchema = new Schema<>().properties(Map.of("status", statusSchema));
        String schemaJson = buildSchemaJson(jsonBody(bodySchema), List.of("200"));
        Endpoint endpoint = endpoint("/orders", "POST", false, schemaJson);
        TestCase anyValue = testCase(endpoint, TestCaseSource.AI_GENERATED, 200, "{\"status\":\"ACTIVE\"}");

        when(endpointRepository.findByIdAndProject(endpoint.getId(), project)).thenReturn(java.util.Optional.of(endpoint));
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoint));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(anyValue));
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testResultRepository.countGroupedByEndpointAndStatus(project)).thenReturn(List.of());

        EndpointCoverageResponse result = endpointCoverageService.getEndpointCoverage(projectId, endpoint.getId());

        CoverageDimensionResponse boundaryDim = result.dimensions().stream()
                .filter(d -> d.category().equals("BOUNDARY")).findFirst().orElseThrow();
        assertThat(boundaryDim.status()).isEqualTo(CoverageDimensionStatus.COVERED);
    }

    private TestResultRepository.EndpointStatusCount endpointStatusCount(UUID endpointId, TestResultStatus status, long count) {
        return new TestResultRepository.EndpointStatusCount() {
            @Override
            public UUID getEndpointId() {
                return endpointId;
            }

            @Override
            public TestResultStatus getStatus() {
                return status;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }
}
