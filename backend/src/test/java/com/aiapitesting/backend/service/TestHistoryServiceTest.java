package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.response.EndpointHistoryResponse;
import com.aiapitesting.backend.dto.response.HistoryEventResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestExecutionEndpoint;
import com.aiapitesting.backend.entity.TestGenerationEvent;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestHistoryServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestGenerationEventRepository testGenerationEventRepository;

    @Mock
    private TestExecutionEndpointRepository testExecutionEndpointRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @InjectMocks
    private TestHistoryService testHistoryService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Project project;
    private UUID projectId;
    private Endpoint endpointA;
    private Endpoint endpointB;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = Project.builder().id(projectId).build();
        endpointA = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/api/products").build();
        endpointB = Endpoint.builder().id(UUID.randomUUID()).project(project).method("PUT").path("/api/products/{id}").build();
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
    }

    @Test
    void getHistory_endpointWithOnlyGenerationEvent_returnsGenerationEvent() throws Exception {
        stubEndpoints(endpointA);
        stubTestCaseCounts(Map.of(endpointA.getId(), 3L));

        String snapshotJson = objectMapper.writeValueAsString(List.of(
                new GeneratedTestCase("Positive - tao san pham", "mo ta", Map.of(), "{}", 201, "/api/products", Map.of())));
        TestGenerationEvent event = TestGenerationEvent.builder()
                .id(UUID.randomUUID()).endpoint(endpointA).testCaseCount(3)
                .snapshotJson(snapshotJson).createdAt(Instant.parse("2026-08-13T03:04:00Z")).build();
        when(testGenerationEventRepository.findAllByEndpointProject(project)).thenReturn(List.of(event));
        when(testExecutionEndpointRepository.findAllByEndpointProject(project)).thenReturn(List.of());

        List<EndpointHistoryResponse> history = testHistoryService.getHistory(projectId);

        assertThat(history).hasSize(1);
        EndpointHistoryResponse endpointHistory = history.get(0);
        assertThat(endpointHistory.endpointId()).isEqualTo(endpointA.getId());
        assertThat(endpointHistory.currentTestCaseCount()).isEqualTo(3);
        assertThat(endpointHistory.totalRuns()).isZero();
        assertThat(endpointHistory.events()).hasSize(1);
        HistoryEventResponse genEvent = endpointHistory.events().get(0);
        assertThat(genEvent.type()).isEqualTo("GENERATION");
        assertThat(genEvent.generatedCount()).isEqualTo(3);
        assertThat(genEvent.snapshotItems()).hasSize(1);
        assertThat(genEvent.snapshotItems().get(0).name()).isEqualTo("Positive - tao san pham");
    }

    @Test
    void getHistory_endpointWithOnlyExecutionEvent_returnsExecutionEventWithPassFailCounts() {
        stubEndpoints(endpointB);
        stubTestCaseCounts(Map.of(endpointB.getId(), 2L));
        when(testGenerationEventRepository.findAllByEndpointProject(project)).thenReturn(List.of());

        TestExecution execution = TestExecution.builder().id(UUID.randomUUID()).project(project)
                .status(ExecutionStatus.COMPLETED).startedAt(Instant.parse("2026-08-13T15:04:00Z")).build();
        TestExecutionEndpoint link = TestExecutionEndpoint.builder()
                .id(UUID.randomUUID()).execution(execution).endpoint(endpointB).build();
        when(testExecutionEndpointRepository.findAllByEndpointProject(project)).thenReturn(List.of(link));

        TestCase tcB1 = TestCase.builder().id(UUID.randomUUID()).endpoint(endpointB).build();
        TestCase tcB2 = TestCase.builder().id(UUID.randomUUID()).endpoint(endpointB).build();
        TestResult passed = TestResult.builder().testCase(tcB1).execution(execution).status(TestResultStatus.PASSED).build();
        TestResult failed = TestResult.builder().testCase(tcB2).execution(execution).status(TestResultStatus.FAILED).build();
        when(testResultRepository.findAllByExecutionIn(List.of(execution))).thenReturn(List.of(passed, failed));

        List<EndpointHistoryResponse> history = testHistoryService.getHistory(projectId);

        assertThat(history).hasSize(1);
        EndpointHistoryResponse endpointHistory = history.get(0);
        assertThat(endpointHistory.totalRuns()).isEqualTo(1);
        assertThat(endpointHistory.events()).hasSize(1);
        HistoryEventResponse execEvent = endpointHistory.events().get(0);
        assertThat(execEvent.type()).isEqualTo("EXECUTION");
        assertThat(execEvent.executionId()).isEqualTo(execution.getId());
        assertThat(execEvent.selectedCount()).isEqualTo(2);
        assertThat(execEvent.passCount()).isEqualTo(1);
        assertThat(execEvent.failCount()).isEqualTo(1);
        assertThat(execEvent.otherEndpointCount()).isZero();
    }

    @Test
    void getHistory_executionSpansMultipleEndpoints_setsOtherEndpointCountOnBothSides() {
        stubEndpoints(endpointA, endpointB);
        stubTestCaseCounts(Map.of(endpointA.getId(), 1L, endpointB.getId(), 1L));
        when(testGenerationEventRepository.findAllByEndpointProject(project)).thenReturn(List.of());

        // Kịch bản chaining: PUT /api/products/{id} (endpointB) tự kéo theo test case nguồn của
        // POST /api/products (endpointA) - 1 execution nhưng chạm tới cả 2 endpoint.
        TestExecution execution = TestExecution.builder().id(UUID.randomUUID()).project(project)
                .status(ExecutionStatus.COMPLETED).startedAt(Instant.parse("2026-08-13T15:04:00Z")).build();
        TestExecutionEndpoint linkA = TestExecutionEndpoint.builder().id(UUID.randomUUID()).execution(execution).endpoint(endpointA).build();
        TestExecutionEndpoint linkB = TestExecutionEndpoint.builder().id(UUID.randomUUID()).execution(execution).endpoint(endpointB).build();
        when(testExecutionEndpointRepository.findAllByEndpointProject(project)).thenReturn(List.of(linkA, linkB));

        TestCase tcA = TestCase.builder().id(UUID.randomUUID()).endpoint(endpointA).build();
        TestCase tcB = TestCase.builder().id(UUID.randomUUID()).endpoint(endpointB).build();
        TestResult resultA = TestResult.builder().testCase(tcA).execution(execution).status(TestResultStatus.PASSED).build();
        TestResult resultB = TestResult.builder().testCase(tcB).execution(execution).status(TestResultStatus.PASSED).build();
        when(testResultRepository.findAllByExecutionIn(List.of(execution))).thenReturn(List.of(resultA, resultB));

        List<EndpointHistoryResponse> history = testHistoryService.getHistory(projectId);

        assertThat(history).hasSize(2);
        for (EndpointHistoryResponse endpointHistory : history) {
            assertThat(endpointHistory.events()).hasSize(1);
            HistoryEventResponse event = endpointHistory.events().get(0);
            assertThat(event.selectedCount()).isEqualTo(1);
            assertThat(event.passCount()).isEqualTo(1);
            assertThat(event.otherEndpointCount()).isEqualTo(1);
        }
    }

    @Test
    void getHistory_endpointWithNoEvents_excludedFromResponse() {
        stubEndpoints(endpointA, endpointB);
        stubTestCaseCounts(Map.of());
        when(testGenerationEventRepository.findAllByEndpointProject(project)).thenReturn(List.of());
        when(testExecutionEndpointRepository.findAllByEndpointProject(project)).thenReturn(List.of());

        List<EndpointHistoryResponse> history = testHistoryService.getHistory(projectId);

        assertThat(history).isEmpty();
    }

    @Test
    void getHistory_sortsEventsByOccurredAtAscending() {
        stubEndpoints(endpointA);
        stubTestCaseCounts(Map.of(endpointA.getId(), 1L));

        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(List.of(
                    new GeneratedTestCase("Positive", "mo ta", Map.of(), "{}", 201, "/api/products", Map.of())));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        TestGenerationEvent laterGenerationEvent = TestGenerationEvent.builder()
                .id(UUID.randomUUID()).endpoint(endpointA).testCaseCount(1)
                .snapshotJson(snapshotJson).createdAt(Instant.parse("2026-08-13T10:04:00Z")).build();
        when(testGenerationEventRepository.findAllByEndpointProject(project)).thenReturn(List.of(laterGenerationEvent));

        TestExecution earlierExecution = TestExecution.builder().id(UUID.randomUUID()).project(project)
                .status(ExecutionStatus.COMPLETED).startedAt(Instant.parse("2026-08-13T03:04:00Z")).build();
        TestExecutionEndpoint link = TestExecutionEndpoint.builder()
                .id(UUID.randomUUID()).execution(earlierExecution).endpoint(endpointA).build();
        when(testExecutionEndpointRepository.findAllByEndpointProject(project)).thenReturn(List.of(link));
        when(testResultRepository.findAllByExecutionIn(List.of(earlierExecution))).thenReturn(List.of());

        List<EndpointHistoryResponse> history = testHistoryService.getHistory(projectId);

        assertThat(history).hasSize(1);
        List<HistoryEventResponse> events = history.get(0).events();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo("EXECUTION"); // 03:04 - som hon
        assertThat(events.get(1).type()).isEqualTo("GENERATION"); // 10:04 - muon hon
    }

    private void stubEndpoints(Endpoint... endpoints) {
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(endpoints));
    }

    private void stubTestCaseCounts(Map<UUID, Long> countsByEndpointId) {
        List<TestCaseRepository.EndpointTestCaseCount> projections = countsByEndpointId.entrySet().stream()
                .<TestCaseRepository.EndpointTestCaseCount>map(entry -> new TestCaseRepository.EndpointTestCaseCount() {
                    @Override
                    public UUID getEndpointId() {
                        return entry.getKey();
                    }

                    @Override
                    public long getCount() {
                        return entry.getValue();
                    }
                })
                .toList();
        when(testCaseRepository.countByEndpointIds(org.mockito.ArgumentMatchers.anyList())).thenReturn(projections);
    }
}
