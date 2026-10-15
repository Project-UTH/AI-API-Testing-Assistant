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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
@Slf4j
public class EndpointCoverageService {

    private static final Set<String> SENSITIVE_FIELD_KEYWORDS = Set.of(
            "password", "email", "token", "secret", "card", "cvv", "ssn", "apikey", "api_key", "credit");

    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseDependencyRepository testCaseDependencyRepository;
    private final TestResultRepository testResultRepository;
    // FAIL_ON_UNKNOWN_PROPERTIES tắt vì StoredSchema chỉ khai báo đúng field cần dùng (parameters/
    // requestBody/responseStatusCodes) - buildSchemaJson() (EndpointImportService) còn ghi thêm
    // "summary" mà parser này không cần đọc lại. Thiếu dòng này gây UnrecognizedPropertyException
    // thật (đã xác nhận khi verify bằng dữ liệu Docker thật - mọi dimension REQUIRED_FIELD/BOUNDARY/
    // STATUS_CODE bị rớt sạch vì exception bị extractSchemaFacts() nuốt và trả rỗng, chỉ unit test
    // với fixture tự dựng không bắt được vì fixture không có field "summary" như data thật).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
            List<TestCase> matches = testCases.stream()
                    .filter(tc -> tc.getExpectedStatus() != null && tc.getExpectedStatus() >= 400
                            && !extractBodyFieldNames(tc.getRequestBody()).contains(field))
                    .toList();
            dimensions.add(new CoverageDimensionResponse(
                    "REQUIRED_FIELD", "Trường bắt buộc: " + field,
                    matches.isEmpty() ? CoverageDimensionStatus.MISSING : CoverageDimensionStatus.COVERED,
                    matches.isEmpty() ? "Chưa có test case nào thiếu field '" + field + "' để kiểm tra validation" : null,
                    toRefs(matches)));
        }
        for (Map.Entry<String, BoundaryRule> entry : facts.boundaryRules().entrySet()) {
            String field = entry.getKey();
            BoundaryRule rule = entry.getValue();
            List<TestCase> touching = testCases.stream()
                    .filter(tc -> parseBody(tc.getRequestBody()).containsKey(field))
                    .toList();
            List<TestCase> atBoundary = touching.stream()
                    .filter(tc -> isBoundaryValue(rule, parseBody(tc.getRequestBody()).get(field)))
                    .toList();
            String detail;
            if (!atBoundary.isEmpty()) {
                detail = null;
            } else if (!touching.isEmpty()) {
                // Module 15g - phân biệt rõ "có case đụng field nhưng chưa chạm biên" khỏi "chưa có
                // case nào đụng field" - 2 tình huống khác hẳn nhau, gộp chung sẽ đánh lừa người đọc
                // rằng Coverage Matrix đã kiểm giá trị biên trong khi thực ra chỉ có case giá trị
                // "an toàn ở giữa khoảng" (vd quantity=50 khi min=1/max=100).
                detail = "Có " + touching.size() + " test case đụng field '" + field + "' nhưng chưa case nào chạm giá trị "
                        + "biên (min/max/length) khai báo trong spec - chỉ test giá trị giữa khoảng, chưa test biên thật";
            } else {
                detail = "Chưa có test case nào đụng tới field '" + field + "' (có ràng buộc enum/min/max trong spec)";
            }
            dimensions.add(new CoverageDimensionResponse(
                    "BOUNDARY", "Giá trị biên/enum: " + field,
                    atBoundary.isEmpty() ? CoverageDimensionStatus.MISSING : CoverageDimensionStatus.COVERED,
                    detail, toRefs(atBoundary)));
        }
        for (String statusCode : facts.statusCodes()) {
            List<TestCase> matches = testCases.stream()
                    .filter(tc -> tc.getExpectedStatus() != null && tc.getExpectedStatus().toString().equals(statusCode))
                    .toList();
            dimensions.add(new CoverageDimensionResponse(
                    "STATUS_CODE", "Mã trạng thái: " + statusCode,
                    matches.isEmpty() ? CoverageDimensionStatus.MISSING : CoverageDimensionStatus.COVERED,
                    matches.isEmpty() ? "Chưa có test case nào kỳ vọng đúng mã " + statusCode : null,
                    toRefs(matches)));
        }
        dimensions.add(authDimension401(endpoint, testCases));
        dimensions.add(new CoverageDimensionResponse(
                "AUTH", "Phân quyền (403 Forbidden)", CoverageDimensionStatus.NOT_APPLICABLE,
                "Không áp dụng - hệ thống chưa có mô hình vai trò/scope của target API để giả lập "
                        + "'có đăng nhập nhưng không đủ quyền', xem Module 15 trong roadmap",
                List.of()));

        long covered = dimensions.stream().filter(d -> d.status() == CoverageDimensionStatus.COVERED).count();
        long relevant = dimensions.stream().filter(d -> d.status() != CoverageDimensionStatus.NOT_APPLICABLE).count();
        double coveragePercent = relevant == 0 ? 100.0 : (covered * 100.0) / relevant;

        RiskResult risk = computeRisk(endpoint, facts, context);

        return new EndpointCoverageResponse(
                endpoint.getId(), endpoint.getPath(), endpoint.getMethod(),
                round1(coveragePercent), facts.parsed(), risk.score(), risk.level(), risk.reasons(), dimensions);
    }

    private List<CoverageDimensionResponse.TestCaseRef> toRefs(List<TestCase> testCases) {
        return testCases.stream().map(tc -> new CoverageDimensionResponse.TestCaseRef(tc.getId(), tc.getName())).toList();
    }

    private CoverageDimensionResponse authDimension401(Endpoint endpoint, List<TestCase> testCases) {
        if (!endpoint.isRequiresAuth()) {
            return new CoverageDimensionResponse(
                    "AUTH", "Xác thực (401 Unauthorized)", CoverageDimensionStatus.NOT_APPLICABLE,
                    "Endpoint không yêu cầu xác thực theo spec", List.of());
        }
        List<TestCase> matches = testCases.stream()
                .filter(tc -> tc.getSource() == TestCaseSource.SECURITY
                        && (tc.getAuthOverride() == TestCaseAuthOverride.NONE || tc.getAuthOverride() == TestCaseAuthOverride.INVALID))
                .toList();
        return new CoverageDimensionResponse(
                "AUTH", "Xác thực (401 Unauthorized)",
                matches.isEmpty() ? CoverageDimensionStatus.MISSING : CoverageDimensionStatus.COVERED,
                matches.isEmpty() ? "Endpoint yêu cầu auth nhưng chưa có test case Security kiểm tra thiếu/sai auth" : null,
                toRefs(matches));
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

    /**
     * Module 15g - giữ lại CHI TIẾT ràng buộc biên (không chỉ tên field) để biết "chạm biên" nghĩa là
     * gì: field chỉ có enum thì không có khái niệm biên số/độ dài rõ ràng - giá trị bất kỳ đã kích
     * hoạt kiểm tra enum thật nên vẫn coi field có mặt là đủ; field có minimum/maximum/minLength/
     * maxLength thì PHẢI có giá trị thật `<= minimum`/`>= maximum` (hoặc tương đương với length) mới
     * tính là đã test biên - giá trị "an toàn ở giữa khoảng" không tính.
     */
    private record BoundaryRule(List<String> enumValues, BigDecimal minimum, BigDecimal maximum, Integer minLength, Integer maxLength) {
        boolean hasNumericOrLengthBound() {
            return minimum != null || maximum != null || minLength != null || maxLength != null;
        }
    }

    /** @param parsed false khi Endpoint.schema không đọc được (JSON hỏng/lỗi cấu trúc) - phân biệt với "parse được nhưng không có ràng buộc nào" (parsed=true, set rỗng), để EndpointCoverageResponse.schemaReadable báo đúng, không ngầm hiểu 100% coverage khi thực ra không biết gì cả. */
    private record SchemaFacts(Set<String> requiredFields, Map<String, BoundaryRule> boundaryRules, Set<String> sensitiveFields, Set<String> statusCodes, boolean parsed) {
        static SchemaFacts empty() {
            return new SchemaFacts(Set.of(), Map.of(), Set.of(), Set.of(), true);
        }

        static SchemaFacts unreadable() {
            return new SchemaFacts(Set.of(), Map.of(), Set.of(), Set.of(), false);
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
            log.warn("Khong parse duoc schema cua endpoint {} de tinh coverage: {}", endpoint.getId(), e.toString());
            return SchemaFacts.unreadable();
        }

        Set<String> requiredFields = new LinkedHashSet<>();
        Map<String, BoundaryRule> boundaryRules = new LinkedHashMap<>();
        Set<String> sensitiveFields = new LinkedHashSet<>();

        if (stored.parameters != null) {
            for (Parameter p : stored.parameters) {
                collectFieldFacts(p.getName(), p.getSchema(), boundaryRules, sensitiveFields);
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
                            collectFieldFacts(name, propSchema, boundaryRules, sensitiveFields));
                }
            }
        }
        Set<String> statusCodes = stored.responseStatusCodes == null ? Set.of() : new LinkedHashSet<>(stored.responseStatusCodes);
        return new SchemaFacts(requiredFields, boundaryRules, sensitiveFields, statusCodes, true);
    }

    private void collectFieldFacts(String name, Schema<?> schema, Map<String, BoundaryRule> boundaryRules, Set<String> sensitiveFields) {
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
        List<String> enumValues = schema.getEnum() == null || schema.getEnum().isEmpty()
                ? null : schema.getEnum().stream().map(String::valueOf).toList();
        boolean hasBoundaryRule = enumValues != null
                || schema.getMinimum() != null || schema.getMaximum() != null
                || schema.getMinLength() != null || schema.getMaxLength() != null;
        if (hasBoundaryRule) {
            boundaryRules.put(name, new BoundaryRule(
                    enumValues, schema.getMinimum(), schema.getMaximum(), schema.getMinLength(), schema.getMaxLength()));
        }
    }

    /**
     * Module 15g - CHỈ tính là "chạm biên" khi field có ràng buộc số/độ dài VÀ giá trị thật nằm ở
     * biên hoặc vượt biên (`<= minimum`/`>= maximum`, tương đương cho length) - giá trị giữa khoảng
     * KHÔNG tính dù field có mặt. Field chỉ có enum (không min/max/length) giữ hành vi cũ: có mặt là
     * đủ, vì enum không có khái niệm "biên" số/độ dài rõ ràng - giá trị bất kỳ đã kích hoạt kiểm tra
     * enum thật khi API thực thi.
     */
    private boolean isBoundaryValue(BoundaryRule rule, Object rawValue) {
        if (rawValue == null) {
            return false;
        }
        if (!rule.hasNumericOrLengthBound()) {
            return true;
        }
        String value = String.valueOf(rawValue);
        if (rule.minLength() != null || rule.maxLength() != null) {
            int length = value.length();
            if (rule.minLength() != null && length <= rule.minLength()) {
                return true;
            }
            if (rule.maxLength() != null && length >= rule.maxLength()) {
                return true;
            }
        }
        if (rule.minimum() != null || rule.maximum() != null) {
            BigDecimal numeric = parseNumeric(value);
            if (numeric == null) {
                return false;
            }
            if (rule.minimum() != null && numeric.compareTo(rule.minimum()) <= 0) {
                return true;
            }
            if (rule.maximum() != null && numeric.compareTo(rule.maximum()) >= 0) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal parseNumeric(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Đọc top-level key của requestBody JSON (do AI/người dùng lưu) - trả rỗng nếu null/blank/không parse được. */
    private Set<String> extractBodyFieldNames(String requestBodyJson) {
        return parseBody(requestBodyJson).keySet();
    }

    /** Module 15g - cần cả GIÁ TRỊ (không chỉ tên field) để kiểm tra "chạm biên" - trả rỗng nếu null/blank/không parse được. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(String requestBodyJson) {
        if (requestBodyJson == null || requestBodyJson.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(requestBodyJson, Object.class);
            return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
