package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.CreateBugReportRequest;
import com.aiapitesting.backend.dto.response.BugReportDraftResponse;
import com.aiapitesting.backend.dto.response.BugReportPageResponse;
import com.aiapitesting.backend.dto.response.BugReportResponse;
import com.aiapitesting.backend.entity.BugFrequency;
import com.aiapitesting.backend.entity.BugPriority;
import com.aiapitesting.backend.entity.BugReport;
import com.aiapitesting.backend.entity.BugReportEvent;
import com.aiapitesting.backend.entity.BugReportEventType;
import com.aiapitesting.backend.entity.BugSeverity;
import com.aiapitesting.backend.entity.BugStatus;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.BugReportEventRepository;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.ProjectRepository;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BugReportServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private BugReportRepository bugReportRepository;

    @Mock
    private BugReportEventRepository bugReportEventRepository;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private BugReportExportService bugReportExportService;

    @Spy
    private TestCasePathValidator testCasePathValidator = new TestCasePathValidator();

    @InjectMocks
    private BugReportService bugReportService;

    private UUID projectId;
    private Project project;
    private User owner;
    private Endpoint endpoint;
    private TestCase testCase;
    private TestResult failedResult;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        owner = User.builder().id(UUID.randomUUID()).email("qa@example.com").build();
        project = Project.builder().id(projectId).name("Shop API").owner(owner)
                .targetBaseUrl("http://localhost:8081").targetAuthType(TargetAuthType.BEARER_TOKEN).build();
        endpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/api/products").build();
        testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).name("Positive - tao san pham")
                .expectedStatus(201).resolvedPath("/api/products").requestBody("{\"name\":\"a\"}").build();
        TestExecution execution = TestExecution.builder().id(UUID.randomUUID())
                .startedAt(java.time.Instant.now()).build();
        failedResult = TestResult.builder().id(UUID.randomUUID()).testCase(testCase).execution(execution)
                .status(TestResultStatus.FAILED).responseStatus(500).build();

        when(projectService.getOwnedProject(projectId)).thenReturn(project);
    }

    @Test
    void create_firstBugOfFirstProject_generatesB1_001() {
        when(testResultRepository.findByIdAndTestCaseEndpointProject(failedResult.getId(), project))
                .thenReturn(Optional.of(failedResult));
        when(bugReportRepository.existsBySourceTestResultId(failedResult.getId())).thenReturn(false);
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        when(projectRepository.findMaxBugReportProjectSeqByOwner(owner)).thenReturn(Optional.empty());
        when(bugReportRepository.findMaxSeqInProjectByProject(project)).thenReturn(Optional.empty());

        CreateBugReportRequest request = new CreateBugReportRequest(
                failedResult.getId(), "Tạo sản phẩm bị lỗi 500", null, null, null, null,
                BugSeverity.SERIOUS, BugFrequency.ALWAYS, BugPriority.MAJOR, null, null);

        BugReportResponse response = bugReportService.create(projectId, request);

        assertThat(response.bugId()).isEqualTo("B1_001");
        assertThat(response.status()).isEqualTo(BugStatus.NEW);
        assertThat(response.build()).isEqualTo("Shop API"); // build trống -> mặc định tên Project
        assertThat(project.getBugReportProjectSeq()).isEqualTo(1);
        verify(projectRepository).save(project);

        // Lịch sử tổng (Module 8) phải ghi được sự kiện "Tạo Bug Report" ngay khi tạo thành công.
        ArgumentCaptor<BugReportEvent> eventCaptor = ArgumentCaptor.forClass(BugReportEvent.class);
        verify(bugReportEventRepository).save(eventCaptor.capture());
        BugReportEvent savedEvent = eventCaptor.getValue();
        assertThat(savedEvent.getEventType()).isEqualTo(BugReportEventType.CREATED);
        assertThat(savedEvent.getBugId()).isEqualTo("B1_001");
        assertThat(savedEvent.getEndpoint()).isEqualTo(endpoint);
        // bugReportId phải là UUID thật của bug vừa tạo - dùng để "Xem chi tiết" ở Lịch sử tổng mở
        // thẳng dialog sửa đúng bug đó (chỉ có ở CREATED, vì bug lúc này chắc chắn còn tồn tại).
        assertThat(savedEvent.getBugReportId()).isEqualTo(response.id());
    }

    @Test
    void create_projectAlreadyHasSeq_reusesExistingSeqAndSkipsProjectSave() {
        project.setBugReportProjectSeq(3);
        when(testResultRepository.findByIdAndTestCaseEndpointProject(failedResult.getId(), project))
                .thenReturn(Optional.of(failedResult));
        when(bugReportRepository.existsBySourceTestResultId(failedResult.getId())).thenReturn(false);
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        when(bugReportRepository.findMaxSeqInProjectByProject(project)).thenReturn(Optional.of(4));

        CreateBugReportRequest request = new CreateBugReportRequest(
                failedResult.getId(), "Bug thứ 5", null, null, null, null,
                BugSeverity.MINOR, BugFrequency.SELDOM, BugPriority.TRIVIAL, null, "v1.2.0");

        BugReportResponse response = bugReportService.create(projectId, request);

        assertThat(response.bugId()).isEqualTo("B3_005");
        assertThat(response.build()).isEqualTo("v1.2.0");
        verify(projectRepository, never()).save(any());
        verify(projectRepository, never()).findMaxBugReportProjectSeqByOwner(any());
    }

    @Test
    void create_afterGapFromDeletedBug_usesMaxSeqNotCountToAvoidCollision() {
        // Bug tái diễn thật: xoá 1 bug ở giữa (VD B1_002) để lại "lỗ hổng" - project còn B1_001/B1_003.
        // Nếu dùng COUNT(*)+1 = 2+1 = 3 -> trùng "B1_003" đang tồn tại -> DataIntegrityViolationException.
        // MAX(seqInProject)+1 = 3+1 = 4 mới đúng, không đụng bug nào còn lại.
        project.setBugReportProjectSeq(1);
        when(testResultRepository.findByIdAndTestCaseEndpointProject(failedResult.getId(), project))
                .thenReturn(Optional.of(failedResult));
        when(bugReportRepository.existsBySourceTestResultId(failedResult.getId())).thenReturn(false);
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        when(bugReportRepository.findMaxSeqInProjectByProject(project)).thenReturn(Optional.of(3));

        CreateBugReportRequest request = new CreateBugReportRequest(
                failedResult.getId(), "Bug sau khi xoá B1_002", null, null, null, null,
                BugSeverity.MINOR, BugFrequency.SELDOM, BugPriority.TRIVIAL, null, null);

        BugReportResponse response = bugReportService.create(projectId, request);

        assertThat(response.bugId()).isEqualTo("B1_004");
    }

    @Test
    void create_sourceResultNotFailed_throwsInvalidRequestException() {
        TestResult passedResult = TestResult.builder().id(UUID.randomUUID()).testCase(testCase).status(TestResultStatus.PASSED).build();
        when(testResultRepository.findByIdAndTestCaseEndpointProject(passedResult.getId(), project))
                .thenReturn(Optional.of(passedResult));

        CreateBugReportRequest request = new CreateBugReportRequest(
                passedResult.getId(), "Không hợp lệ", null, null, null, null,
                BugSeverity.MINOR, BugFrequency.SELDOM, BugPriority.TRIVIAL, null, null);

        assertThatThrownBy(() -> bugReportService.create(projectId, request))
                .isInstanceOf(InvalidRequestException.class);
        verify(bugReportRepository, never()).save(any());
    }

    @Test
    void create_sourceResultAlreadyHasBugReport_throwsInvalidRequestException() {
        when(testResultRepository.findByIdAndTestCaseEndpointProject(failedResult.getId(), project))
                .thenReturn(Optional.of(failedResult));
        when(bugReportRepository.existsBySourceTestResultId(failedResult.getId())).thenReturn(true);

        CreateBugReportRequest request = new CreateBugReportRequest(
                failedResult.getId(), "Trùng", null, null, null, null,
                BugSeverity.MINOR, BugFrequency.SELDOM, BugPriority.TRIVIAL, null, null);

        assertThatThrownBy(() -> bugReportService.create(projectId, request))
                .isInstanceOf(InvalidRequestException.class);
        verify(bugReportRepository, never()).save(any());
    }

    @Test
    void getDraft_buildsCombinedDescriptionEmbeddingResponseBody() {
        TestResult resultWithBody = TestResult.builder().id(UUID.randomUUID()).testCase(testCase)
                .status(TestResultStatus.FAILED).responseStatus(500)
                .responseBody("{\"code\":\"INTERNAL_ERROR\",\"message\":\"boom\"}").build();
        when(testResultRepository.findByIdAndTestCaseEndpointProject(resultWithBody.getId(), project))
                .thenReturn(Optional.of(resultWithBody));

        BugReportDraftResponse draft = bugReportService.getDraft(projectId, resultWithBody.getId());

        assertThat(draft.summary()).contains(testCase.getName());
        // stepsToReproduce field giờ chứa "Mô tả" gộp cả 4 phần (Test Environment/Steps/Expected/Actual).
        // Bước tái hiện chỉ 1 câu đơn giản "Gửi {method} {path}" (đã bỏ lệnh curl theo phản hồi
        // người dùng - quá rối, chỉ cần biết gửi request nào là đủ).
        assertThat(draft.stepsToReproduce())
                .contains("Môi trường kiểm thử")
                .contains("Các bước tái hiện")
                .contains("1. Gửi POST /api/products")
                .doesNotContain("curl")
                .contains("Kết quả mong đợi").contains("201")
                .contains("Kết quả thực tế").contains("500")
                .contains("INTERNAL_ERROR"); // response body thật được nhúng vào, không chỉ status code
        assertThat(draft.actualResult()).isNull();
        assertThat(draft.expectedResult()).isNull();
        assertThat(draft.defaultBuild()).isEqualTo("Shop API");
        assertThat(draft.component().endpointMethod()).isEqualTo("POST");
    }

    @Test
    void getDraft_testCaseHasAssertions_listsExpectedAndActualPerAssertion() {
        // assertionResultsJson đúng hình dạng AssertionResultResponse (Module 9b) - dữ liệu thật đã
        // ghi lúc TestExecutionRunner chấm assertion, không phải mock giả lập ngẫu nhiên.
        String assertionsJson = "[{\"jsonPath\":\"$.stock\",\"operator\":\"EQUALS\",\"expectedValue\":\"5\","
                + "\"actualValue\":\"3\",\"passed\":false},"
                + "{\"jsonPath\":\"$.name\",\"operator\":\"EXISTS\",\"expectedValue\":null,"
                + "\"actualValue\":\"Laptop\",\"passed\":true}]";
        TestResult resultWithAssertions = TestResult.builder().id(UUID.randomUUID()).testCase(testCase)
                .status(TestResultStatus.FAILED).responseStatus(200)
                .responseBody("{\"stock\":3}")
                .assertionResultsJson(assertionsJson).build();
        when(testResultRepository.findByIdAndTestCaseEndpointProject(resultWithAssertions.getId(), project))
                .thenReturn(Optional.of(resultWithAssertions));

        BugReportDraftResponse draft = bugReportService.getDraft(projectId, resultWithAssertions.getId());

        assertThat(draft.stepsToReproduce())
                .contains("Kết quả mong đợi").contains("$.stock EQUALS \"5\"")
                .contains("Kết quả thực tế")
                .contains("$.stock → thực tế \"3\" (KHÔNG ĐẠT)")
                .contains("$.name → thực tế \"Laptop\" (ĐẠT)");
    }

    @Test
    void getRunHistory_resultHasAssertions_includesAssertionResultsForTang3() {
        // Tầng 3 (khung "Các test đã chạy") phải hiện đúng assertion đã chấm giống hệt trang Kết quả
        // thực thi - trước đó TestResultHistoryItemResponse thiếu hẳn field này.
        String assertionsJson = "[{\"jsonPath\":\"$.stock\",\"operator\":\"EQUALS\",\"expectedValue\":\"5\","
                + "\"actualValue\":\"3\",\"passed\":false}]";
        TestExecution execution = TestExecution.builder().id(UUID.randomUUID())
                .startedAt(java.time.Instant.parse("2026-08-19T18:15:59Z")).build();
        TestResult resultWithAssertions = TestResult.builder().id(UUID.randomUUID()).testCase(testCase)
                .execution(execution).status(TestResultStatus.FAILED).responseStatus(200)
                .assertionResultsJson(assertionsJson).build();
        when(testCaseRepository.findByIdAndEndpointProject(testCase.getId(), project))
                .thenReturn(Optional.of(testCase));
        when(testResultRepository.findAllByTestCaseOrderByExecutionStartedAtAsc(testCase))
                .thenReturn(List.of(resultWithAssertions));

        List<com.aiapitesting.backend.dto.response.TestResultHistoryItemResponse> history =
                bugReportService.getRunHistory(projectId, testCase.getId());

        assertThat(history).hasSize(1);
        assertThat(history.get(0).assertionResults()).hasSize(1);
        assertThat(history.get(0).assertionResults().get(0).jsonPath()).isEqualTo("$.stock");
        assertThat(history.get(0).assertionResults().get(0).passed()).isFalse();
    }

    @Test
    void getBugReports_excludesTestCasesNeverRunAndComputesDashboardSummaryFromBugsOnly() {
        // Test case thứ 2 CHƯA có bug nào NHƯNG đã chạy - vẫn phải xuất hiện (Tầng 2 hiện mọi test
        // case đã chạy, không chỉ những cái có bug). Test case thứ 3 CHƯA TỪNG chạy lần nào - phải
        // bị loại hẳn khỏi danh sách (không disable, ẩn thẳng theo yêu cầu người dùng).
        TestCase testCaseWithoutBug = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint)
                .name("Positive - khong co bug").expectedStatus(201).build();
        TestCase testCaseNeverRun = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint)
                .name("Chua chay lan nao").expectedStatus(201).build();
        when(testCaseRepository.findAllByEndpointProject(project))
                .thenReturn(List.of(testCase, testCaseWithoutBug, testCaseNeverRun));
        when(testResultRepository.findDistinctTestCaseIdsWithResultsByProject(project))
                .thenReturn(List.of(testCase.getId(), testCaseWithoutBug.getId()));

        BugReport openBug = BugReport.builder().id(UUID.randomUUID()).project(project).endpoint(endpoint)
                .testCase(testCase).sourceTestResult(failedResult).bugId("B1_001").projectSeq(1).seqInProject(1)
                .status(BugStatus.PENDING).severity(BugSeverity.SERIOUS).priority(BugPriority.MAJOR)
                .frequency(BugFrequency.ALWAYS).summary("Bug 1").reporter(owner).build();
        BugReport closedBug = BugReport.builder().id(UUID.randomUUID()).project(project).endpoint(endpoint)
                .testCase(testCase).sourceTestResult(failedResult).bugId("B1_002").projectSeq(1).seqInProject(2)
                .status(BugStatus.CLOSED).severity(BugSeverity.MINOR).priority(BugPriority.MAJOR)
                .frequency(BugFrequency.SELDOM).summary("Bug 2").reporter(owner).build();
        when(bugReportRepository.findAllByProject(project)).thenReturn(List.of(openBug, closedBug));

        BugReportPageResponse page = bugReportService.getBugReports(projectId);

        assertThat(page.summary().totalCount()).isEqualTo(2);
        assertThat(page.summary().openCount()).isEqualTo(1);
        assertThat(page.summary().closedCount()).isEqualTo(1);
        assertThat(page.summary().countByPriority().get(BugPriority.MAJOR)).isEqualTo(2L);
        assertThat(page.summary().countByComponent()).hasSize(1);
        assertThat(page.summary().countByComponent().get(0).count()).isEqualTo(2);

        assertThat(page.endpoints()).hasSize(1);
        // Chỉ 2 test case đã từng chạy hiện ra - testCaseNeverRun bị loại hẳn.
        assertThat(page.endpoints().get(0).testCases()).hasSize(2);
        assertThat(page.endpoints().get(0).testCases())
                .noneMatch(tc -> tc.testCaseId().equals(testCaseNeverRun.getId()));
        var tcWithBugs = page.endpoints().get(0).testCases().stream()
                .filter(tc -> tc.testCaseId().equals(testCase.getId())).findFirst().orElseThrow();
        var tcWithoutBugs = page.endpoints().get(0).testCases().stream()
                .filter(tc -> tc.testCaseId().equals(testCaseWithoutBug.getId())).findFirst().orElseThrow();
        assertThat(tcWithBugs.bugs()).hasSize(2);
        assertThat(tcWithoutBugs.bugs()).isEmpty();
    }

    @Test
    void getBugReports_endpointWithNoTestCaseEverRun_excludedEntirely() {
        TestCase neverRun = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint)
                .name("Chua chay").expectedStatus(200).build();
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(neverRun));
        when(testResultRepository.findDistinctTestCaseIdsWithResultsByProject(project)).thenReturn(List.of());
        when(bugReportRepository.findAllByProject(project)).thenReturn(List.of());

        BugReportPageResponse page = bugReportService.getBugReports(projectId);

        assertThat(page.endpoints()).isEmpty();
    }

    @Test
    void confirmClose_setsClosedAndClearsPendingSuggestion() {
        BugReport bug = BugReport.builder().id(UUID.randomUUID()).project(project).endpoint(endpoint)
                .testCase(testCase).sourceTestResult(failedResult).status(BugStatus.PENDING)
                .pendingCloseSuggestion(true).reporter(owner).build();
        when(bugReportRepository.findByIdAndProject(bug.getId(), project)).thenReturn(Optional.of(bug));

        BugReportResponse response = bugReportService.confirmClose(projectId, bug.getId());

        assertThat(response.status()).isEqualTo(BugStatus.CLOSED);
        assertThat(response.pendingCloseSuggestion()).isFalse();
        assertThat(bug.getNote()).contains("Đã xác nhận đóng");
    }

    @Test
    void exportToExcel_delegatesToExportServiceAndNamesFileAfterBugId() {
        BugReport bug = BugReport.builder().id(UUID.randomUUID()).project(project).endpoint(endpoint)
                .testCase(testCase).sourceTestResult(failedResult).bugId("B1_007").status(BugStatus.NEW)
                .reporter(owner).build();
        when(bugReportRepository.findByIdAndProject(bug.getId(), project)).thenReturn(Optional.of(bug));
        byte[] fakeXlsx = {1, 2, 3};
        when(bugReportExportService.exportSingleBugToExcel(bug)).thenReturn(fakeXlsx);

        BugReportService.ExportFile file = bugReportService.exportToExcel(projectId, bug.getId());

        assertThat(file.filename()).isEqualTo("B1_007.xlsx");
        assertThat(file.content()).isEqualTo(fakeXlsx);
    }

    @Test
    void exportAllToExcel_delegatesToExportServiceAndNamesFileAfterSanitizedProjectName() {
        BugReport bug1 = BugReport.builder().id(UUID.randomUUID()).project(project).endpoint(endpoint)
                .testCase(testCase).sourceTestResult(failedResult).bugId("B1_001").status(BugStatus.NEW)
                .reporter(owner).build();
        BugReport bug2 = BugReport.builder().id(UUID.randomUUID()).project(project).endpoint(endpoint)
                .testCase(testCase).sourceTestResult(failedResult).bugId("B1_002").status(BugStatus.CLOSED)
                .reporter(owner).build();
        List<BugReport> bugs = List.of(bug1, bug2);
        when(bugReportRepository.findAllByProject(project)).thenReturn(bugs);
        byte[] fakeXlsx = {4, 5, 6};
        when(bugReportExportService.exportBugsToExcel(bugs)).thenReturn(fakeXlsx);

        BugReportService.ExportFile file = bugReportService.exportAllToExcel(projectId, null);

        // "Shop API" (có khoảng trắng) -> khoảng trắng thay bằng "_" để an toàn cho Content-Disposition.
        assertThat(file.filename()).isEqualTo("Shop_API_BugReports.xlsx");
        assertThat(file.content()).isEqualTo(fakeXlsx);
    }

    @Test
    void dismissCloseSuggestion_onlyClearsFlagKeepsStatus() {
        BugReport bug = BugReport.builder().id(UUID.randomUUID()).project(project).endpoint(endpoint)
                .testCase(testCase).sourceTestResult(failedResult).status(BugStatus.PENDING)
                .pendingCloseSuggestion(true).reporter(owner).build();
        when(bugReportRepository.findByIdAndProject(bug.getId(), project)).thenReturn(Optional.of(bug));

        BugReportResponse response = bugReportService.dismissCloseSuggestion(projectId, bug.getId());

        assertThat(response.status()).isEqualTo(BugStatus.PENDING);
        assertThat(response.pendingCloseSuggestion()).isFalse();
    }

    @Test
    void delete_removesBugAndRecordsDeletedEventWithSnapshotBeforeRowIsGone() {
        BugReport bug = BugReport.builder().id(UUID.randomUUID()).project(project).endpoint(endpoint)
                .testCase(testCase).sourceTestResult(failedResult).bugId("B1_009")
                .status(BugStatus.NEW).summary("Bug se bi xoa").reporter(owner).build();
        when(bugReportRepository.findByIdAndProject(bug.getId(), project)).thenReturn(Optional.of(bug));
        when(currentUserService.getCurrentUser()).thenReturn(owner);

        bugReportService.delete(projectId, bug.getId());

        verify(bugReportRepository).delete(bug);
        // Xoá bug report là xoá hẳn dòng đó - sự kiện DELETED phải lưu SNAPSHOT (bugId/summary dạng
        // chuỗi), không phải FK tới BugReport đã không còn tồn tại.
        ArgumentCaptor<BugReportEvent> eventCaptor = ArgumentCaptor.forClass(BugReportEvent.class);
        verify(bugReportEventRepository).save(eventCaptor.capture());
        BugReportEvent savedEvent = eventCaptor.getValue();
        assertThat(savedEvent.getEventType()).isEqualTo(BugReportEventType.DELETED);
        assertThat(savedEvent.getBugId()).isEqualTo("B1_009");
        assertThat(savedEvent.getSummary()).isEqualTo("Bug se bi xoa");
        assertThat(savedEvent.getEndpoint()).isEqualTo(endpoint);
        // DELETED không có bugReportId - bug đã không còn tồn tại, không có gì để deep-link mở dialog.
        assertThat(savedEvent.getBugReportId()).isNull();
        assertThat(savedEvent.getActor()).isEqualTo(owner);
    }
}
