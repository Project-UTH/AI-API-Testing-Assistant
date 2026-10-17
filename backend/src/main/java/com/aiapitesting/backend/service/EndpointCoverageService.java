package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.response.ChecklistItemType;
import com.aiapitesting.backend.dto.response.CoverageDimensionResponse;
import com.aiapitesting.backend.dto.response.CoverageDimensionStatus;
import com.aiapitesting.backend.dto.response.CoverageRunStatus;
import com.aiapitesting.backend.dto.response.EndpointCoverageResponse;
import com.aiapitesting.backend.dto.response.ProjectCoverageResponse;
import com.aiapitesting.backend.dto.response.RiskLevel;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseDependency;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Module 15c - Coverage Matrix + Risk Engine. TẤT ĐỊNH TUYỆT ĐỐI: mọi giá trị trả về tính từ
 * {@code Endpoint.schema} (lưu lúc import) + {@code TestCase}/{@code TestCaseDependency}/
 * {@code TestResult} hiện có trong DB - KHÔNG gọi AI, KHÔNG parse {@code TestCase.rationale} (lời
 * AI tự khai) để suy coverage. Đây là nguồn sự thật độc lập, kiểm chứng được, tách bạch khỏi phần
 * giải thích AI ở Module 15a - xem "Nguyên tắc thiết kế quan trọng" ở Module 15 trong roadmap.
 *
 * Module 15j (Coverage Gap Analysis) - các dòng coverage giờ là CHÍNH checklist EP/BVA của
 * {@link TestCaseChecklistPlanner} (cùng bộ tiêu chí lúc sinh test case; trước đây 15c tự định nghĩa 4
 * dimension hẹp hơn nên "100%" ở trang Coverage và "đủ checklist" lúc sinh là 2 thước đo khác nhau),
 * chấm trên TOÀN BỘ test case của endpoint theo nội dung thật. Dòng còn thiếu kèm vì sao cần kiểm + bằng
 * chứng case gần đạt; dòng đã có kèm trạng thái chạy thật; thêm dòng RELATION (chuỗi dữ liệu 2 endpoint).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EndpointCoverageService {

    private static final Set<String> SENSITIVE_FIELD_KEYWORDS = Set.of(
            "password", "email", "token", "secret", "card", "cvv", "ssn", "apikey", "api_key", "credit");

    /** Bằng chứng "gần đạt" tối đa cho 1 mục - đủ để hiểu vì sao chưa tính, không làm response phình. */
    private static final int MAX_NEAR_MISSES = 3;

    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}/]+)}");
    private static final Pattern EXACT_STATUS = Pattern.compile("\\d{3}");
    private static final Pattern RANGE_STATUS = Pattern.compile("([1-5])XX", Pattern.CASE_INSENSITIVE);

    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseDependencyRepository testCaseDependencyRepository;
    private final TestResultRepository testResultRepository;
    private final TestCaseChecklistPlanner testCaseChecklistPlanner;
    // FAIL_ON_UNKNOWN_PROPERTIES tắt vì StoredSchema chỉ khai báo đúng field cần dùng (parameters/
    // requestBody/responseStatusCodes) - buildSchemaJson() (EndpointImportService) còn ghi thêm
    // "summary" mà parser này không cần đọc lại. Thiếu dòng này gây UnrecognizedPropertyException
    // thật (đã xác nhận khi verify bằng dữ liệu Docker thật - mọi dimension bị rớt sạch vì exception bị
    // extractSchemaFacts() nuốt và trả rỗng, chỉ unit test với fixture tự dựng không bắt được vì fixture
    // không có field "summary" như data thật).
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
        // Vẫn load context của CẢ project (không chỉ 1 endpoint) - dependency risk + dòng RELATION của 1
        // endpoint phụ thuộc vào test case/endpoint KHÁC trong cùng project.
        List<Endpoint> allEndpoints = endpointRepository.findAllByProjectOrderByCreatedAtAsc(project);
        CoverageContext context = buildContext(project, allEndpoints);
        return computeForEndpoint(endpoint, context);
    }

    private ProjectCoverageResponse aggregateProject(UUID projectId, List<EndpointCoverageResponse> endpoints) {
        long coveredTotal = 0;
        long executedTotal = 0;
        long dimensionTotal = 0;
        double riskWeightedSum = 0;
        double riskWeightTotal = 0;
        for (EndpointCoverageResponse e : endpoints) {
            coveredTotal += e.dimensions().stream().filter(d -> d.status() == CoverageDimensionStatus.COVERED).count();
            executedTotal += e.dimensions().stream().filter(this::isExecuted).count();
            dimensionTotal += e.dimensions().stream().filter(d -> d.status() != CoverageDimensionStatus.NOT_APPLICABLE).count();
            if (e.riskScore() > 0) {
                riskWeightedSum += e.riskScore() * (e.coveragePercent() / 100.0);
                riskWeightTotal += e.riskScore();
            }
        }
        double overallCoveragePercent = dimensionTotal == 0 ? 100.0 : (coveredTotal * 100.0) / dimensionTotal;
        double overallExecutedPercent = dimensionTotal == 0 ? 100.0 : (executedTotal * 100.0) / dimensionTotal;
        double overallRiskWeighted = riskWeightTotal == 0 ? overallCoveragePercent : (riskWeightedSum * 100.0) / riskWeightTotal;
        return new ProjectCoverageResponse(projectId, round1(overallCoveragePercent), round1(overallExecutedPercent),
                round1(overallRiskWeighted), endpoints);
    }

    /** Dòng đã có test VÀ test đó đã chạy ra kết luận thật (PASSED hoặc FAILED). */
    private boolean isExecuted(CoverageDimensionResponse d) {
        return d.status() == CoverageDimensionStatus.COVERED
                && (d.runStatus() == CoverageRunStatus.PASSED || d.runStatus() == CoverageRunStatus.FAILED);
    }

    // ---- Dữ liệu dùng chung cho toàn project (tránh N+1 khi tính cho nhiều endpoint) ----

    private CoverageContext buildContext(Project project, List<Endpoint> endpoints) {
        Map<UUID, List<TestCase>> testCasesByEndpointId = testCaseRepository.findAllByEndpointProject(project).stream()
                .collect(Collectors.groupingBy(tc -> tc.getEndpoint().getId()));

        // Endpoint X là "nguồn" cho endpoint Y nếu có TestCaseDependency: case của Y phụ thuộc vào
        // case của X. Dùng để tính Dependency risk: X càng có nhiều Y khác phụ thuộc, risk càng cao.
        Map<UUID, Set<UUID>> dependentEndpointIdsBySourceEndpointId = new LinkedHashMap<>();
        Map<UUID, List<TestCaseDependency>> dependenciesByConsumerEndpointId = new LinkedHashMap<>();
        for (TestCaseDependency dep : testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)) {
            UUID sourceEndpointId = dep.getDependsOnTestCase().getEndpoint().getId();
            UUID consumerEndpointId = dep.getTestCase().getEndpoint().getId();
            dependenciesByConsumerEndpointId.computeIfAbsent(consumerEndpointId, k -> new ArrayList<>()).add(dep);
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

        // Module 15j - lượt chạy MỚI NHẤT của từng test case (trạng thái chạy của dòng coverage).
        Map<UUID, TestResultStatus> latestResultByTestCaseId = new HashMap<>();
        for (TestResultRepository.TestCaseLatestResult row : testResultRepository.findLatestResultsByProject(project)) {
            latestResultByTestCaseId.putIfAbsent(row.getTestCaseId(), row.getStatus());
        }

        // Module 15j - endpoint POST theo path, để dò "POST tạo resource → endpoint dùng {id}" (dòng RELATION).
        Map<String, Endpoint> postEndpointByPath = new HashMap<>();
        for (Endpoint e : endpoints) {
            if ("POST".equalsIgnoreCase(e.getMethod()) && e.getPath() != null) {
                postEndpointByPath.putIfAbsent(e.getPath(), e);
            }
        }

        return new CoverageContext(testCasesByEndpointId, dependentEndpointIdsBySourceEndpointId, resultCountsByEndpointId,
                dependenciesByConsumerEndpointId, latestResultByTestCaseId, postEndpointByPath);
    }

    private record CoverageContext(
            Map<UUID, List<TestCase>> testCasesByEndpointId,
            Map<UUID, Set<UUID>> dependentEndpointIdsBySourceEndpointId,
            Map<UUID, long[]> resultCountsByEndpointId,
            Map<UUID, List<TestCaseDependency>> dependenciesByConsumerEndpointId,
            Map<UUID, TestResultStatus> latestResultByTestCaseId,
            Map<String, Endpoint> postEndpointByPath
    ) {
    }

    /** Test case đã lưu + bản đổi sang dạng chấm được (đổi 1 lần, dùng cho mọi mục checklist). */
    private record Candidate(TestCase entity, GeneratedTestCase generated) {
    }

    // ---- Tính cho 1 endpoint ----

    private EndpointCoverageResponse computeForEndpoint(Endpoint endpoint, CoverageContext context) {
        List<TestCase> testCases = context.testCasesByEndpointId().getOrDefault(endpoint.getId(), List.of());
        SchemaFacts facts = extractSchemaFacts(endpoint);
        TestCaseChecklistPlanner.Spec spec = testCaseChecklistPlanner.readSpec(endpoint);
        List<Candidate> candidates = testCases.stream()
                .map(tc -> new Candidate(tc, testCaseChecklistPlanner.toCandidate(tc)))
                .toList();

        // Case gốc để mượn dữ liệu khi bù - chọn giống CoverageGapFillService (mục Security chỉ đổi cách gọi nên
        // không cần body hợp lệ tuyệt đối: không có case hợp lệ thì lấy case đầu tiên), để nút Bổ sung chỉ sáng khi
        // thật sự bù được. Khác duy nhất: ở đây lấy case hợp lệ đầu tiên, còn lúc bù thật ưu tiên case đã chạy PASS.
        GeneratedTestCase baseline = candidates.stream()
                .map(Candidate::generated)
                .filter(g -> testCaseChecklistPlanner.isUsableBaseline(spec, g, endpoint.isRequiresAuth()))
                .findFirst().orElse(null);
        GeneratedTestCase securityBaseline = baseline != null ? baseline
                : candidates.isEmpty() ? null : candidates.get(0).generated();

        List<CoverageDimensionResponse> dimensions = new ArrayList<>();
        for (TestCaseChecklistPlanner.ChecklistItem item : testCaseChecklistPlanner.planAll(spec, endpoint.isRequiresAuth())) {
            GeneratedTestCase base = item.type().name().startsWith("SECURITY_") ? securityBaseline : baseline;
            dimensions.add(checklistDimension(spec, item, candidates, endpoint.isRequiresAuth(), context, base));
        }
        for (String statusCode : facts.statusCodes()) {
            dimensions.add(statusCodeDimension(statusCode, testCases, context));
        }
        dimensions.addAll(relationDimensions(endpoint, context));
        dimensions.add(new CoverageDimensionResponse(
                "AUTH", "Phân quyền (403 Forbidden)", CoverageDimensionStatus.NOT_APPLICABLE,
                "Không áp dụng - hệ thống chưa có mô hình vai trò/scope của target API để giả lập "
                        + "'có đăng nhập nhưng không đủ quyền', xem Module 15 trong roadmap",
                List.of(), null, null, null, null, List.of()));

        long covered = dimensions.stream().filter(d -> d.status() == CoverageDimensionStatus.COVERED).count();
        long executed = dimensions.stream().filter(this::isExecuted).count();
        long relevant = dimensions.stream().filter(d -> d.status() != CoverageDimensionStatus.NOT_APPLICABLE).count();
        double coveragePercent = relevant == 0 ? 100.0 : (covered * 100.0) / relevant;
        double executedPercent = relevant == 0 ? 100.0 : (executed * 100.0) / relevant;

        RiskResult risk = computeRisk(endpoint, facts, context);

        return new EndpointCoverageResponse(
                endpoint.getId(), endpoint.getPath(), endpoint.getMethod(), testCases.size(),
                round1(coveragePercent), round1(executedPercent), facts.parsed(),
                risk.score(), risk.level(), risk.reasons(), dimensions);
    }

    /**
     * 1 mục checklist EP/BVA → 1 dòng coverage. COVERED khi có case đạt mục theo NỘI DUNG (quy tắc "mỗi case
     * chỉ 1 lỗi" của planner); MISSING thì kèm case "gần đạt" làm bằng chứng vì sao chưa tính, và cờ
     * {@code fillable} cho biết nút Bổ sung có bù được mục này ngay bây giờ không (thử bù thật bằng
     * {@code attemptFill}, cùng hàm dịch vụ bù dùng - không đoán).
     */
    private CoverageDimensionResponse checklistDimension(
            TestCaseChecklistPlanner.Spec spec, TestCaseChecklistPlanner.ChecklistItem item,
            List<Candidate> candidates, boolean requiresAuth, CoverageContext context, GeneratedTestCase baseline
    ) {
        String category = categoryOf(item.type());
        if (item.notApplicableReason() != null) {
            return new CoverageDimensionResponse(category, item.description(), CoverageDimensionStatus.NOT_APPLICABLE,
                    item.notApplicableReason(), List.of(), item.type().name(), null, item.rationale(), null, List.of());
        }
        String key = TestCaseChecklistPlanner.key(item);
        List<TestCase> covering = candidates.stream()
                .filter(c -> testCaseChecklistPlanner.covers(spec, item, c.generated(), requiresAuth))
                .map(Candidate::entity)
                .toList();
        if (!covering.isEmpty()) {
            return new CoverageDimensionResponse(category, item.description(), CoverageDimensionStatus.COVERED, null,
                    toRefs(covering, context), item.type().name(), key, item.rationale(), runStatus(covering, context), List.of());
        }

        List<CoverageDimensionResponse.NearMiss> nearMisses = new ArrayList<>();
        for (Candidate c : candidates) {
            String reason = testCaseChecklistPlanner.explainNearMiss(spec, item, c.generated(), requiresAuth);
            if (reason != null) {
                nearMisses.add(new CoverageDimensionResponse.NearMiss(c.entity().getId(), c.entity().getName(), reason));
            }
        }
        String detail = nearMisses.isEmpty()
                ? "Chưa có test case nào thử đúng tình huống này"
                : "Có " + nearMisses.size() + " test case gần đạt nhưng chưa được tính - xem lý do từng case";
        TestCaseChecklistPlanner.FillAttempt attempt = testCaseChecklistPlanner.attemptFill(spec, item, baseline);
        return new CoverageDimensionResponse(category, item.description(), CoverageDimensionStatus.MISSING, detail,
                List.of(), item.type().name(), key, item.rationale(), null,
                List.copyOf(nearMisses.subList(0, Math.min(MAX_NEAR_MISSES, nearMisses.size()))),
                attempt.filled() != null, attempt.blockedReason());
    }

    /** Tiền tố tên ChecklistItemType chính là loại test case (POSITIVE/NEGATIVE/BOUNDARY/SECURITY). */
    private String categoryOf(ChecklistItemType type) {
        String name = type.name();
        return name.substring(0, name.indexOf('_'));
    }

    /**
     * Mã trạng thái spec khai báo - COVERED khi có case kỳ vọng đúng mã. Hỗ trợ dạng khoảng "4XX" của
     * OpenAPI 3; "default" (= mọi mã còn lại) không phải mã cụ thể nên không đối chiếu được, ghi rõ thay
     * vì để MISSING vĩnh viễn.
     */
    private CoverageDimensionResponse statusCodeDimension(String statusCode, List<TestCase> testCases, CoverageContext context) {
        String label = "Mã trạng thái: " + statusCode;
        Matcher range = RANGE_STATUS.matcher(statusCode);
        boolean exact = EXACT_STATUS.matcher(statusCode).matches();
        if (!exact && !range.matches()) {
            return new CoverageDimensionResponse("STATUS_CODE", label, CoverageDimensionStatus.NOT_APPLICABLE,
                    "'" + statusCode + "' không phải mã trạng thái cụ thể (vd 'default' = mọi mã còn lại) - không đối chiếu "
                            + "được với kỳ vọng của test case", List.of(), null, null, null, null, List.of());
        }
        String why = "Spec khai báo endpoint có thể trả mã " + statusCode + "; cần ít nhất 1 test case kỳ vọng đúng mã "
                + "này để biết API thật sự trả về như đặc tả.";
        List<TestCase> matches = testCases.stream()
                .filter(tc -> tc.getExpectedStatus() != null && (exact
                        ? tc.getExpectedStatus().toString().equals(statusCode)
                        : tc.getExpectedStatus() / 100 == Integer.parseInt(range.group(1))))
                .toList();
        if (matches.isEmpty()) {
            return new CoverageDimensionResponse("STATUS_CODE", label, CoverageDimensionStatus.MISSING,
                    "Chưa có test case nào kỳ vọng mã " + statusCode, List.of(), null, null, why, null, List.of());
        }
        return new CoverageDimensionResponse("STATUS_CODE", label, CoverageDimensionStatus.COVERED, null,
                toRefs(matches, context), null, null, why, runStatus(matches, context), List.of());
    }

    /**
     * Module 15j - quan hệ giữa 2 endpoint: endpoint dùng tham số path {x} mà project có POST tạo đúng
     * resource đó (dò cùng cách DependencySuggestionService, Module 7) thì cần ít nhất 1 case lấy id THẬT
     * từ response của POST (TestCaseDependency) - case chỉ dùng id đoán sẵn không chứng minh được 2
     * endpoint chạy đúng cùng nhau. Không có POST tương ứng thì không tạo dòng (không có gì để nối).
     */
    private List<CoverageDimensionResponse> relationDimensions(Endpoint endpoint, CoverageContext context) {
        if (endpoint.getPath() == null) {
            return List.of();
        }
        Set<String> params = new LinkedHashSet<>();
        Matcher matcher = PATH_PARAM.matcher(endpoint.getPath());
        while (matcher.find()) {
            params.add(matcher.group(1));
        }
        List<CoverageDimensionResponse> result = new ArrayList<>();
        for (String param : params) {
            String producerPath = DependencySuggestionService.cutPathBeforeParam(endpoint.getPath(), param);
            Endpoint producer = producerPath == null || producerPath.isBlank() ? null : context.postEndpointByPath().get(producerPath);
            if (producer == null || producer.getId().equals(endpoint.getId())) {
                continue;
            }
            String producerLabel = "POST " + producer.getPath();
            Map<UUID, TestCase> chained = new LinkedHashMap<>();
            for (TestCaseDependency dep : context.dependenciesByConsumerEndpointId().getOrDefault(endpoint.getId(), List.of())) {
                if (dep.getDependsOnTestCase().getEndpoint().getId().equals(producer.getId())) {
                    chained.putIfAbsent(dep.getTestCase().getId(), dep.getTestCase());
                }
            }
            String label = "Chuỗi dữ liệu: '" + param + "' lấy id thật từ response của " + producerLabel;
            String why = "Đường dẫn dùng tham số '" + param + "' mà giá trị thật do " + producerLabel + " tạo ra. Cần ít nhất 1 "
                    + "test case lấy id thật từ response của " + producerLabel + " (Test Data Chaining) để kiểm tra 2 endpoint "
                    + "hoạt động đúng cùng nhau - test chỉ dùng id đoán sẵn không chứng minh được điều này.";
            if (chained.isEmpty()) {
                result.add(new CoverageDimensionResponse("RELATION", label, CoverageDimensionStatus.MISSING,
                        "Chưa test case nào của endpoint này liên kết dữ liệu với test case của " + producerLabel
                                + " - mở Sửa test case, mục 'Phụ thuộc dữ liệu', bấm 'Áp dụng' ở gợi ý",
                        List.of(), null, null, why, null, List.of()));
            } else {
                List<TestCase> covering = List.copyOf(chained.values());
                result.add(new CoverageDimensionResponse("RELATION", label, CoverageDimensionStatus.COVERED, null,
                        toRefs(covering, context), null, null, why, runStatus(covering, context), List.of()));
            }
        }
        return result;
    }

    /**
     * Gộp trạng thái chạy của các case phủ 1 dòng: có case FAILED thì báo FAILED (đã kiểm và thấy lệch -
     * không được che bởi case khác PASSED); không thì có PASSED là PASSED; chỉ ERROR/BLOCKED là NO_VERDICT.
     */
    private CoverageRunStatus runStatus(List<TestCase> covering, CoverageContext context) {
        boolean anyPassed = false;
        boolean anyNoVerdict = false;
        for (TestCase tc : covering) {
            TestResultStatus latest = context.latestResultByTestCaseId().get(tc.getId());
            if (latest == null) {
                continue;
            }
            switch (latest) {
                case FAILED -> {
                    return CoverageRunStatus.FAILED;
                }
                case PASSED -> anyPassed = true;
                default -> anyNoVerdict = true;
            }
        }
        return anyPassed ? CoverageRunStatus.PASSED : anyNoVerdict ? CoverageRunStatus.NO_VERDICT : CoverageRunStatus.NOT_RUN;
    }

    private List<CoverageDimensionResponse.TestCaseRef> toRefs(List<TestCase> testCases, CoverageContext context) {
        return testCases.stream()
                .map(tc -> new CoverageDimensionResponse.TestCaseRef(tc.getId(), tc.getName(),
                        context.latestResultByTestCaseId().get(tc.getId())))
                .toList();
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

    // ---- Parse Endpoint.schema (JSON lưu lúc import) - phần Risk Engine + mã trạng thái cần ----

    /**
     * @param parsed false khi Endpoint.schema không đọc được (JSON hỏng/lỗi cấu trúc) - phân biệt với "parse
     *               được nhưng không có ràng buộc nào", để EndpointCoverageResponse.schemaReadable báo đúng,
     *               không ngầm hiểu số % là đầy đủ khi thực ra không biết gì cả.
     */
    private record SchemaFacts(Set<String> sensitiveFields, Set<String> statusCodes, boolean parsed) {
        static SchemaFacts empty() {
            return new SchemaFacts(Set.of(), Set.of(), true);
        }

        static SchemaFacts unreadable() {
            return new SchemaFacts(Set.of(), Set.of(), false);
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

        Set<String> sensitiveFields = new LinkedHashSet<>();
        if (stored.parameters != null) {
            for (Parameter p : stored.parameters) {
                addIfSensitive(p.getName(), sensitiveFields);
            }
        }
        if (stored.requestBody != null && stored.requestBody.getContent() != null) {
            for (MediaType mediaType : stored.requestBody.getContent().values()) {
                Schema<?> bodySchema = mediaType.getSchema();
                if (bodySchema != null && bodySchema.getProperties() != null) {
                    bodySchema.getProperties().keySet().forEach(name -> addIfSensitive(name, sensitiveFields));
                }
            }
        }
        Set<String> statusCodes = stored.responseStatusCodes == null ? Set.of() : new LinkedHashSet<>(stored.responseStatusCodes);
        return new SchemaFacts(sensitiveFields, statusCodes, true);
    }

    private void addIfSensitive(String name, Set<String> sensitiveFields) {
        if (name == null) {
            return;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (SENSITIVE_FIELD_KEYWORDS.stream().anyMatch(lower::contains)) {
            sensitiveFields.add(name);
        }
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
