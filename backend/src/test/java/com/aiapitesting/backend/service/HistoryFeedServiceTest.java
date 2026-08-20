package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.request.HistoryStatusFilter;
import com.aiapitesting.backend.dto.response.HistoryFeedItemResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.entity.BugReportEvent;
import com.aiapitesting.backend.entity.BugReportEventType;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestExecutionEndpoint;
import com.aiapitesting.backend.entity.TestGenerationEvent;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.repository.BugReportEventRepository;
import com.aiapitesting.backend.repository.TestExecutionEndpointRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoryFeedServiceTest {

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private TestGenerationEventRepository testGenerationEventRepository;

    @Mock
    private TestExecutionEndpointRepository testExecutionEndpointRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private BugReportEventRepository bugReportEventRepository;

    @InjectMocks
    private HistoryFeedService historyFeedService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User owner;
    private Project project1;
    private Project project2;
    private Endpoint endpointA;
    private Endpoint endpointB;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(UUID.randomUUID()).build();
        project1 = Project.builder().id(UUID.randomUUID()).name("project1").owner(owner).build();
        project2 = Project.builder().id(UUID.randomUUID()).name("project2").owner(owner).build();
        endpointA = Endpoint.builder().id(UUID.randomUUID()).project(project1).method("POST").path("/api/products").build();
        endpointB = Endpoint.builder().id(UUID.randomUUID()).project(project2).method("GET").path("/api/orders").build();
        when(currentUserService.getCurrentUser()).thenReturn(owner);
    }

    private TestGenerationEvent generationEvent(Endpoint endpoint, Instant createdAt, int count) throws Exception {
        String snapshotJson = objectMapper.writeValueAsString(List.of(
                new GeneratedTestCase("Positive", "mo ta", Map.of(), "{}", 201, endpoint.getPath(), Map.of(), null, null)));
        return TestGenerationEvent.builder()
                .id(UUID.randomUUID()).endpoint(endpoint).testCaseCount(count)
                .snapshotJson(snapshotJson).createdAt(createdAt).build();
    }

    @Test
    void getFeed_mergesEventsAcrossProjects_sortedByOccurredAtDesc() throws Exception {
        TestGenerationEvent earlierGeneration = generationEvent(endpointA, Instant.parse("2026-08-13T03:00:00Z"), 4);
        when(testGenerationEventRepository.findAllForHistoryFeed(owner, null, null, null, null))
                .thenReturn(List.of(earlierGeneration));

        TestExecution laterExecution = TestExecution.builder().id(UUID.randomUUID()).project(project2)
                .status(ExecutionStatus.COMPLETED).startedAt(Instant.parse("2026-08-13T15:00:00Z")).build();
        TestExecutionEndpoint link = TestExecutionEndpoint.builder()
                .id(UUID.randomUUID()).execution(laterExecution).endpoint(endpointB).build();
        when(testExecutionEndpointRepository.findAllForHistoryFeed(owner, null, null, null))
                .thenReturn(List.of(link));

        TestCase tcB = TestCase.builder().id(UUID.randomUUID()).endpoint(endpointB).build();
        TestResult passed = TestResult.builder().testCase(tcB).execution(laterExecution).status(TestResultStatus.PASSED).build();
        when(testResultRepository.findAllByExecutionIn(List.of(laterExecution))).thenReturn(List.of(passed));

        PageResponse<HistoryFeedItemResponse> page = historyFeedService.getFeed(
                null, null, null, null, HistoryStatusFilter.ALL, PageRequest.of(0, 20));

        assertThat(page.data()).hasSize(2);
        assertThat(page.data().get(0).type()).isEqualTo("EXECUTION"); // 15:00 - muon hon, len dau
        assertThat(page.data().get(0).projectName()).isEqualTo("project2");
        assertThat(page.data().get(1).type()).isEqualTo("GENERATION"); // 03:00 - som hon
        assertThat(page.data().get(1).projectName()).isEqualTo("project1");
        assertThat(page.totalElements()).isEqualTo(2);
    }

    @Test
    void getFeed_mergesBugReportEvents_sortedTogetherWithOthers() {
        UUID createdBugReportId = UUID.randomUUID();
        BugReportEvent created = BugReportEvent.builder().id(UUID.randomUUID()).endpoint(endpointA)
                .actor(owner).bugReportId(createdBugReportId).bugId("B1_001").summary("Loi 500")
                .eventType(BugReportEventType.CREATED).occurredAt(Instant.parse("2026-08-13T09:00:00Z")).build();
        BugReportEvent deleted = BugReportEvent.builder().id(UUID.randomUUID()).endpoint(endpointB)
                .actor(owner).bugId("B2_003").summary("Da xoa").eventType(BugReportEventType.DELETED)
                .occurredAt(Instant.parse("2026-08-13T20:00:00Z")).build();
        when(bugReportEventRepository.findAllForHistoryFeed(owner, null, null, null, null))
                .thenReturn(List.of(created, deleted));
        when(testGenerationEventRepository.findAllForHistoryFeed(owner, null, null, null, null))
                .thenReturn(List.of());

        PageResponse<HistoryFeedItemResponse> page = historyFeedService.getFeed(
                null, null, null, null, HistoryStatusFilter.ALL, PageRequest.of(0, 20));

        assertThat(page.data()).hasSize(2);
        assertThat(page.data().get(0).type()).isEqualTo("BUG_REPORT_DELETED"); // 20:00 - muon hon, len dau
        assertThat(page.data().get(0).bugId()).isEqualTo("B2_003");
        assertThat(page.data().get(0).bugSummary()).isEqualTo("Da xoa");
        assertThat(page.data().get(1).type()).isEqualTo("BUG_REPORT_CREATED"); // 09:00 - som hon
        assertThat(page.data().get(1).bugId()).isEqualTo("B1_001");
        assertThat(page.data().get(1).bugReportId()).isEqualTo(createdBugReportId);
        assertThat(page.data().get(0).bugReportId()).isNull(); // DELETED khong deep-link duoc
    }

    @Test
    void getFeed_statusHasFail_excludesGenerationAndPassingExecutions() {
        TestExecution execution = TestExecution.builder().id(UUID.randomUUID()).project(project1)
                .status(ExecutionStatus.COMPLETED).startedAt(Instant.parse("2026-08-13T10:00:00Z")).build();
        TestExecutionEndpoint linkPassing = TestExecutionEndpoint.builder()
                .id(UUID.randomUUID()).execution(execution).endpoint(endpointA).build();
        TestExecution failingExecution = TestExecution.builder().id(UUID.randomUUID()).project(project2)
                .status(ExecutionStatus.COMPLETED).startedAt(Instant.parse("2026-08-13T11:00:00Z")).build();
        TestExecutionEndpoint linkFailing = TestExecutionEndpoint.builder()
                .id(UUID.randomUUID()).execution(failingExecution).endpoint(endpointB).build();
        when(testExecutionEndpointRepository.findAllForHistoryFeed(owner, null, null, null))
                .thenReturn(List.of(linkPassing, linkFailing));

        TestCase tcA = TestCase.builder().id(UUID.randomUUID()).endpoint(endpointA).build();
        TestResult passedResult = TestResult.builder().testCase(tcA).execution(execution).status(TestResultStatus.PASSED).build();
        TestCase tcB = TestCase.builder().id(UUID.randomUUID()).endpoint(endpointB).build();
        TestResult failedResult = TestResult.builder().testCase(tcB).execution(failingExecution).status(TestResultStatus.FAILED).build();
        when(testResultRepository.findAllByExecutionIn(List.of(execution, failingExecution)))
                .thenReturn(List.of(passedResult, failedResult));

        // status != ALL - service khong goi query generation event, khong stub testGenerationEventRepository.

        PageResponse<HistoryFeedItemResponse> page = historyFeedService.getFeed(
                null, null, null, null, HistoryStatusFilter.HAS_FAIL, PageRequest.of(0, 20));

        assertThat(page.data()).hasSize(1);
        assertThat(page.data().get(0).type()).isEqualTo("EXECUTION");
        assertThat(page.data().get(0).executionId()).isEqualTo(failingExecution.getId());
        assertThat(page.data().get(0).failCount()).isEqualTo(1);
    }

    @Test
    void getFeed_statusAllPass_excludesGenerationAndFailingExecutions() {
        TestExecution passingExecution = TestExecution.builder().id(UUID.randomUUID()).project(project1)
                .status(ExecutionStatus.COMPLETED).startedAt(Instant.parse("2026-08-13T10:00:00Z")).build();
        TestExecutionEndpoint linkPassing = TestExecutionEndpoint.builder()
                .id(UUID.randomUUID()).execution(passingExecution).endpoint(endpointA).build();
        TestExecution failingExecution = TestExecution.builder().id(UUID.randomUUID()).project(project2)
                .status(ExecutionStatus.COMPLETED).startedAt(Instant.parse("2026-08-13T11:00:00Z")).build();
        TestExecutionEndpoint linkFailing = TestExecutionEndpoint.builder()
                .id(UUID.randomUUID()).execution(failingExecution).endpoint(endpointB).build();
        when(testExecutionEndpointRepository.findAllForHistoryFeed(owner, null, null, null))
                .thenReturn(List.of(linkPassing, linkFailing));

        TestCase tcA = TestCase.builder().id(UUID.randomUUID()).endpoint(endpointA).build();
        TestResult passedResult = TestResult.builder().testCase(tcA).execution(passingExecution).status(TestResultStatus.PASSED).build();
        TestCase tcB = TestCase.builder().id(UUID.randomUUID()).endpoint(endpointB).build();
        TestResult failedResult = TestResult.builder().testCase(tcB).execution(failingExecution).status(TestResultStatus.FAILED).build();
        when(testResultRepository.findAllByExecutionIn(List.of(passingExecution, failingExecution)))
                .thenReturn(List.of(passedResult, failedResult));

        PageResponse<HistoryFeedItemResponse> page = historyFeedService.getFeed(
                null, null, null, null, HistoryStatusFilter.ALL_PASS, PageRequest.of(0, 20));

        assertThat(page.data()).hasSize(1);
        assertThat(page.data().get(0).executionId()).isEqualTo(passingExecution.getId());
        assertThat(page.data().get(0).failCount()).isZero();
    }

    @Test
    void getFeed_filteredByEndpoint_otherEndpointCountStillCountsFullExecution() {
        // endpointA va endpointB cung project1 - execution cham ca 2. Loc feed theo endpointA phai
        // van ra otherEndpointCount = 1 (khong duoc loc endpointId ngay o SQL truoc khi tinh so nay).
        Endpoint endpointA2 = Endpoint.builder().id(UUID.randomUUID()).project(project1).method("PUT").path("/api/products/{id}").build();
        TestExecution execution = TestExecution.builder().id(UUID.randomUUID()).project(project1)
                .status(ExecutionStatus.COMPLETED).startedAt(Instant.parse("2026-08-13T10:00:00Z")).build();
        TestExecutionEndpoint linkA = TestExecutionEndpoint.builder().id(UUID.randomUUID()).execution(execution).endpoint(endpointA).build();
        TestExecutionEndpoint linkA2 = TestExecutionEndpoint.builder().id(UUID.randomUUID()).execution(execution).endpoint(endpointA2).build();
        when(testExecutionEndpointRepository.findAllForHistoryFeed(owner, null, null, null))
                .thenReturn(List.of(linkA, linkA2));
        when(testGenerationEventRepository.findAllForHistoryFeed(owner, null, endpointA.getId(), null, null))
                .thenReturn(List.of());

        TestCase tcA = TestCase.builder().id(UUID.randomUUID()).endpoint(endpointA).build();
        TestCase tcA2 = TestCase.builder().id(UUID.randomUUID()).endpoint(endpointA2).build();
        TestResult resultA = TestResult.builder().testCase(tcA).execution(execution).status(TestResultStatus.PASSED).build();
        TestResult resultA2 = TestResult.builder().testCase(tcA2).execution(execution).status(TestResultStatus.PASSED).build();
        when(testResultRepository.findAllByExecutionIn(List.of(execution))).thenReturn(List.of(resultA, resultA2));

        PageResponse<HistoryFeedItemResponse> page = historyFeedService.getFeed(
                null, endpointA.getId(), null, null, HistoryStatusFilter.ALL, PageRequest.of(0, 20));

        assertThat(page.data()).hasSize(1);
        assertThat(page.data().get(0).endpointId()).isEqualTo(endpointA.getId());
        assertThat(page.data().get(0).otherEndpointCount()).isEqualTo(1);
    }

    @Test
    void getFeed_pagination_returnsCorrectSliceAndMetadata() {
        TestExecution exec1 = TestExecution.builder().id(UUID.randomUUID()).project(project1)
                .status(ExecutionStatus.COMPLETED).startedAt(Instant.parse("2026-08-13T09:00:00Z")).build();
        TestExecution exec2 = TestExecution.builder().id(UUID.randomUUID()).project(project1)
                .status(ExecutionStatus.COMPLETED).startedAt(Instant.parse("2026-08-13T10:00:00Z")).build();
        TestExecution exec3 = TestExecution.builder().id(UUID.randomUUID()).project(project1)
                .status(ExecutionStatus.COMPLETED).startedAt(Instant.parse("2026-08-13T11:00:00Z")).build();
        TestExecutionEndpoint link1 = TestExecutionEndpoint.builder().id(UUID.randomUUID()).execution(exec1).endpoint(endpointA).build();
        TestExecutionEndpoint link2 = TestExecutionEndpoint.builder().id(UUID.randomUUID()).execution(exec2).endpoint(endpointA).build();
        TestExecutionEndpoint link3 = TestExecutionEndpoint.builder().id(UUID.randomUUID()).execution(exec3).endpoint(endpointA).build();
        when(testExecutionEndpointRepository.findAllForHistoryFeed(owner, null, null, null))
                .thenReturn(List.of(link1, link2, link3));
        when(testGenerationEventRepository.findAllForHistoryFeed(owner, null, null, null, null))
                .thenReturn(List.of());
        when(testResultRepository.findAllByExecutionIn(List.of(exec1, exec2, exec3))).thenReturn(List.of());

        PageResponse<HistoryFeedItemResponse> page = historyFeedService.getFeed(
                null, null, null, null, HistoryStatusFilter.ALL, PageRequest.of(1, 1));

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.data()).hasSize(1);
        // Sap moi nhat truoc: [exec3(11:00), exec2(10:00), exec1(09:00)] - trang 1 (index 1) la exec2.
        assertThat(page.data().get(0).executionId()).isEqualTo(exec2.getId());
    }
}
