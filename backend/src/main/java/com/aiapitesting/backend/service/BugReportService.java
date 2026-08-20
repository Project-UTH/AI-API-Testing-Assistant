package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.CreateBugReportRequest;
import com.aiapitesting.backend.dto.request.UpdateBugReportRequest;
import com.aiapitesting.backend.dto.response.BugDashboardSummaryResponse;
import com.aiapitesting.backend.dto.response.BugReportDraftResponse;
import com.aiapitesting.backend.dto.response.BugReportPageResponse;
import com.aiapitesting.backend.dto.response.BugReportResponse;
import com.aiapitesting.backend.dto.response.AssertionResultResponse;
import com.aiapitesting.backend.dto.response.ComponentBugCountResponse;
import com.aiapitesting.backend.dto.response.ComponentRefResponse;
import com.aiapitesting.backend.dto.response.EndpointBugSummaryResponse;
import com.aiapitesting.backend.dto.response.TestCaseBugSummaryResponse;
import com.aiapitesting.backend.dto.response.TestResultHistoryItemResponse;
import com.aiapitesting.backend.entity.AssertionOperator;
import com.aiapitesting.backend.entity.BugReport;
import com.aiapitesting.backend.entity.BugReportEvent;
import com.aiapitesting.backend.entity.BugReportEventType;
import com.aiapitesting.backend.entity.BugStatus;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.BugReportNotFoundException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.TestCaseNotFoundException;
import com.aiapitesting.backend.exception.TestResultNotFoundException;
import com.aiapitesting.backend.repository.BugReportEventRepository;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.ProjectRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD + đọc-tổng hợp Bug Report (Module 10) - đặt ở service/ gốc giống TestHistoryService (không
 * phải logic AI, không phải engine thực thi). Quy tắc tự động chuyển trạng thái theo lần chạy mới
 * nằm ở BugReportStatusService riêng, KHÔNG ở đây.
 */
@Service
@RequiredArgsConstructor
public class BugReportService {

    private final ProjectService projectService;
    private final ProjectRepository projectRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final BugReportRepository bugReportRepository;
    private final BugReportEventRepository bugReportEventRepository;
    private final CurrentUserService currentUserService;
    private final TestCasePathValidator testCasePathValidator;
    private final BugReportExportService bugReportExportService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Kết quả xuất file - không đi qua envelope {data,meta} chuẩn vì đây là binary, không phải JSON. */
    public record ExportFile(String filename, byte[] content) {
    }

    public BugReportPageResponse getBugReports(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        // Nguồn Tầng 1+2 là TOÀN BỘ test case của project (giống TestCaseService.listByProject) -
        // không lọc riêng test case đang có bug, để trang này duyệt được hết mọi test case/endpoint
        // giống cách trang Lịch sử liệt kê hết, chỉ khác ở chỗ có thêm Tầng 3 (lịch sử chạy riêng
        // từng test case) và badge bug (nếu có) trên mỗi dòng. Dashboard tổng hợp vẫn chỉ tính trên
        // bug thật (không tính test case chưa có bug).
        List<TestCase> allTestCases = testCaseRepository.findAllByEndpointProject(project);
        List<BugReport> bugs = bugReportRepository.findAllByProject(project);
        Map<UUID, List<BugReport>> bugsByTestCaseId = bugs.stream()
                .collect(Collectors.groupingBy(b -> b.getTestCase().getId()));
        Set<UUID> testCaseIdsWithRuns = new HashSet<>(
                testResultRepository.findDistinctTestCaseIdsWithResultsByProject(project));

        // Gộp theo endpoint -> test case trong bộ nhớ từ 1 query đã tải sẵn (đúng pattern
        // TestHistoryService/HistoryFeedService) - không N+1 theo từng endpoint/test case.
        Map<UUID, Endpoint> endpointById = new LinkedHashMap<>();
        Map<UUID, List<TestCase>> testCasesByEndpointId = new LinkedHashMap<>();
        for (TestCase testCase : allTestCases) {
            Endpoint endpoint = testCase.getEndpoint();
            endpointById.putIfAbsent(endpoint.getId(), endpoint);
            testCasesByEndpointId.computeIfAbsent(endpoint.getId(), k -> new ArrayList<>()).add(testCase);
        }

        List<EndpointBugSummaryResponse> endpoints = new ArrayList<>();
        for (Map.Entry<UUID, List<TestCase>> endpointEntry : testCasesByEndpointId.entrySet()) {
            Endpoint endpoint = endpointById.get(endpointEntry.getKey());
            // Chỉ hiện test case ĐÃ TỪNG CHẠY ít nhất 1 lần (theo yêu cầu người dùng) - test case
            // chưa chạy lần nào không có gì để xem ở Tầng 3 nên ẩn hẳn thay vì hiện rồi disable.
            List<TestCaseBugSummaryResponse> testCases = endpointEntry.getValue().stream()
                    .filter(testCase -> testCaseIdsWithRuns.contains(testCase.getId()))
                    .map(testCase -> new TestCaseBugSummaryResponse(
                            testCase.getId(), testCase.getName(),
                            bugsByTestCaseId.getOrDefault(testCase.getId(), List.of()).stream()
                                    .map(BugReportResponse::from).toList()))
                    .toList();
            if (testCases.isEmpty()) {
                continue; // Endpoint không có test case nào đã chạy - không hiện trong Tầng 1.
            }
            endpoints.add(new EndpointBugSummaryResponse(
                    endpoint.getId(), endpoint.getMethod(), endpoint.getPath(), endpoint.getSummary(), testCases));
        }

        return new BugReportPageResponse(buildDashboardSummary(bugs), endpoints);
    }

    private BugDashboardSummaryResponse buildDashboardSummary(List<BugReport> bugs) {
        Map<com.aiapitesting.backend.entity.BugPriority, Long> countByPriority = bugs.stream()
                .collect(Collectors.groupingBy(BugReport::getPriority, Collectors.counting()));

        Map<UUID, List<BugReport>> bugsByEndpointId = bugs.stream()
                .collect(Collectors.groupingBy(b -> b.getEndpoint().getId(), LinkedHashMap::new, Collectors.toList()));
        List<ComponentBugCountResponse> countByComponent = bugsByEndpointId.values().stream()
                .map(list -> {
                    Endpoint endpoint = list.get(0).getEndpoint();
                    return new ComponentBugCountResponse(endpoint.getId(), endpoint.getMethod(), endpoint.getPath(), list.size());
                })
                .toList();

        long totalCount = bugs.size();
        long closedCount = bugs.stream().filter(b -> b.getStatus() == BugStatus.CLOSED).count();
        long openCount = totalCount - closedCount;

        return new BugDashboardSummaryResponse(countByPriority, countByComponent, openCount, closedCount, totalCount);
    }

    public List<TestResultHistoryItemResponse> getRunHistory(UUID projectId, UUID testCaseId) {
        Project project = projectService.getOwnedProject(projectId);
        TestCase testCase = testCaseRepository.findByIdAndEndpointProject(testCaseId, project)
                .orElseThrow(() -> new TestCaseNotFoundException("Không tìm thấy test case với id đã cho"));
        return testResultRepository.findAllByTestCaseOrderByExecutionStartedAtAsc(testCase).stream()
                .map(TestResultHistoryItemResponse::from)
                .toList();
    }

    public BugReportDraftResponse getDraft(UUID projectId, UUID testResultId) {
        Project project = projectService.getOwnedProject(projectId);
        TestResult result = getOwnedFailedTestResult(project, testResultId);
        TestCase testCase = result.getTestCase();
        Endpoint endpoint = testCase.getEndpoint();

        return new BugReportDraftResponse(
                testCase.getId(), testCase.getName(), result.getId(),
                ComponentRefResponse.from(endpoint),
                "Fail: " + testCase.getName(),
                buildDescription(project, endpoint, testCase, result),
                null, null,
                project.getName());
    }

    /**
     * Gộp Test Environment/Steps to Reproduce/Actual Result/Expected Result thành 1 "Mô tả" duy
     * nhất (theo yêu cầu người dùng, khớp mẫu QA thực tế) - nhúng thẳng response body thật đã ghi
     * nhận lúc chạy để đủ chi tiết, không chỉ tóm tắt status code. Nếu test case có assertion
     * (Module 9b), liệt kê từng assertion kèm giá trị kỳ vọng/thực tế - đây là dữ liệu chi tiết
     * nhất hệ thống có ngoài status code, không có "expected body" nào khác để thêm vào nếu test
     * case không có assertion. Trả về qua field BugReportDraftResponse.stepsToReproduce (không đổi
     * tên field DTO/entity để tránh phải sửa schema, chỉ đổi CÁCH DÙNG - frontend hiện field này
     * dưới nhãn "Mô tả").
     */
    private String buildDescription(Project project, Endpoint endpoint, TestCase testCase, TestResult result) {
        Map<String, String> fallbacks = parseJsonMapSafe(testCase.getPathParamFallbacks());
        String resolvedPath = testCasePathValidator.substitute(testCase.getResolvedPath(), fallbacks);
        String path = (resolvedPath == null || resolvedPath.isBlank()) ? endpoint.getPath() : resolvedPath;
        List<AssertionResultResponse> assertions = parseAssertions(result.getAssertionResultsJson());

        StringBuilder sb = new StringBuilder();
        sb.append("Môi trường kiểm thử:\n");
        sb.append("- Base URL: ").append(blankToPlaceholder(project.getTargetBaseUrl())).append("\n");
        sb.append("- Endpoint: ").append(endpoint.getMethod()).append(" ").append(endpoint.getPath()).append("\n\n");

        sb.append("Các bước tái hiện:\n");
        sb.append("1. Gửi ").append(endpoint.getMethod()).append(" ").append(path).append("\n");
        sb.append("2. Đợi response trả về.\n");
        sb.append("3. Kiểm tra status code và nội dung response.\n\n");

        sb.append("Kết quả mong đợi:\n");
        sb.append("Status code: ").append(testCase.getExpectedStatus());
        for (AssertionResultResponse a : assertions) {
            sb.append("\n- ").append(a.jsonPath()).append(" ").append(a.operator());
            if (a.operator() != AssertionOperator.EXISTS && a.expectedValue() != null) {
                sb.append(" \"").append(a.expectedValue()).append("\"");
            }
        }
        sb.append("\n\n");

        sb.append("Kết quả thực tế:\n");
        sb.append("Status code: ")
                .append(result.getResponseStatus() == null ? "không xác định" : result.getResponseStatus());
        for (AssertionResultResponse a : assertions) {
            sb.append("\n- ").append(a.jsonPath()).append(" → thực tế \"")
                    .append(a.actualValue() == null ? "—" : a.actualValue())
                    .append("\" (").append(a.passed() ? "ĐẠT" : "KHÔNG ĐẠT").append(")");
        }
        sb.append("\nResponse body:\n").append(prettyPrintJson(result.getResponseBody()));
        if (result.getErrorMessage() != null && !result.getErrorMessage().isBlank()) {
            sb.append("\n\nLỗi: ").append(result.getErrorMessage());
        }
        return sb.toString();
    }

    private List<AssertionResultResponse> parseAssertions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AssertionResultResponse>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, String> parseJsonMapSafe(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String prettyPrintJson(String json) {
        if (json == null || json.isBlank()) {
            return "(không có)";
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
        } catch (Exception e) {
            return json;
        }
    }

    @Transactional
    public BugReportResponse create(UUID projectId, CreateBugReportRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        TestResult sourceResult = getOwnedFailedTestResult(project, request.sourceTestResultId());
        if (bugReportRepository.existsBySourceTestResultId(sourceResult.getId())) {
            throw new InvalidRequestException("Lần chạy này đã có Bug Report rồi");
        }

        User currentUser = currentUserService.getCurrentUser();
        if (project.getBugReportProjectSeq() == null) {
            int nextProjectSeq = projectRepository.findMaxBugReportProjectSeqByOwner(currentUser).orElse(0) + 1;
            project.setBugReportProjectSeq(nextProjectSeq);
            projectRepository.save(project);
        }
        int seqInProject = bugReportRepository.findMaxSeqInProjectByProject(project).orElse(0) + 1;
        String bugId = "B" + project.getBugReportProjectSeq() + "_" + String.format("%03d", seqInProject);

        TestCase testCase = sourceResult.getTestCase();
        String build = (request.build() == null || request.build().isBlank()) ? project.getName() : request.build();

        BugReport bug = BugReport.builder()
                .project(project)
                .endpoint(testCase.getEndpoint())
                .testCase(testCase)
                .sourceTestResult(sourceResult)
                .bugId(bugId)
                .projectSeq(project.getBugReportProjectSeq())
                .seqInProject(seqInProject)
                .severity(request.severity())
                .priority(request.priority())
                .frequency(request.frequency())
                .summary(request.summary())
                .testEnvironment(request.testEnvironment())
                .stepsToReproduce(request.stepsToReproduce())
                .actualResult(request.actualResult())
                .expectedResult(request.expectedResult())
                .attachmentUrl(request.attachmentUrl())
                .build(build)
                .reporter(currentUser)
                .build();

        try {
            bugReportRepository.save(bug);
        } catch (DataIntegrityViolationException e) {
            // Race hiếm: 2 request tạo bug đồng thời cho cùng project tính trùng seqInProject -
            // chặn ở tầng DB (uk_bug_reports_project_seq), báo lỗi rõ ràng thay vì 500 chung chung.
            throw new InvalidRequestException("Bug report vừa được tạo bởi 1 thao tác khác, vui lòng thử lại");
        }
        recordEvent(testCase.getEndpoint(), currentUser, bug.getId(), bug.getBugId(), bug.getSummary(), BugReportEventType.CREATED);
        return BugReportResponse.from(bug);
    }

    @Transactional
    public BugReportResponse update(UUID projectId, UUID bugReportId, UpdateBugReportRequest request) {
        BugReport bug = getOwnedBugReport(projectId, bugReportId);
        bug.setStatus(request.status());
        bug.setSeverity(request.severity());
        bug.setPriority(request.priority());
        bug.setFrequency(request.frequency());
        bug.setSummary(request.summary());
        bug.setTestEnvironment(request.testEnvironment());
        bug.setStepsToReproduce(request.stepsToReproduce());
        bug.setActualResult(request.actualResult());
        bug.setExpectedResult(request.expectedResult());
        bug.setAttachmentUrl(request.attachmentUrl());
        bug.setBuild(request.build());
        bug.setNote(request.note());
        bugReportRepository.save(bug);
        return BugReportResponse.from(bug);
    }

    @Transactional
    public BugReportResponse confirmClose(UUID projectId, UUID bugReportId) {
        BugReport bug = getOwnedBugReport(projectId, bugReportId);
        bug.setStatus(BugStatus.CLOSED);
        bug.setPendingCloseSuggestion(false);
        appendNote(bug, "[hệ thống] Đã xác nhận đóng lúc " + Instant.now() + " sau khi lần chạy mới nhất Pass");
        bugReportRepository.save(bug);
        return BugReportResponse.from(bug);
    }

    @Transactional
    public BugReportResponse dismissCloseSuggestion(UUID projectId, UUID bugReportId) {
        BugReport bug = getOwnedBugReport(projectId, bugReportId);
        bug.setPendingCloseSuggestion(false);
        bugReportRepository.save(bug);
        return BugReportResponse.from(bug);
    }

    @Transactional
    public void delete(UUID projectId, UUID bugReportId) {
        BugReport bug = getOwnedBugReport(projectId, bugReportId);
        // Chụp lại trước khi xoá - đọc field trên object Java vẫn an toàn ngay sau delete() (entity
        // chuyển trạng thái "removed" nhưng field trong bộ nhớ không bị xoá), nhưng chụp trước cho rõ ý.
        Endpoint endpoint = bug.getEndpoint();
        String bugId = bug.getBugId();
        String summary = bug.getSummary();
        bugReportRepository.delete(bug);
        recordEvent(endpoint, currentUserService.getCurrentUser(), null, bugId, summary, BugReportEventType.DELETED);
    }

    /**
     * Ghi sự kiện cho trang Lịch sử tổng (Module 8) - bugId/summary lưu dạng snapshot chuỗi, KHÔNG
     * phải FK tới BugReport, vì sự kiện DELETED được ghi SAU KHI dòng BugReport đã không còn tồn tại.
     * bugReportId chỉ truyền cho CREATED (dùng để "Xem chi tiết" ở Lịch sử tổng mở thẳng dialog sửa
     * đúng bug đó) - DELETED để null vì bug đã không còn tồn tại, không có gì để mở.
     */
    private void recordEvent(Endpoint endpoint, User actor, UUID bugReportId, String bugId, String summary, BugReportEventType eventType) {
        bugReportEventRepository.save(BugReportEvent.builder()
                .endpoint(endpoint).actor(actor).bugReportId(bugReportId).bugId(bugId).summary(summary).eventType(eventType).build());
    }

    public ExportFile exportToExcel(UUID projectId, UUID bugReportId) {
        BugReport bug = getOwnedBugReport(projectId, bugReportId);
        byte[] content = bugReportExportService.exportSingleBugToExcel(bug);
        return new ExportFile(bug.getBugId() + ".xlsx", content);
    }

    public ExportFile exportAllToExcel(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        List<BugReport> bugs = bugReportRepository.findAllByProject(project);
        byte[] content = bugReportExportService.exportBugsToExcel(bugs);
        return new ExportFile(sanitizeFilename(project.getName()) + "_BugReports.xlsx", content);
    }

    private String sanitizeFilename(String value) {
        String sanitized = value.replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        return sanitized.isBlank() ? "Project" : sanitized;
    }

    private BugReport getOwnedBugReport(UUID projectId, UUID bugReportId) {
        Project project = projectService.getOwnedProject(projectId);
        return bugReportRepository.findByIdAndProject(bugReportId, project)
                .orElseThrow(() -> new BugReportNotFoundException("Không tìm thấy Bug Report với id đã cho"));
    }

    private TestResult getOwnedFailedTestResult(Project project, UUID testResultId) {
        TestResult result = testResultRepository.findByIdAndTestCaseEndpointProject(testResultId, project)
                .orElseThrow(() -> new TestResultNotFoundException("Không tìm thấy lần chạy với id đã cho"));
        if (result.getStatus() != TestResultStatus.FAILED) {
            throw new InvalidRequestException("Chỉ tạo Bug Report từ lần chạy có kết quả Fail");
        }
        return result;
    }

    private void appendNote(BugReport bug, String line) {
        String existing = bug.getNote();
        bug.setNote(existing == null || existing.isBlank() ? line : existing + "\n" + line);
    }

    private String blankToPlaceholder(String value) {
        return (value == null || value.isBlank()) ? "(không có)" : value;
    }
}
