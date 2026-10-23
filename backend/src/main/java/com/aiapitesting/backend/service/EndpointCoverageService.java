package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.request.UpdateCoverageScopeRequest;
import com.aiapitesting.backend.service.execution.MassAssignmentProbe;
import com.aiapitesting.backend.dto.response.ChecklistItemType;
import com.aiapitesting.backend.dto.response.CoverageEndpointRow;
import com.aiapitesting.backend.dto.response.CoverageOverview;
import com.aiapitesting.backend.dto.response.CoverageScope;
import com.aiapitesting.backend.dto.response.CoverageSummaryResponse;
import com.aiapitesting.backend.dto.response.CoverageDimensionResponse;
import com.aiapitesting.backend.dto.response.CoverageDimensionStatus;
import com.aiapitesting.backend.dto.response.CoverageRunStatus;
import com.aiapitesting.backend.dto.response.EndpointCoverageResponse;
import com.aiapitesting.backend.dto.response.EndpointTestState;
import com.aiapitesting.backend.dto.response.ProjectCoverageResponse;
import com.aiapitesting.backend.entity.AuthProfile;
import com.aiapitesting.backend.entity.AuthzExpectation;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.EndpointRole;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseDependency;
import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.VerdictTrust;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.AuthProfileRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.EndpointRoleRepository;
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
    private final AuthProfileRepository authProfileRepository;
    private final EndpointRoleRepository endpointRoleRepository;
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

    private static final int COVERAGE_DEFAULT_PAGE_SIZE = 50;
    private static final int COVERAGE_MAX_PAGE_SIZE = 200;
    private static final java.util.Set<String> COVERAGE_SORTS =
            java.util.Set.of("default", "path", "coverage,asc", "coverage,desc", "missing,desc");

    /** Đếm mục của 1 endpoint theo đúng các vị từ dùng cho % (loại "không áp dụng", chỉ 4 loại checklist + phân quyền). */
    private record Counts(long relevant, long covered, long executed, long failing, long missing,
                          long certain, long doubtful, long specGap) {
    }

    private Counts countsOf(List<CoverageDimensionResponse> dimensions) {
        List<CoverageDimensionResponse> counted = dimensions.stream().filter(this::countsTowardPercent).toList();
        return new Counts(
                counted.stream().filter(d -> d.status() != CoverageDimensionStatus.NOT_APPLICABLE).count(),
                counted.stream().filter(d -> d.status() == CoverageDimensionStatus.COVERED).count(),
                counted.stream().filter(this::isExecuted).count(),
                counted.stream().filter(d -> d.status() == CoverageDimensionStatus.COVERED && d.runStatus() == CoverageRunStatus.FAILED).count(),
                counted.stream().filter(d -> d.status() == CoverageDimensionStatus.MISSING).count(),
                counted.stream().filter(d -> isExecuted(d) && d.verdictTrust() == VerdictTrust.CERTAIN).count(),
                counted.stream().filter(d -> isExecuted(d) && d.verdictTrust() == VerdictTrust.DOUBTFUL).count(),
                counted.stream().filter(d -> d.status() != CoverageDimensionStatus.NOT_APPLICABLE && d.specUnderspecified()).count());
    }

    private record LightEntry(Endpoint endpoint, EndpointCoverageResponse light, CoverageEndpointRow row) {
    }

    private List<LightEntry> lightEntries(List<Endpoint> endpoints, CoverageContext context) {
        return endpoints.stream().map(endpoint -> {
            EndpointCoverageResponse light = computeForEndpoint(endpoint, context, false);
            Counts c = countsOf(light.dimensions());
            CoverageEndpointRow row = new CoverageEndpointRow(endpoint.getId(), endpoint.getPath(), endpoint.getMethod(),
                    light.requiresAuth(), light.testCaseCount(), c.relevant(), c.covered(), c.executed(), c.failing(), c.missing(),
                    light.coveragePercent(), light.executedPercent(),
                    light.testState(), c.certain(), c.doubtful(), c.specGap(), light.baselineVerified());
            return new LightEntry(endpoint, light, row);
        }).toList();
    }

    private CoverageOverview overviewOf(List<LightEntry> entries) {
        // 17q G2 - số chính của cấp project là "X/N endpoint đã kiểm thử" + phân bố trạng thái; % (nếu hiện) chỉ tính trên endpoint
        // ĐÃ SINH test - endpoint chưa sinh không bị tính là "0%" (chưa làm, không phải làm thiếu).
        Map<EndpointTestState, Integer> stateCounts = new java.util.EnumMap<>(EndpointTestState.class);
        for (EndpointTestState state : EndpointTestState.values()) {
            stateCounts.put(state, (int) entries.stream().filter(e -> e.row().testState() == state).count());
        }
        List<CoverageEndpointRow> generated = entries.stream().map(LightEntry::row)
                .filter(r -> r.testState() != EndpointTestState.NOT_GENERATED).toList();
        return new CoverageOverview(entries.size(),
                (int) entries.stream().filter(e -> e.row().missingCount() > 0).count(),
                (int) entries.stream().filter(e -> e.row().failingCount() > 0).count(),
                entries.stream().mapToLong(e -> e.row().relevantCount()).sum(),
                entries.stream().mapToLong(e -> e.row().coveredCount()).sum(),
                entries.stream().mapToLong(e -> e.row().executedCount()).sum(),
                stateCounts.get(EndpointTestState.TESTED_FAILING) + stateCounts.get(EndpointTestState.TESTED_DOUBTFUL)
                        + stateCounts.get(EndpointTestState.TESTED_OK),
                stateCounts,
                generated.stream().mapToLong(CoverageEndpointRow::relevantCount).sum(),
                generated.stream().mapToLong(CoverageEndpointRow::coveredCount).sum(),
                generated.stream().mapToLong(CoverageEndpointRow::executedCount).sum(),
                entries.stream().mapToLong(e -> e.row().certainCount()).sum(),
                entries.stream().mapToLong(e -> e.row().doubtfulCount()).sum());
    }

    /**
     * Coverage theo TRANG (Module 17 G4). Số tổng + số đếm luôn tính trên TOÀN project bằng chế độ nhẹ (cùng đường tính nên không
     * lệch); chỉ các endpoint của trang mới tính đủ chi tiết. Lọc/sắp xếp dùng số đếm của chế độ nhẹ.
     */
    public ProjectCoverageResponse getProjectCoveragePaged(UUID projectId, CoverageListQuery query) {
        Project project = projectService.getOwnedProject(projectId);
        PagingSupport.Paging paging = PagingSupport.resolve(query.page(), query.size(), COVERAGE_DEFAULT_PAGE_SIZE, COVERAGE_MAX_PAGE_SIZE);
        String sort = PagingSupport.requireAllowedSort(query.sort(), "default", COVERAGE_SORTS);
        String q = PagingSupport.normalizeQuery(query.q(), 100);
        String method = PagingSupport.normalizeMethod(query.method());
        EndpointTestState stateFilter = parseState(query.state());

        List<Endpoint> endpoints = endpointRepository.findAllByProjectOrderByCreatedAtAsc(project);
        CoverageContext context = buildContext(project, endpoints);
        List<LightEntry> all = lightEntries(endpoints, context);
        ProjectCoverageResponse overall = aggregateProject(project.getId(), all.stream().map(LightEntry::light).toList());

        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        List<LightEntry> filtered = new ArrayList<>(all.stream()
                .filter(e -> needle == null
                        || (e.row().path() != null && e.row().path().toLowerCase(Locale.ROOT).contains(needle))
                        || (e.row().method() != null && e.row().method().toLowerCase(Locale.ROOT).contains(needle)))
                .filter(e -> method == null || method.equalsIgnoreCase(e.row().method()))
                .filter(e -> !Boolean.TRUE.equals(query.onlyGaps()) || e.row().missingCount() > 0)
                .filter(e -> !Boolean.TRUE.equals(query.onlyFailing()) || e.row().failingCount() > 0)
                .filter(e -> stateFilter == null || e.row().testState() == stateFilter)
                .toList());
        java.util.Comparator<LightEntry> byPath = java.util.Comparator
                .comparing((LightEntry e) -> e.row().path() == null ? "" : e.row().path())
                .thenComparing(e -> e.row().method() == null ? "" : e.row().method());
        switch (sort) {
            case "path" -> filtered.sort(byPath);
            case "coverage,asc" -> filtered.sort(java.util.Comparator.comparingDouble((LightEntry e) -> e.row().coveragePercent()).thenComparing(byPath));
            case "coverage,desc" -> filtered.sort(java.util.Comparator.comparingDouble((LightEntry e) -> -e.row().coveragePercent()).thenComparing(byPath));
            case "missing,desc" -> filtered.sort(java.util.Comparator.comparingLong((LightEntry e) -> -e.row().missingCount()).thenComparing(byPath));
            default -> { /* giữ thứ tự tạo endpoint */ }
        }

        int from = (int) Math.min((long) paging.page() * paging.size(), filtered.size());
        int to = Math.min(from + paging.size(), filtered.size());
        List<EndpointCoverageResponse> pageEndpoints = filtered.subList(from, to).stream()
                .map(e -> computeForEndpoint(e.endpoint(), context, true))
                .toList();
        int totalPages = filtered.isEmpty() ? 0 : (int) Math.ceil((double) filtered.size() / paging.size());
        return new ProjectCoverageResponse(project.getId(), overall.overallCoveragePercent(), overall.overallExecutedPercent(),
                pageEndpoints, new ProjectCoverageResponse.PageInfo(paging.page(), paging.size(), filtered.size(), totalPages),
                overviewOf(all));
    }

    /** Coverage nhẹ của cả project: số tổng + 1 dòng tóm tắt mỗi endpoint (màn hình danh sách endpoint chỉ cần các số đếm này). */
    private static EndpointTestState parseState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return EndpointTestState.valueOf(state.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Trạng thái kiểm thử không hợp lệ: " + state);
        }
    }

    public CoverageSummaryResponse getCoverageSummary(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        List<Endpoint> endpoints = endpointRepository.findAllByProjectOrderByCreatedAtAsc(project);
        CoverageContext context = buildContext(project, endpoints);
        List<LightEntry> all = lightEntries(endpoints, context);
        ProjectCoverageResponse overall = aggregateProject(project.getId(), all.stream().map(LightEntry::light).toList());
        return new CoverageSummaryResponse(project.getId(), overall.overallCoveragePercent(), overall.overallExecutedPercent(),
                overviewOf(all), all.stream().map(LightEntry::row).toList());
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
        Map<UUID, TestResultRepository.TestCaseLatestResult> latestRowByTestCaseId = new HashMap<>();
        for (TestResultRepository.TestCaseLatestResult row : testResultRepository.findLatestResultsByProject(project)) {
            if (latestResultByTestCaseId.putIfAbsent(row.getTestCaseId(), row.getStatus()) != null) {
                continue;
            }
            latestRowByTestCaseId.put(row.getTestCaseId(), row);
            // Fail do môi trường/token/không ổn định (FLAKY), hoặc kết quả của test KHÔNG ỔN ĐỊNH (kể cả Pass - Module 16 gđ2): không
            // phải bằng chứng về API nên runStatus là NO_VERDICT. 17q G2 - dùng chung định nghĩa NO_VERDICT của VerdictTrust.
            boolean concluded = row.getStatus() == TestResultStatus.FAILED || row.getStatus() == TestResultStatus.PASSED;
            if (concluded && VerdictTrust.of(row.getStatus(), row.getFailureCause(), row.getUnstable(), true, false)
                    == VerdictTrust.NO_VERDICT) {
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

        // Module 17 (17a) - ma trận phân quyền: kỳ vọng theo (endpoint, vai trò) + thông tin vai trò.
        Map<UUID, List<EndpointRole>> rolesByEndpointId = new HashMap<>();
        for (EndpointRole role : endpointRoleRepository.findAllByEndpointProject(project)) {
            rolesByEndpointId.computeIfAbsent(role.getEndpoint().getId(), k -> new ArrayList<>()).add(role);
        }
        Map<UUID, AuthProfile> profilesById = new HashMap<>();
        authProfileRepository.findAllByProjectOrderByCreatedAtAsc(project).forEach(p -> profilesById.put(p.getId(), p));

        return new CoverageContext(testCasesByEndpointId, dependenciesByConsumerEndpointId, latestResultByTestCaseId,
                postEndpointByPath, latestNotApiFaultIds, rolesByEndpointId, profilesById, latestRowByTestCaseId);
    }

    private record CoverageContext(
            Map<UUID, List<TestCase>> testCasesByEndpointId,
            Map<UUID, List<TestCaseDependency>> dependenciesByConsumerEndpointId,
            Map<UUID, TestResultStatus> latestResultByTestCaseId,
            Map<String, Endpoint> postEndpointByPath,
            /** Module 16 - test case có lần chạy mới nhất KHÔNG phải bằng chứng chắc chắn về API: FAILED do môi trường/token, hoặc test không ổn định (kể cả Pass). */
            Set<UUID> latestNotApiFaultTestCaseIds,
            /** Module 17 (17a) - ma trận phân quyền đã khai, theo endpoint. */
            Map<UUID, List<EndpointRole>> rolesByEndpointId,
            Map<UUID, AuthProfile> profilesById,
            /** 17q G2 - lượt chạy mới nhất đầy đủ (nhãn nguyên nhân, không ổn định, đoạn trích lý do từ chối) để tính độ tin kết luận. */
            Map<UUID, TestResultRepository.TestCaseLatestResult> latestRowByTestCaseId
    ) {
    }

    /** Test case đã lưu + bản đổi sang dạng chấm được (đổi 1 lần, dùng cho mọi mục checklist). */
    private record Candidate(TestCase entity, GeneratedTestCase generated) {
    }

    // ---- Tính cho 1 endpoint ----

    private EndpointCoverageResponse computeForEndpoint(Endpoint endpoint, CoverageContext context) {
        return computeForEndpoint(endpoint, context, true);
    }

    /**
     * detail = false (Module 17 G4): chế độ NHẸ chỉ để đếm mục - chạy ĐÚNG cùng đường tính (covers, runStatus, ma trận quyền) nên
     * số liệu không thể lệch với bản đầy đủ, nhưng bỏ phần chỉ phục vụ hiển thị chi tiết (case gần đạt, thử bù, dòng mã trạng thái,
     * quan hệ, OWASP - các dòng này vốn không tính vào %).
     */
    private EndpointCoverageResponse computeForEndpoint(Endpoint endpoint, CoverageContext context, boolean detail) {
        List<TestCase> testCases = context.testCasesByEndpointId().getOrDefault(endpoint.getId(), List.of());
        SchemaFacts facts = detail ? extractSchemaFacts(endpoint) : SchemaFacts.empty();
        TestCaseChecklistPlanner.Spec spec = testCaseChecklistPlanner.readSpec(endpoint);
        List<Candidate> candidates = testCases.stream()
                .filter(tc -> tc.getSource() != com.aiapitesting.backend.entity.TestCaseSource.BUSINESS) // nghiệp vụ không tính vào EP/BVA
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
        Set<UUID> underspecifiedTestIds = new HashSet<>();
        for (TestCaseChecklistPlanner.ChecklistItem item : testCaseChecklistPlanner.planScoped(spec, endpoint.effectiveRequiresAuth(), scope, testCaseChecklistPlanner.hintsOf(endpoint))) {
            GeneratedTestCase base = item.type().name().startsWith("SECURITY_") ? securityBaseline : baseline;
            dimensions.add(checklistDimension(spec, item, candidates, endpoint.effectiveRequiresAuth(), context, base, detail));
            if (isUnderspecified(item) && item.notApplicableReason() == null) {
                candidates.stream().filter(c -> testCaseChecklistPlanner.covers(spec, item, c.generated(), endpoint.effectiveRequiresAuth()))
                        .forEach(c -> underspecifiedTestIds.add(c.entity().getId()));
            }
        }
        if (detail) {
            for (String statusCode : facts.statusCodes()) {
                dimensions.add(statusCodeDimension(statusCode, testCases, context));
            }
            dimensions.addAll(relationDimensions(endpoint, context));
        }
        // Module 17 (17a) - mục phân quyền theo vai trò từ ma trận người dùng khai; chưa khai thì giữ dòng AUTH "không áp dụng"
        // kèm hướng dẫn. DENY cần 1 test gọi bằng vai trò đó (không phụ thuộc dữ liệu hợp lệ) nên dùng case gốc lỏng như Security.
        List<CoverageDimensionResponse> authz = authzDimensions(endpoint, testCases, context, baseline != null, securityBaseline != null);
        dimensions.addAll(authz);
        if (authz.isEmpty()) {
            dimensions.add(new CoverageDimensionResponse(
                    "AUTH", "Phân quyền theo vai trò (403 Forbidden)", CoverageDimensionStatus.NOT_APPLICABLE,
                    "Chưa khai ma trận phân quyền cho endpoint này - thêm vai trò ở nút \"Vai trò\" của project rồi khai vai trò nào "
                            + "được phép/bị cấm trong \"Ma trận phân quyền\"",
                    List.of(), null, null, null, null, List.of()));
        }

        // Module 17 (17b-2) - mục OWASP mass-assignment (chỉ khi người dùng bật cho endpoint ghi). Không tính vào % coverage EP/BVA.
        CoverageDimensionResponse owasp = detail ? owaspDimension(endpoint, spec, testCases, context, baseline != null) : null;
        if (owasp != null) {
            dimensions.add(owasp);
        }

        Counts counts = countsOf(dimensions);
        double coveragePercent = counts.relevant() == 0 ? 100.0 : (counts.covered() * 100.0) / counts.relevant();
        double executedPercent = counts.relevant() == 0 ? 100.0 : (counts.executed() * 100.0) / counts.relevant();

        return new EndpointCoverageResponse(
                endpoint.getId(), endpoint.getPath(), endpoint.getMethod(), testCases.size(),
                round1(coveragePercent), round1(executedPercent), facts.parsed(), scope,
                endpoint.effectiveRequiresAuth(), endpoint.getRequiresAuthManual() != null ? "MANUAL" : "SPEC", dimensions,
                testStateOf(testCases, context, underspecifiedTestIds), counts.certain(), counts.doubtful(), counts.specGap(),
                baselineVerified(testCases));
    }

    // ---- 17q G2 - trạng thái kiểm thử của endpoint + độ tin kết luận ----

    /**
     * Trạng thái tính theo TEST (không theo mục) để bản nhẹ và bản đầy đủ không lệch nhau; test nghiệp vụ GIẢ ĐỊNH không tính. Chỉ Fail
     * kết luận CHẮC mới làm endpoint "Đang có Fail" (đỏ); Fail NGHI (nguyên nhân chưa rõ, kỳ vọng do AI viết, mục biên ngầm, xung đột
     * trạng thái...) là "Có Fail cần xem" - không bị che, nhưng không tô đỏ như lỗi API chắc chắn.
     */
    private EndpointTestState testStateOf(List<TestCase> testCases, CoverageContext context, Set<UUID> underspecifiedTestIds) {
        List<TestCase> own = testCases.stream()
                .filter(tc -> tc.getSource() != com.aiapitesting.backend.entity.TestCaseSource.BUSINESS)
                .toList();
        if (own.isEmpty()) {
            return EndpointTestState.NOT_GENERATED;
        }
        boolean concluded = false;
        boolean doubtfulFail = false;
        for (TestCase tc : own) {
            TestResultRepository.TestCaseLatestResult row = context.latestRowByTestCaseId().get(tc.getId());
            if (row == null || (row.getStatus() != TestResultStatus.PASSED && row.getStatus() != TestResultStatus.FAILED)) {
                continue;
            }
            VerdictTrust trust = VerdictTrust.of(row.getStatus(), row.getFailureCause(), row.getUnstable(), true,
                    isAssumedOracle(tc) || underspecifiedTestIds.contains(tc.getId()));
            if (trust == VerdictTrust.NO_VERDICT) {
                continue;
            }
            concluded = true;
            if (row.getStatus() == TestResultStatus.FAILED) {
                if (trust == VerdictTrust.CERTAIN) {
                    return EndpointTestState.TESTED_FAILING;
                }
                doubtfulFail = true;
            }
        }
        return doubtfulFail ? EndpointTestState.TESTED_DOUBTFUL : concluded ? EndpointTestState.TESTED_OK : EndpointTestState.NOT_RUN;
    }

    /** Có test gốc AI đã được chạy thật xác nhận và khoá làm nền (BaselineVerificationService chỉ khoá khi test gốc Pass). */
    private boolean baselineVerified(List<TestCase> testCases) {
        return testCases.stream().anyMatch(tc -> tc.getGenerationOrigin() == TestCaseGenerationOrigin.AI_BASELINE && tc.isLocked());
    }

    /**
     * Mục "biên ngầm": spec không khai ràng buộc nên kỳ vọng 2xx chỉ là quy ước (closed-world), không phải đặc tả. Chỉ mục kỳ vọng
     * THÀNH CÔNG - cùng loại mục ở tham số path định danh tài nguyên kỳ vọng 404 (id không tồn tại), đó là oracle thật, không phải biên ngầm.
     */
    static boolean isUnderspecified(TestCaseChecklistPlanner.ChecklistItem item) {
        return item != null && item.expectSuccess() && (item.type() == ChecklistItemType.BOUNDARY_ZERO
                || item.type() == ChecklistItemType.BOUNDARY_NEGATIVE_NUMBER
                || item.type() == ChecklistItemType.BOUNDARY_EMPTY_STRING);
    }

    /**
     * Độ tin của 1 dòng đã chạy: dòng FAILED → CHẮC nếu có ít nhất 1 test Fail ở mức CHẮC; dòng PASSED → CHẮC nếu có ít nhất 1 test Pass
     * ở mức CHẮC; ngược lại NGHI. Dòng chưa ra kết luận (chưa chạy/NO_VERDICT) → null.
     */
    private VerdictTrust dimensionTrust(CoverageRunStatus run, List<TestCase> covering, CoverageContext context,
                                        TestCaseChecklistPlanner.ChecklistItem item) {
        if (run != CoverageRunStatus.PASSED && run != CoverageRunStatus.FAILED) {
            return null;
        }
        TestResultStatus wanted = run == CoverageRunStatus.FAILED ? TestResultStatus.FAILED : TestResultStatus.PASSED;
        for (TestCase tc : covering) {
            TestResultRepository.TestCaseLatestResult row = context.latestRowByTestCaseId().get(tc.getId());
            if (row != null && row.getStatus() == wanted && testTrust(tc, row, item) == VerdictTrust.CERTAIN) {
                return VerdictTrust.CERTAIN;
            }
        }
        return VerdictTrust.DOUBTFUL;
    }

    private VerdictTrust testTrust(TestCase tc, TestResultRepository.TestCaseLatestResult row,
                                   TestCaseChecklistPlanner.ChecklistItem item) {
        boolean assumed = isAssumedOracle(tc) || isUnderspecified(item);
        // Pass nhưng mã thành công lệch spec (G1): chưa phải bằng chứng "đúng" - chỉ NGHI (kiểm định 2026-10-23).
        boolean strong = strongPassOracle(tc, row, item) && !Boolean.TRUE.equals(row.getSpecDeviation());
        return VerdictTrust.of(row.getStatus(), row.getFailureCause(), row.getUnstable(), strong, assumed);
    }

    /**
     * Kỳ vọng do AI viết (AI_CHECKLIST/AI_EXTRA/AI_BUSINESS) hoặc bị đánh dấu "kỳ vọng cần xem": Fail tối đa NGHI. Nhãn "kỳ vọng cần xem"
     * không áp dụng cho test hệ thống bù (sinh từ chính spec) - khớp với giao diện và bộ lọc.
     */
    public static boolean isAssumedOracle(TestCase tc) {
        TestCaseGenerationOrigin origin = tc.getGenerationOrigin();
        return origin == TestCaseGenerationOrigin.AI_CHECKLIST || origin == TestCaseGenerationOrigin.AI_EXTRA
                || origin == TestCaseGenerationOrigin.AI_BUSINESS
                || (tc.getReliabilityStatus() == com.aiapitesting.backend.entity.TestCaseReliability.NEEDS_REVIEW
                    && origin != TestCaseGenerationOrigin.SYSTEM_FILLED);
    }

    /** Test hệ thống bù cho mục "biên ngầm" kỳ vọng thành công (dựa vào checklistType đã lưu) - dùng ở trang kết quả chạy. */
    public static boolean isUnderspecifiedTest(TestCase tc) {
        String type = tc.getChecklistType();
        return type != null && tc.getExpectedStatus() != null && tc.getExpectedStatus() < 400
                && (type.equals(ChecklistItemType.BOUNDARY_ZERO.name()) || type.equals(ChecklistItemType.BOUNDARY_NEGATIVE_NUMBER.name())
                    || type.equals(ChecklistItemType.BOUNDARY_EMPTY_STRING.name()));
    }

    /**
     * Pass có oracle mạnh khi: test kỳ vọng thành công (oracle là mã 2xx), hoặc mục bảo mật/phân quyền (401/403 chính là oracle), hoặc
     * test âm mà lý do API từ chối nhắc ĐÚNG field đang kiểm. Test âm Pass vì bị từ chối do lý do khác (trùng hợp) chỉ là NGHI.
     */
    private static boolean strongPassOracle(TestCase tc, TestResultRepository.TestCaseLatestResult row,
                                            TestCaseChecklistPlanner.ChecklistItem item) {
        // Chỉ "từ chối đầu vào" (400/422) mới có thể trùng hợp vì lỗi KHÁC; mã riêng (401/403/404/409...) tự nó là oracle.
        if (!isInputRejectionStatus(tc.getExpectedStatus()) || item == null) {
            return true;
        }
        if (item.type().name().startsWith("SECURITY_") || item.field() == null || item.field().isBlank()) {
            return true;
        }
        return mentionsField(row.getRejectionExcerpt(), item.field());
    }

    static boolean isInputRejectionStatus(Integer status) {
        return status != null && (status == 400 || status == 422);
    }

    /** So khớp tên field trong đoạn trích: bỏ dấu + chữ thường, chấp nhận dạng camelCase/snake_case/kebab-case và có khoảng trắng. */
    static boolean mentionsField(String excerpt, String field) {
        if (excerpt == null || excerpt.isBlank() || field == null || field.isBlank()) {
            return false;
        }
        String leaf = field.contains(".") ? field.substring(field.lastIndexOf('.') + 1) : field;
        leaf = leaf.replaceAll("\\[\\d*]", "");
        String text = foldLetters(excerpt);
        String spaced = foldLetters(leaf.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').replace('-', ' '));
        String joined = spaced.replace(" ", "");
        return Pattern.compile("(^|[^a-z0-9])(" + Pattern.quote(joined) + "|" + Pattern.quote(spaced) + ")([^a-z0-9]|$)")
                .matcher(text.replace('_', ' ').replace('-', ' ')).find()
                || Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(joined) + "([^a-z0-9]|$)").matcher(text.replace("_", "").replace("-", "")).find();
    }

    private static String foldLetters(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
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
        if (request.owasp() != null) {
            endpoint.setScopeOwasp(request.owasp()); // opt-in có chủ đích: chỉ đổi khi client gửi rõ
        }
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
            List<Candidate> candidates, boolean requiresAuth, CoverageContext context, GeneratedTestCase baseline,
            boolean withDetail
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
        boolean underspecified = isUnderspecified(item);
        if (!covering.isEmpty()) {
            CoverageRunStatus run = runStatus(covering, context);
            return new CoverageDimensionResponse(category, item.description(), CoverageDimensionStatus.COVERED, null,
                    withDetail ? toRefs(covering, context) : List.of(), item.type().name(), key, item.rationale(),
                    run, List.of())
                    .withTrust(dimensionTrust(run, covering, context, item), underspecified);
        }
        if (!withDetail) {
            return new CoverageDimensionResponse(category, item.description(), CoverageDimensionStatus.MISSING, null,
                    List.of(), item.type().name(), key, item.rationale(), null, List.of())
                    .withTrust(null, underspecified);
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
                attempt.filled() != null, attempt.blockedReason(), null, underspecified);
    }

    /** Tiền tố tên ChecklistItemType chính là loại test case (POSITIVE/NEGATIVE/BOUNDARY/SECURITY). */
    private String categoryOf(ChecklistItemType type) {
        String name = type.name();
        return name.substring(0, name.indexOf('_'));
    }

    /**
     * Module 17 (17a) - mỗi ô (endpoint, vai trò) trong ma trận là 1 mục: COVERED khi có test case gọi bằng đúng profile của vai trò,
     * không cố ý bỏ/sai token, kỳ vọng đúng chiều (ALLOW → 2xx, DENY → 403/401). Thiếu thì kèm cờ {@code fillable} (có case gốc để
     * mượn dữ liệu không) để nút Bổ sung chỉ sáng khi bù được.
     */
    static final String OWASP_CATEGORY = "OWASP";
    static final String OWASP_KEY = "OWASP_MASS_ASSIGNMENT";

    /** Chỉ endpoint ghi (POST/PUT/PATCH) có body dạng field đã bật cờ OWASP mới có mục này. */
    private CoverageDimensionResponse owaspDimension(
            Endpoint endpoint, TestCaseChecklistPlanner.Spec spec, List<TestCase> testCases, CoverageContext context, boolean usableBaseline
    ) {
        if (!Boolean.TRUE.equals(endpoint.getScopeOwasp()) || !MassAssignmentFields.isWriteMethod(endpoint.getMethod())) {
            return null;
        }
        String label = "Mass assignment: gửi field đặc quyền không khai báo (role, isAdmin, status, price...) - API phải bỏ qua hoặc từ chối";
        String why = "Phát hiện lỗi OWASP API3/API6: API tự gán field người dùng không được phép đặt (leo quyền, đổi giá/chủ sở hữu). "
                + "Probe gửi request GHI thật tới hệ thống đích - chỉ dùng với môi trường test.";
        List<TestCase> covering = testCases.stream().filter(tc -> MassAssignmentProbe.KIND.equals(tc.getProbeKind())).toList();
        if (!covering.isEmpty()) {
            return new CoverageDimensionResponse(OWASP_CATEGORY, label, CoverageDimensionStatus.COVERED, null,
                    toRefs(covering, context), MassAssignmentProbe.KIND, OWASP_KEY, why, runStatus(covering, context), List.of());
        }
        boolean hasFields = spec.hasBody() && spec.bodyIsJson();
        boolean fillable = usableBaseline && hasFields;
        return new CoverageDimensionResponse(OWASP_CATEGORY, label, CoverageDimensionStatus.MISSING,
                "Chưa có test probe mass-assignment cho endpoint này", List.of(), MassAssignmentProbe.KIND, OWASP_KEY, why, null,
                List.of(), fillable,
                fillable ? null : hasFields ? "Cần ít nhất 1 test case hợp lệ (kỳ vọng thành công) làm gốc để mượn body"
                        : "Endpoint không có body dạng field (JSON/form) để tiêm field lạ");
    }

    private List<CoverageDimensionResponse> authzDimensions(
            Endpoint endpoint, List<TestCase> testCases, CoverageContext context, boolean usableBaseline, boolean anyBaseline
    ) {
        List<CoverageDimensionResponse> result = new ArrayList<>();
        for (EndpointRole rule : context.rolesByEndpointId().getOrDefault(endpoint.getId(), List.of())) {
            AuthProfile profile = context.profilesById().get(rule.getAuthProfileId());
            if (profile == null) {
                continue; // vai trò đã bị xoá
            }
            AuthzExpectation expectation = rule.getExpectation();
            String label = AuthzMatrix.label(expectation, profile.getName());
            String key = AuthzMatrix.key(expectation, profile.getRoleKey());
            String why = AuthzMatrix.why(expectation, profile.getName());
            List<TestCase> covering = testCases.stream()
                    .filter(tc -> AuthzMatrix.covers(expectation, profile.getId(), tc))
                    .toList();
            if (!covering.isEmpty()) {
                CoverageRunStatus run = runStatus(covering, context);
                result.add(new CoverageDimensionResponse(AuthzMatrix.CATEGORY, label, CoverageDimensionStatus.COVERED, null,
                        toRefs(covering, context), AuthzMatrix.type(expectation), key, why, run, List.of())
                        .withTrust(dimensionTrust(run, covering, context, null), false));
                continue;
            }
            boolean fillable = expectation == AuthzExpectation.ALLOW ? usableBaseline : anyBaseline;
            result.add(new CoverageDimensionResponse(AuthzMatrix.CATEGORY, label, CoverageDimensionStatus.MISSING,
                    "Chưa có test case nào gọi bằng vai trò '" + profile.getName() + "' với kỳ vọng "
                            + (expectation == AuthzExpectation.ALLOW ? "2xx" : "403/401"),
                    List.of(), AuthzMatrix.type(expectation), key, why, null, List.of(), fillable,
                    fillable ? null : "Cần ít nhất 1 test case làm gốc (kỳ vọng thành công) để mượn dữ liệu gọi endpoint này"));
        }
        return result;
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
        // Module 17 (1.4) - mã THÀNH CÔNG (2xx) so theo NHÓM 2xx: spec thường chỉ khai 200 trong khi API thật trả 201 (POST tạo)
        // hoặc 204 (DELETE) - đo thật: 110 dòng "200" không dòng nào thoả được bằng test đúng. Mã lỗi (4xx/5xx) vẫn so đúng mã
        // vì mỗi mã lỗi là 1 hành vi khác nhau. Lệch mã trong cùng nhóm 2xx vẫn được báo ở kết quả chạy (EXPECTATION_MISMATCH).
        boolean successGroup = exact && statusCode.startsWith("2");
        List<TestCase> matches = testCases.stream()
                .filter(tc -> tc.getExpectedStatus() != null && (successGroup
                        ? tc.getExpectedStatus() / 100 == 2
                        : exact
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
