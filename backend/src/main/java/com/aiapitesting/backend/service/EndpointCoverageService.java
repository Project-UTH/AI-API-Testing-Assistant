package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.request.UpdateCoverageScopeRequest;
import com.aiapitesting.backend.dto.response.ChecklistItemType;
import com.aiapitesting.backend.dto.response.CoverageScope;
import com.aiapitesting.backend.dto.response.CoverageDimensionResponse;
import com.aiapitesting.backend.dto.response.CoverageDimensionStatus;
import com.aiapitesting.backend.dto.response.CoverageRunStatus;
import com.aiapitesting.backend.dto.response.EndpointCoverageResponse;
import com.aiapitesting.backend.dto.response.ProjectCoverageResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseDependency;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * Module 15c - Coverage Matrix. TẤT ĐỊNH TUYỆT ĐỐI: mọi giá trị trả về tính từ
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
        // Vẫn load context của CẢ project (không chỉ 1 endpoint) - dòng RELATION của 1
        // endpoint phụ thuộc vào test case/endpoint KHÁC trong cùng project.
        List<Endpoint> allEndpoints = endpointRepository.findAllByProjectOrderByCreatedAtAsc(project);
        CoverageContext context = buildContext(project, allEndpoints);
        return computeForEndpoint(endpoint, context);
    }

    private ProjectCoverageResponse aggregateProject(UUID projectId, List<EndpointCoverageResponse> endpoints) {
        long coveredTotal = 0;
        long executedTotal = 0;
        long dimensionTotal = 0;
        // Gộp theo MỤC (không trung bình % từng endpoint) và mỗi endpoint chỉ góp các mục trong phạm vi của nó.
        for (EndpointCoverageResponse e : endpoints) {
            List<CoverageDimensionResponse> counted = e.dimensions().stream().filter(this::countsTowardPercent).toList();
            coveredTotal += counted.stream().filter(d -> d.status() == CoverageDimensionStatus.COVERED).count();
            executedTotal += counted.stream().filter(this::isExecuted).count();
            dimensionTotal += counted.stream().filter(d -> d.status() != CoverageDimensionStatus.NOT_APPLICABLE).count();
        }
        double overallCoveragePercent = dimensionTotal == 0 ? 100.0 : (coveredTotal * 100.0) / dimensionTotal;
        double overallExecutedPercent = dimensionTotal == 0 ? 100.0 : (executedTotal * 100.0) / dimensionTotal;
        return new ProjectCoverageResponse(projectId, round1(overallCoveragePercent), round1(overallExecutedPercent),
                endpoints);
    }

    /**
     * Chỉ 4 loại checklist EP/BVA (đã lọc theo phạm vi) tính vào %. STATUS_CODE/RELATION là độ phủ theo spec/luồng
     * dữ liệu, không thuộc loại test nào - tính chung sẽ khiến người chỉ chọn Positive luôn "thiếu" mã 4xx/401/404.
     */
    private boolean countsTowardPercent(CoverageDimensionResponse d) {
        return CoverageScope.isChecklistCategory(d.category());
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

        Map<UUID, List<TestCaseDependency>> dependenciesByConsumerEndpointId = new LinkedHashMap<>();
        for (TestCaseDependency dep : testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)) {
            UUID consumerEndpointId = dep.getTestCase().getEndpoint().getId();
            dependenciesByConsumerEndpointId.computeIfAbsent(consumerEndpointId, k -> new ArrayList<>()).add(dep);
        }

        // Module 15j - lượt chạy MỚI NHẤT của từng test case (trạng thái chạy của dòng coverage).
        Map<UUID, TestResultStatus> latestResultByTestCaseId = new HashMap<>();
        Set<UUID> latestNotApiFaultIds = new HashSet<>();
        for (TestResultRepository.TestCaseLatestResult row : testResultRepository.findLatestResultsByProject(project)) {
            if (latestResultByTestCaseId.putIfAbsent(row.getTestCaseId(), row.getStatus()) != null) {
                continue;
            }
            // Fail do môi trường/token (không nói gì về API) hoặc kết quả của test KHÔNG ỔN ĐỊNH (kể cả Pass - Module 16
            // gđ2): đều không phải bằng chứng chắc chắn nên runStatus là NO_VERDICT, không phải PASSED/FAILED.
            boolean notApiFault = row.getStatus() == TestResultStatus.FAILED
                    && row.getFailureCause() != null && row.getFailureCause().isNotApiFault();
            if (notApiFault || Boolean.TRUE.equals(row.getUnstable())) {
                latestNotApiFaultIds.add(row.getTestCaseId());
            }
        }

        // Module 15j - endpoint POST theo path, để dò "POST tạo resource → endpoint dùng {id}" (dòng RELATION).
        Map<String, Endpoint> postEndpointByPath = new HashMap<>();
        for (Endpoint e : endpoints) {
            if ("POST".equalsIgnoreCase(e.getMethod()) && e.getPath() != null) {
                postEndpointByPath.putIfAbsent(e.getPath(), e);
            }
        }

        return new CoverageContext(testCasesByEndpointId, dependenciesByConsumerEndpointId, latestResultByTestCaseId,
                postEndpointByPath, latestNotApiFaultIds);
    }

    private record CoverageContext(
            Map<UUID, List<TestCase>> testCasesByEndpointId,
            Map<UUID, List<TestCaseDependency>> dependenciesByConsumerEndpointId,
            Map<UUID, TestResultStatus> latestResultByTestCaseId,
            Map<String, Endpoint> postEndpointByPath,
            /** Module 16 - test case có lần chạy mới nhất KHÔNG phải bằng chứng chắc chắn về API: FAILED do môi trường/token, hoặc test không ổn định (kể cả Pass). */
            Set<UUID> latestNotApiFaultTestCaseIds
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
                .filter(g -> testCaseChecklistPlanner.isUsableBaseline(spec, g, endpoint.effectiveRequiresAuth()))
                .findFirst().orElse(null);
        GeneratedTestCase securityBaseline = baseline != null ? baseline
                : candidates.isEmpty() ? null : candidates.get(0).generated();

        CoverageScope scope = CoverageScope.of(endpoint);
        List<CoverageDimensionResponse> dimensions = new ArrayList<>();
        for (TestCaseChecklistPlanner.ChecklistItem item : testCaseChecklistPlanner.planScoped(spec, endpoint.effectiveRequiresAuth(), scope)) {
            GeneratedTestCase base = item.type().name().startsWith("SECURITY_") ? securityBaseline : baseline;
            dimensions.add(checklistDimension(spec, item, candidates, endpoint.effectiveRequiresAuth(), context, base));
        }
        for (String statusCode : facts.statusCodes()) {
            dimensions.add(statusCodeDimension(statusCode, testCases, context));
        }
        dimensions.addAll(relationDimensions(endpoint, context));
        dimensions.add(new CoverageDimensionResponse(
                "AUTH", "Phân quyền (403 Forbidden)", CoverageDimensionStatus.NOT_APPLICABLE,
                "Không áp dụng - hệ thống chưa có mô hình vai trò/scope của target API để giả lập "
                        + "'có đăng nhập nhưng không đủ quyền'",
                List.of(), null, null, null, null, List.of()));

        List<CoverageDimensionResponse> counted = dimensions.stream().filter(this::countsTowardPercent).toList();
        long covered = counted.stream().filter(d -> d.status() == CoverageDimensionStatus.COVERED).count();
        long executed = counted.stream().filter(this::isExecuted).count();
        long relevant = counted.stream().filter(d -> d.status() != CoverageDimensionStatus.NOT_APPLICABLE).count();
        double coveragePercent = relevant == 0 ? 100.0 : (covered * 100.0) / relevant;
        double executedPercent = relevant == 0 ? 100.0 : (executed * 100.0) / relevant;

        return new EndpointCoverageResponse(
                endpoint.getId(), endpoint.getPath(), endpoint.getMethod(), testCases.size(),
                round1(coveragePercent), round1(executedPercent), facts.parsed(), scope,
                endpoint.effectiveRequiresAuth(), endpoint.getRequiresAuthManual() != null ? "MANUAL" : "SPEC", dimensions);
    }

    /**
     * Chỉnh phạm vi đo của 1 endpoint ở trang Coverage. Không xoá test case nào: thu hẹp thì case ngoài phạm vi
     * vẫn còn (chỉ không được tính), mở rộng thì các mục mới hiện là còn thiếu.
     */
    public EndpointCoverageResponse updateScope(UUID projectId, UUID endpointId, UpdateCoverageScopeRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        Endpoint endpoint = endpointRepository.findByIdAndProject(endpointId, project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));
        if (request == null || !(request.positive() || request.negative() || request.boundary() || request.security())) {
            throw new InvalidRequestException("Phải chọn ít nhất 1 loại test case để đo độ bao phủ");
        }
        endpoint.setScopePositive(request.positive());
        endpoint.setScopeNegative(request.negative());
        endpoint.setScopeBoundary(request.boundary());
        endpoint.setScopeSecurity(request.security());
        endpointRepository.save(endpoint);
        return getEndpointCoverage(projectId, endpointId);
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
                    // Module 16 - Fail do môi trường/token/cấu hình test = "chạy chưa ra kết luận về API", không phải
                    // "đã kiểm và thấy API lệch kỳ vọng".
                    if (context.latestNotApiFaultTestCaseIds().contains(tc.getId())) {
                        anyNoVerdict = true;
                    } else {
                        return CoverageRunStatus.FAILED;
                    }
                }
                // 1 lần Pass của test không ổn định (Module 16 gđ2) cũng không phải bằng chứng chắc chắn.
                case PASSED -> {
                    if (context.latestNotApiFaultTestCaseIds().contains(tc.getId())) {
                        anyNoVerdict = true;
                    } else {
                        anyPassed = true;
                    }
                }
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

    // ---- Parse Endpoint.schema (JSON lưu lúc import) - lấy các mã trạng thái phản hồi khai báo trong spec ----

    /**
     * @param parsed false khi Endpoint.schema không đọc được (JSON hỏng/lỗi cấu trúc) - phân biệt với "parse
     *               được nhưng không có ràng buộc nào", để EndpointCoverageResponse.schemaReadable báo đúng,
     *               không ngầm hiểu số % là đầy đủ khi thực ra không biết gì cả.
     */
    private record SchemaFacts(Set<String> statusCodes, boolean parsed) {
        static SchemaFacts empty() {
            return new SchemaFacts(Set.of(), true);
        }

        static SchemaFacts unreadable() {
            return new SchemaFacts(Set.of(), false);
        }
    }

    /** Khớp đúng shape đã lưu bởi {@code EndpointImportService.buildSchemaJson()} - chỉ đọc phần mã trạng thái phản hồi. */
    private static class StoredSchema {
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
        Set<String> statusCodes = stored.responseStatusCodes == null ? Set.of() : new LinkedHashSet<>(stored.responseStatusCodes);
        return new SchemaFacts(statusCodes, true);
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
