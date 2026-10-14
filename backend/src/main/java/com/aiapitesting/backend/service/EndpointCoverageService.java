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
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Module 15c - Coverage Matrix + Risk Engine. TẤT ĐỊNH TUYỆT ĐỐI: mọi giá trị trả về tính từ
 * {@code Endpoint.schema} (lưu lúc import) + {@code TestCase}/{@code TestCaseDependency}/
 * {@code TestResult} hiện có trong DB - KHÔNG gọi AI, KHÔNG parse {@code TestCase.rationale} (lời
 * AI tự khai) để suy coverage. Đây là nguồn sự thật độc lập, kiểm chứng được, tách bạch khỏi phần
 * giải thích AI ở Module 15a - xem "Nguyên tắc thiết kế quan trọng" ở Module 15 trong roadmap.
 */
@Service
@RequiredArgsConstructor
public class EndpointCoverageService {

    private static final Set<String> SENSITIVE_FIELD_KEYWORDS = Set.of(
            "password", "email", "token", "secret", "card", "cvv", "ssn", "apikey", "api_key", "credit");

    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseDependencyRepository testCaseDependencyRepository;
    private final TestResultRepository testResultRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProjectCoverageResponse getProjectCoverage(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        List<Endpoint> endpoints = endpointRepository.findAllByProjectOrderByCreatedAtAsc(project);
        CoverageContext context = buildContext(project, endpoints);

        List<EndpointCoverageResponse> endpointResponses = endpoints.stream()
                .map(endpoint -> computeForEndpoint(endpoint, context))
                .toList();

        return aggregateProject(project.getId(), endpointResponses);
    }

    public EndpointCoverageResponse getEndpointCoverage(UUID projectId, UUID endpointId) {
        Project project = projectService.getOwnedProject(projectId);
        Endpoint endpoint = endpointRepository.findByIdAndProject(endpointId, project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));
        // Vẫn load context của CẢ project (không chỉ 1 endpoint) - dependency risk của 1 endpoint
        // phụ thuộc vào test case của NHỮNG endpoint khác trong cùng project.
        List<Endpoint> allEndpoints = endpointRepository.findAllByProjectOrderByCreatedAtAsc(project);
        CoverageContext context = buildContext(project, allEndpoints);
        return computeForEndpoint(endpoint, context);
    }

    private ProjectCoverageResponse aggregateProject(UUID projectId, List<EndpointCoverageResponse> endpoints) {
        long coveredTotal = 0;
        long dimensionTotal = 0;
        double riskWeightedSum = 0;
        double riskWeightTotal = 0;
        for (EndpointCoverageResponse e : endpoints) {
            long covered = e.dimensions().stream().filter(d -> d.status() == CoverageDimensionStatus.COVERED).count();
            long relevant = e.dimensions().stream().filter(d -> d.status() != CoverageDimensionStatus.NOT_APPLICABLE).count();
            coveredTotal += covered;
            dimensionTotal += relevant;
            if (e.riskScore() > 0) {
                riskWeightedSum += e.riskScore() * (e.coveragePercent() / 100.0);
                riskWeightTotal += e.riskScore();
            }
        }
        double overallCoveragePercent = dimensionTotal == 0 ? 100.0 : (coveredTotal * 100.0) / dimensionTotal;
        double overallRiskWeighted = riskWeightTotal == 0 ? overallCoveragePercent : (riskWeightedSum * 100.0) / riskWeightTotal;
        return new ProjectCoverageResponse(projectId, round1(overallCoveragePercent), round1(overallRiskWeighted), endpoints);
    }

    // ---- Dữ liệu dùng chung cho toàn project (tránh N+1 khi tính cho nhiều endpoint) ----

    private CoverageContext buildContext(Project project, List<Endpoint> endpoints) {
        Map<UUID, List<TestCase>> testCasesByEndpointId = testCaseRepository.findAllByEndpointProject(project).stream()
                .collect(Collectors.groupingBy(tc -> tc.getEndpoint().getId()));

        // Endpoint X là "nguồn" cho endpoint Y nếu có TestCaseDependency: case của Y phụ thuộc vào
        // case của X. Dùng để tính Dependency risk: X càng có nhiều Y khác phụ thuộc, risk càng cao.
        Map<UUID, Set<UUID>> dependentEndpointIdsBySourceEndpointId = new LinkedHashMap<>();
        for (TestCaseDependency dep : testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)) {
            UUID sourceEndpointId = dep.getDependsOnTestCase().getEndpoint().getId();
            UUID consumerEndpointId = dep.getTestCase().getEndpoint().getId();
            if (sourceEndpointId.equals(consumerEndpointId)) {
                continue;
            }
            dependentEndpointIdsBySourceEndpointId
                    .computeIfAbsent(sourceEndpointId, k -> new LinkedHashSet<>())
                    .add(consumerEndpointId);
        }

        Map<UUID, long[]> resultCountsByEndpointId = new LinkedHashMap<>(); // [0]=total, [1]=failed
        for (TestResultRepository.EndpointStatusCount row : testResultRepository.countGroupedByEndpointAndStatus(project)) {
            long[] counts = resultCountsByEndpointId.computeIfAbsent(row.getEndpointId(), k -> new long[2]);
            counts[0] += row.getCount();
            if (row.getStatus() == TestResultStatus.FAILED) {
                counts[1] += row.getCount();
            }
        }

        return new CoverageContext(testCasesByEndpointId, dependentEndpointIdsBySourceEndpointId, resultCountsByEndpointId);
    }

    private record CoverageContext(
            Map<UUID, List<TestCase>> testCasesByEndpointId,
            Map<UUID, Set<UUID>> dependentEndpointIdsBySourceEndpointId,
            Map<UUID, long[]> resultCountsByEndpointId
    ) {
    }

    // ---- Tính cho 1 endpoint ----

    private EndpointCoverageResponse computeForEndpoint(Endpoint endpoint, CoverageContext context) {
        List<TestCase> testCases = context.testCasesByEndpointId().getOrDefault(endpoint.getId(), List.of());
        SchemaFacts facts = extractSchemaFacts(endpoint);

        List<CoverageDimensionResponse> dimensions = new ArrayList<>();
        for (String field : facts.requiredFields()) {
            boolean covered = testCases.stream().anyMatch(tc ->
                    tc.getExpectedStatus() != null && tc.getExpectedStatus() >= 400
                            && !extractBodyFieldNames(tc.getRequestBody()).contains(field));
            dimensions.add(new CoverageDimensionResponse(
                    "REQUIRED_FIELD", "Trường bắt buộc: " + field,
                    covered ? CoverageDimensionStatus.COVERED : CoverageDimensionStatus.MISSING,
                    covered ? null : "Chưa có test case nào thiếu field '" + field + "' để kiểm tra validation"));
        }
        for (String field : facts.boundaryFields()) {
            boolean covered = testCases.stream().anyMatch(tc -> extractBodyFieldNames(tc.getRequestBody()).contains(field));
            dimensions.add(new CoverageDimensionResponse(
                    "BOUNDARY", "Giá trị biên/enum: " + field,
                    covered ? CoverageDimensionStatus.COVERED : CoverageDimensionStatus.MISSING,
                    covered ? null : "Chưa có test case nào đụng tới field '" + field + "' (có ràng buộc enum/min/max trong spec)"));
        }
        for (String statusCode : facts.statusCodes()) {
            boolean covered = testCases.stream().anyMatch(tc ->
                    tc.getExpectedStatus() != null && tc.getExpectedStatus().toString().equals(statusCode));
            dimensions.add(new CoverageDimensionResponse(
                    "STATUS_CODE", "Mã trạng thái: " + statusCode,
                    covered ? CoverageDimensionStatus.COVERED : CoverageDimensionStatus.MISSING,
                    covered ? null : "Chưa có test case nào kỳ vọng đúng mã " + statusCode));
        }
        dimensions.add(authDimension401(endpoint, testCases));
        dimensions.add(new CoverageDimensionResponse(
                "AUTH", "Phân quyền (403 Forbidden)", CoverageDimensionStatus.NOT_APPLICABLE,
                "Không áp dụng - hệ thống chưa có mô hình vai trò/scope của target API để giả lập "
                        + "'có đăng nhập nhưng không đủ quyền', xem Module 15 trong roadmap"));

        long covered = dimensions.stream().filter(d -> d.status() == CoverageDimensionStatus.COVERED).count();
        long relevant = dimensions.stream().filter(d -> d.status() != CoverageDimensionStatus.NOT_APPLICABLE).count();
        double coveragePercent = relevant == 0 ? 100.0 : (covered * 100.0) / relevant;

        RiskResult risk = computeRisk(endpoint, facts, context);

        return new EndpointCoverageResponse(
                endpoint.getId(), endpoint.getPath(), endpoint.getMethod(),
                round1(coveragePercent), risk.score(), risk.level(), risk.reasons(), dimensions);
    }

    private CoverageDimensionResponse authDimension401(Endpoint endpoint, List<TestCase> testCases) {
        if (!endpoint.isRequiresAuth()) {
            return new CoverageDimensionResponse(
                    "AUTH", "Xác thực (401 Unauthorized)", CoverageDimensionStatus.NOT_APPLICABLE,
                    "Endpoint không yêu cầu xác thực theo spec");
        }
        boolean covered = testCases.stream().anyMatch(tc ->
                tc.getSource() == TestCaseSource.SECURITY
                        && (tc.getAuthOverride() == TestCaseAuthOverride.NONE || tc.getAuthOverride() == TestCaseAuthOverride.INVALID));
        return new CoverageDimensionResponse(
                "AUTH", "Xác thực (401 Unauthorized)",
                covered ? CoverageDimensionStatus.COVERED : CoverageDimensionStatus.MISSING,
                covered ? null : "Endpoint yêu cầu auth nhưng chưa có test case Security kiểm tra thiếu/sai auth");
    }

    // ---- Risk Engine: tất định, mỗi thành phần 0-20 điểm, tổng tối đa 100 ----

    private record RiskResult(int score, RiskLevel level, List<String> reasons) {
    }

    private RiskResult computeRisk(Endpoint endpoint, SchemaFacts facts, CoverageContext context) {
        List<String> reasons = new ArrayList<>();
        int score = 0;

        int methodPoints = switch (endpoint.getMethod() == null ? "" : endpoint.getMethod().toUpperCase(Locale.ROOT)) {
            case "GET" -> 0;
            case "POST" -> 10;
            case "PUT", "PATCH" -> 12;
            case "DELETE" -> 20;
            default -> 8;
        };
        score += methodPoints;
        if (methodPoints > 0) {
            reasons.add("Phương thức " + endpoint.getMethod() + " làm thay đổi dữ liệu (+" + methodPoints + ")");
        }

        if (endpoint.isRequiresAuth()) {
            score += 20;
            reasons.add("Yêu cầu xác thực theo spec (+20)");
        }

        int dependentCount = context.dependentEndpointIdsBySourceEndpointId()
                .getOrDefault(endpoint.getId(), Set.of()).size();
        int dependencyPoints = dependentCount == 0 ? 0 : dependentCount <= 2 ? 10 : 20;
        score += dependencyPoints;
        if (dependencyPoints > 0) {
            reasons.add(dependentCount + " endpoint khác phụ thuộc dữ liệu từ endpoint này (+" + dependencyPoints + ")");
        }

        int sensitiveCount = facts.sensitiveFields().size();
        int securityPoints = sensitiveCount == 0 ? 0 : sensitiveCount == 1 ? 10 : 20;
        score += securityPoints;
        if (securityPoints > 0) {
            reasons.add("Có " + sensitiveCount + " field nhạy cảm trong dữ liệu (" + String.join(", ", facts.sensitiveFields()) + ") (+" + securityPoints + ")");
        }

        long[] counts = context.resultCountsByEndpointId().get(endpoint.getId());
        int historyPoints = 0;
        if (counts != null && counts[0] > 0) {
            double failRate = (double) counts[1] / counts[0];
            historyPoints = Math.round((float) (failRate * 20));
            if (historyPoints > 0) {
                reasons.add("Tỷ lệ fail lịch sử " + Math.round(failRate * 100) + "% (" + counts[1] + "/" + counts[0] + " lượt chạy) (+" + historyPoints + ")");
            }
        }
        score += historyPoints;

        RiskLevel level = score >= 75 ? RiskLevel.CRITICAL : score >= 50 ? RiskLevel.HIGH : score >= 25 ? RiskLevel.MEDIUM : RiskLevel.LOW;
        return new RiskResult(score, level, reasons);
    }

    // ---- Parse Endpoint.schema (JSON lưu lúc import) ----

    private record SchemaFacts(Set<String> requiredFields, Set<String> boundaryFields, Set<String> sensitiveFields, Set<String> statusCodes) {
        static SchemaFacts empty() {
            return new SchemaFacts(Set.of(), Set.of(), Set.of(), Set.of());
        }
    }

    /** Khớp đúng shape đã lưu bởi {@code EndpointImportService.buildSchemaJson()} - dùng chính các lớp model của swagger-parser để parse lại thay vì tự dò JsonNode, tránh sai lệch cấu trúc. */
    private static class StoredSchema {
        public List<Parameter> parameters;
        public RequestBody requestBody;
        public List<String> responseStatusCodes;
    }

    private SchemaFacts extractSchemaFacts(Endpoint endpoint) {
        if (endpoint.getSchema() == null || endpoint.getSchema().isBlank()) {
            return SchemaFacts.empty();
        }
        StoredSchema stored;
        try {
            stored = objectMapper.readValue(endpoint.getSchema(), StoredSchema.class);
        } catch (Exception e) {
            return SchemaFacts.empty();
        }

        Set<String> requiredFields = new LinkedHashSet<>();
        Set<String> boundaryFields = new LinkedHashSet<>();
        Set<String> sensitiveFields = new LinkedHashSet<>();

        if (stored.parameters != null) {
            for (Parameter p : stored.parameters) {
                collectFieldFacts(p.getName(), p.getSchema(), boundaryFields, sensitiveFields);
            }
        }
        if (stored.requestBody != null && stored.requestBody.getContent() != null) {
            for (MediaType mediaType : stored.requestBody.getContent().values()) {
                Schema<?> bodySchema = mediaType.getSchema();
                if (bodySchema == null) {
                    continue;
                }
                if (bodySchema.getRequired() != null) {
                    requiredFields.addAll(bodySchema.getRequired());
                }
                if (bodySchema.getProperties() != null) {
                    bodySchema.getProperties().forEach((name, propSchema) ->
                            collectFieldFacts(name, propSchema, boundaryFields, sensitiveFields));
                }
            }
        }
        Set<String> statusCodes = stored.responseStatusCodes == null ? Set.of() : new LinkedHashSet<>(stored.responseStatusCodes);
        return new SchemaFacts(requiredFields, boundaryFields, sensitiveFields, statusCodes);
    }

    private void collectFieldFacts(String name, Schema<?> schema, Set<String> boundaryFields, Set<String> sensitiveFields) {
        if (name == null) {
            return;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (SENSITIVE_FIELD_KEYWORDS.stream().anyMatch(lower::contains)) {
            sensitiveFields.add(name);
        }
        if (schema == null) {
            return;
        }
        boolean hasBoundaryRule = (schema.getEnum() != null && !schema.getEnum().isEmpty())
                || schema.getMinimum() != null || schema.getMaximum() != null
                || schema.getMinLength() != null || schema.getMaxLength() != null;
        if (hasBoundaryRule) {
            boundaryFields.add(name);
        }
    }

    /** Đọc top-level key của requestBody JSON (do AI/người dùng lưu) - trả rỗng nếu null/blank/không parse được. */
    @SuppressWarnings("unchecked")
    private Set<String> extractBodyFieldNames(String requestBodyJson) {
        if (requestBodyJson == null || requestBodyJson.isBlank()) {
            return Set.of();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(requestBodyJson, Map.class);
            return map.keySet();
        } catch (Exception e) {
            return Set.of();
        }
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
