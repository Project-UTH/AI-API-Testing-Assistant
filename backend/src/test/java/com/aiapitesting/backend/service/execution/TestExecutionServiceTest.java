package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.dto.request.TestExecutionRequest;
import com.aiapitesting.backend.dto.response.TestExecutionResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseDependency;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestExecutionEndpoint;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.TestExecutionNotFoundException;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.TestCaseAssertionRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestExecutionEndpointRepository;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestExecutionServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestCaseDependencyRepository testCaseDependencyRepository;

    @Mock
    private TestCaseAssertionRepository testCaseAssertionRepository;

    @Mock
    private TestExecutionRepository testExecutionRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private TestExecutionEndpointRepository testExecutionEndpointRepository;

    @Mock
    private TestExecutionRunner testExecutionRunner;

    @Mock
    private BugReportRepository bugReportRepository;

    @Mock
    private ExecutionFailureAnalyzer executionFailureAnalyzer;

    @InjectMocks
    private TestExecutionService testExecutionService;

    private Project project;
    private UUID projectId;
    private Endpoint getEndpoint;
    private Endpoint postEndpoint;
    private Endpoint deleteEndpoint;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = Project.builder().id(projectId).targetBaseUrl("https://petstore.example.com").build();
        getEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/pet/{petId}").build();
        postEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/pet").build();
        deleteEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("DELETE").path("/pet/{petId}").build();
    }

    @Test
    void trigger_missingTargetBaseUrl_throwsInvalidRequest() {
        Project projectWithoutUrl = Project.builder().id(projectId).targetBaseUrl(null).build();
        when(projectService.getOwnedProject(projectId)).thenReturn(projectWithoutUrl);

        assertThatThrownBy(() -> testExecutionService.trigger(projectId, new TestExecutionRequest(List.of(UUID.randomUUID()))))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(testCaseRepository, testExecutionRepository, testExecutionRunner);
    }

    @Test
    void trigger_someTestCaseNotOwned_throwsInvalidRequest() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        TestCase onlyOne = TestCase.builder().id(id1).endpoint(getEndpoint).expectedStatus(200)
                .createdAt(Instant.now()).build();
        when(testCaseRepository.findAllByIdInAndEndpointProject(List.of(id1, id2), project))
                .thenReturn(List.of(onlyOne));

        assertThatThrownBy(() -> testExecutionService.trigger(projectId, new TestExecutionRequest(List.of(id1, id2))))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(testExecutionRepository, testExecutionRunner);
    }

    @Test
    void trigger_noDependencies_ordersByCreatedAtAndDelegatesToRunner() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        TestCase newer = TestCase.builder().id(id1).endpoint(getEndpoint).expectedStatus(200)
                .createdAt(Instant.parse("2026-01-02T00:00:00Z")).build();
        TestCase older = TestCase.builder().id(id2).endpoint(getEndpoint).expectedStatus(200)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z")).build();
        when(testCaseRepository.findAllByIdInAndEndpointProject(anyList(), any(Project.class)))
                .thenReturn(List.of(newer, older));
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of());
        when(testCaseAssertionRepository.findAllByTestCaseIn(anyList())).thenReturn(List.of());

        TestExecution saved = TestExecution.builder().id(UUID.randomUUID()).project(project)
                .status(ExecutionStatus.PENDING).startedAt(Instant.now()).build();
        when(testExecutionRepository.save(any(TestExecution.class))).thenReturn(saved);

        TestExecutionResponse response = testExecutionService.trigger(projectId, new TestExecutionRequest(List.of(id1, id2)));

        assertThat(response.id()).isEqualTo(saved.getId());
        assertThat(response.status()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(response.autoIncludedTestCaseIds()).isEmpty();

        ArgumentCaptor<List<TestCase>> orderedCaptor = ArgumentCaptor.forClass(List.class);
        verify(testExecutionRunner).runInBackground(
                any(TestExecution.class), orderedCaptor.capture(), any(Project.class), anyMap(), anySetOfUUID(), anyAssertionMap(), org.mockito.ArgumentMatchers.eq(false));
        assertThat(orderedCaptor.getValue()).extracting(TestCase::getId).containsExactly(id2, id1);

        // Lịch sử (Module 8) - cả 2 test case đã chọn cùng thuộc 1 endpoint, chỉ ghi 1 dòng liên kết.
        ArgumentCaptor<List<TestExecutionEndpoint>> linksCaptor = ArgumentCaptor.forClass(List.class);
        verify(testExecutionEndpointRepository).saveAll(linksCaptor.capture());
        assertThat(linksCaptor.getValue()).extracting(TestExecutionEndpoint::getEndpoint).containsExactly(getEndpoint);
    }

    @Test
    void trigger_consumerDependsOnUnselectedSource_autoIncludesSourceAndOrdersItFirst() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        TestCase source = TestCase.builder().id(UUID.randomUUID()).endpoint(postEndpoint).expectedStatus(201)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z")).build();
        TestCase consumer = TestCase.builder().id(UUID.randomUUID()).endpoint(getEndpoint).expectedStatus(200)
                .resolvedPath("/pet/{{petId}}").createdAt(Instant.parse("2026-01-02T00:00:00Z")).build();

        when(testCaseRepository.findAllByIdInAndEndpointProject(List.of(consumer.getId()), project))
                .thenReturn(List.of(consumer));
        TestCaseDependency dependency = TestCaseDependency.builder()
                .testCase(consumer).dependsOnTestCase(source).jsonPath("$.id").placeholderName("petId").build();
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project)).thenReturn(List.of(dependency));
        when(testCaseAssertionRepository.findAllByTestCaseIn(anyList())).thenReturn(List.of());

        TestExecution saved = TestExecution.builder().id(UUID.randomUUID()).project(project)
                .status(ExecutionStatus.PENDING).startedAt(Instant.now()).build();
        when(testExecutionRepository.save(any(TestExecution.class))).thenReturn(saved);

        TestExecutionResponse response = testExecutionService.trigger(projectId, new TestExecutionRequest(List.of(consumer.getId())));

        assertThat(response.autoIncludedTestCaseIds()).containsExactly(source.getId());

        ArgumentCaptor<List<TestCase>> orderedCaptor = ArgumentCaptor.forClass(List.class);
        verify(testExecutionRunner).runInBackground(
                any(TestExecution.class), orderedCaptor.capture(), any(Project.class), anyMap(), anySetOfUUID(), anyAssertionMap(), org.mockito.ArgumentMatchers.eq(false));
        assertThat(orderedCaptor.getValue()).extracting(TestCase::getId).containsExactly(source.getId(), consumer.getId());

        // Lịch sử (Module 8) - consumer thuộc getEndpoint, source chỉ có mặt do auto-included (chaining)
        // thuộc postEndpoint - cả 2 endpoint đều phải có dòng liên kết, không chỉ endpoint được chọn tường minh.
        ArgumentCaptor<List<TestExecutionEndpoint>> linksCaptor = ArgumentCaptor.forClass(List.class);
        verify(testExecutionEndpointRepository).saveAll(linksCaptor.capture());
        assertThat(linksCaptor.getValue())
                .extracting(TestExecutionEndpoint::getEndpoint)
                .containsExactlyInAnyOrder(getEndpoint, postEndpoint);
    }

    @Test
    void trigger_cycleBetweenSelectedTestCases_throwsInvalidRequest() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        TestCase a = TestCase.builder().id(UUID.randomUUID()).endpoint(getEndpoint).expectedStatus(200)
                .createdAt(Instant.now()).build();
        TestCase b = TestCase.builder().id(UUID.randomUUID()).endpoint(getEndpoint).expectedStatus(200)
                .createdAt(Instant.now()).build();
        when(testCaseRepository.findAllByIdInAndEndpointProject(List.of(a.getId(), b.getId()), project))
                .thenReturn(List.of(a, b));

        TestCaseDependency aDependsOnB = TestCaseDependency.builder()
                .testCase(a).dependsOnTestCase(b).jsonPath("$.id").placeholderName("x").build();
        TestCaseDependency bDependsOnA = TestCaseDependency.builder()
                .testCase(b).dependsOnTestCase(a).jsonPath("$.id").placeholderName("y").build();
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project))
                .thenReturn(List.of(aDependsOnB, bDependsOnA));

        assertThatThrownBy(() -> testExecutionService.trigger(projectId, new TestExecutionRequest(List.of(a.getId(), b.getId()))))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(testExecutionRepository, testExecutionRunner);
    }

    @Test
    void trigger_deleteSharesSourceWithSibling_deleteRunsLast() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        TestCase source = TestCase.builder().id(UUID.randomUUID()).endpoint(postEndpoint).expectedStatus(201)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z")).build();
        TestCase getPet = TestCase.builder().id(UUID.randomUUID()).endpoint(getEndpoint).expectedStatus(200)
                .resolvedPath("/pet/{{petId}}").createdAt(Instant.parse("2026-01-02T00:00:00Z")).build();
        // deletePet duoc tao TRUOC getPet (createdAt som hon) - neu chi tie-break bang createdAt no
        // se bi xep truoc getPet, nhung canh ngam dinh phai day no xuong cuoi.
        TestCase deletePet = TestCase.builder().id(UUID.randomUUID()).endpoint(deleteEndpoint).expectedStatus(200)
                .resolvedPath("/pet/{{petId}}").createdAt(Instant.parse("2026-01-01T12:00:00Z")).build();

        when(testCaseRepository.findAllByIdInAndEndpointProject(List.of(getPet.getId(), deletePet.getId()), project))
                .thenReturn(List.of(getPet, deletePet));

        TestCaseDependency getDependsOnSource = TestCaseDependency.builder()
                .testCase(getPet).dependsOnTestCase(source).jsonPath("$.id").placeholderName("petId").build();
        TestCaseDependency deleteDependsOnSource = TestCaseDependency.builder()
                .testCase(deletePet).dependsOnTestCase(source).jsonPath("$.id").placeholderName("petId").build();
        when(testCaseDependencyRepository.findAllByTestCaseEndpointProject(project))
                .thenReturn(List.of(getDependsOnSource, deleteDependsOnSource));
        when(testCaseAssertionRepository.findAllByTestCaseIn(anyList())).thenReturn(List.of());

        TestExecution saved = TestExecution.builder().id(UUID.randomUUID()).project(project)
                .status(ExecutionStatus.PENDING).startedAt(Instant.now()).build();
        when(testExecutionRepository.save(any(TestExecution.class))).thenReturn(saved);

        testExecutionService.trigger(projectId, new TestExecutionRequest(List.of(getPet.getId(), deletePet.getId())));

        ArgumentCaptor<List<TestCase>> orderedCaptor = ArgumentCaptor.forClass(List.class);
        verify(testExecutionRunner).runInBackground(
                any(TestExecution.class), orderedCaptor.capture(), any(Project.class), anyMap(), anySetOfUUID(), anyAssertionMap(), org.mockito.ArgumentMatchers.eq(false));
        List<TestCase> ordered = orderedCaptor.getValue();
        assertThat(ordered).hasSize(3);
        assertThat(ordered.get(ordered.size() - 1).getId()).isEqualTo(deletePet.getId());
    }

    @Test
    void getExecution_notFound_throwsTestExecutionNotFound() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        UUID executionId = UUID.randomUUID();
        when(testExecutionRepository.findByIdAndProject(executionId, project)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testExecutionService.getExecution(projectId, executionId))
                .isInstanceOf(TestExecutionNotFoundException.class);

        verify(testResultRepository, never()).findAllByExecutionOrderByTestCaseCreatedAt(any());
    }

    @Test
    void getExecution_found_returnsResultsFromRepository() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        UUID executionId = UUID.randomUUID();
        TestExecution execution = TestExecution.builder().id(executionId).project(project)
                .status(ExecutionStatus.COMPLETED).startedAt(Instant.now()).finishedAt(Instant.now()).build();
        when(testExecutionRepository.findByIdAndProject(executionId, project)).thenReturn(Optional.of(execution));
        when(testResultRepository.findAllByExecutionOrderByTestCaseCreatedAt(execution)).thenReturn(List.of());

        TestExecutionResponse response = testExecutionService.getExecution(projectId, executionId);

        assertThat(response.id()).isEqualTo(executionId);
        assertThat(response.status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(response.results()).isEmpty();
    }

    /** Module 16 gđ2 - độ ổn định được tính khi xem lượt chạy ĐÃ XONG, đưa vào đúng kết quả theo id. */
    @Test
    void getExecution_finished_attachesStabilityByResultId() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        UUID executionId = UUID.randomUUID();
        TestExecution execution = TestExecution.builder().id(executionId).project(project)
                .status(ExecutionStatus.COMPLETED).startedAt(Instant.now()).finishedAt(Instant.now()).build();
        Endpoint endpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("DELETE").path("/x").build();
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).name("n").expectedStatus(204).build();
        com.aiapitesting.backend.entity.TestResult result = com.aiapitesting.backend.entity.TestResult.builder()
                .id(UUID.randomUUID()).testCase(testCase).execution(execution)
                .status(com.aiapitesting.backend.entity.TestResultStatus.PASSED).build();
        com.aiapitesting.backend.dto.response.StabilityResponse stability = new com.aiapitesting.backend.dto.response.StabilityResponse(
                com.aiapitesting.backend.entity.StabilityState.FLAKY, 5, 3, 2, 4, 0.6, null, true);
        when(testExecutionRepository.findByIdAndProject(executionId, project)).thenReturn(Optional.of(execution));
        when(testResultRepository.findAllByExecutionOrderByTestCaseCreatedAt(execution)).thenReturn(List.of(result));
        when(executionFailureAnalyzer.stabilityOf(List.of(result))).thenReturn(Map.of(result.getId(), stability));

        TestExecutionResponse response = testExecutionService.getExecution(projectId, executionId);

        assertThat(response.results()).singleElement().satisfies(r -> {
            assertThat(r.stability()).isEqualTo(stability);
            assertThat(r.endpointMethod()).isEqualTo("DELETE");
        });
    }

    /** Trang poll mỗi 1,5 giây lúc đang chạy - không được thêm truy vấn lịch sử vào mỗi lần poll. */
    @Test
    void getExecution_stillRunning_doesNotComputeStability() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        UUID executionId = UUID.randomUUID();
        TestExecution execution = TestExecution.builder().id(executionId).project(project)
                .status(ExecutionStatus.RUNNING).startedAt(Instant.now()).build();
        when(testExecutionRepository.findByIdAndProject(executionId, project)).thenReturn(Optional.of(execution));
        when(testResultRepository.findAllByExecutionOrderByTestCaseCreatedAt(execution)).thenReturn(List.of());

        testExecutionService.getExecution(projectId, executionId);

        org.mockito.Mockito.verifyNoInteractions(executionFailureAnalyzer);
    }

    // ---- Module 17 (G4) - kết quả theo trang ----

    private TestExecution execution(UUID executionId, ExecutionStatus status) {
        return TestExecution.builder().id(executionId).project(project).status(status).startedAt(Instant.now()).build();
    }

    private com.aiapitesting.backend.entity.TestResult result(TestExecution execution, com.aiapitesting.backend.entity.TestResultStatus status) {
        Endpoint endpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/pets").build();
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).name("n").expectedStatus(200).build();
        return com.aiapitesting.backend.entity.TestResult.builder().id(UUID.randomUUID()).testCase(testCase).execution(execution)
                .status(status).build();
    }

    @SuppressWarnings("unchecked")
    @Test
    void getExecutionPage_finished_usesPageQuery_computesStabilityOnlyForPageRows_andSummaryFromAggregates() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        UUID executionId = UUID.randomUUID();
        TestExecution execution = execution(executionId, ExecutionStatus.COMPLETED);
        com.aiapitesting.backend.entity.TestResult failed = result(execution, com.aiapitesting.backend.entity.TestResultStatus.FAILED);
        when(testExecutionRepository.findByIdAndProject(executionId, project)).thenReturn(Optional.of(execution));
        when(testResultRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(failed),
                        org.springframework.data.domain.PageRequest.of(1, 50), 120));
        when(executionFailureAnalyzer.stabilityOf(List.of(failed))).thenReturn(Map.of());
        TestResultRepository.ExecutionStatusCount failedRow = statusCount(com.aiapitesting.backend.entity.TestResultStatus.FAILED, 30);
        TestResultRepository.ExecutionStatusCount passedRow = statusCount(com.aiapitesting.backend.entity.TestResultStatus.PASSED, 90);
        when(testResultRepository.countByExecutionsGroupedByStatus(List.of(execution))).thenReturn(List.of(failedRow, passedRow));
        TestResultRepository.FailureCauseCount causeRow = causeCount(com.aiapitesting.backend.entity.FailureCause.API_WRONG_STATUS, 7);
        when(testResultRepository.countByExecutionGroupedByFailureCause(execution)).thenReturn(List.of(causeRow));
        when(testResultRepository.countByExecutionAndUnstableTrue(execution)).thenReturn(2L);
        when(bugReportRepository.countFailedResultsWithBugByExecution(execution)).thenReturn(10L);
        UUID autoIncluded = UUID.randomUUID();
        when(testResultRepository.findAutoIncludedTestCaseIds(execution)).thenReturn(List.of(autoIncluded));
        UUID resultWithBug = UUID.randomUUID();
        when(bugReportRepository.findSourceTestResultIdsByExecution(execution)).thenReturn(List.of(resultWithBug));

        com.aiapitesting.backend.dto.response.TestExecutionPageResponse response =
                testExecutionService.getExecutionPage(projectId, executionId, 1, null, null, null, null, null);

        assertThat(response.results().data()).hasSize(1);
        assertThat(response.results().page()).isEqualTo(1);
        assertThat(response.results().totalElements()).isEqualTo(120);
        assertThat(response.results().totalPages()).isEqualTo(3);
        assertThat(response.summary().total()).isEqualTo(120);
        assertThat(response.summary().statusCounts()).containsEntry(com.aiapitesting.backend.entity.TestResultStatus.FAILED, 30L)
                .containsEntry(com.aiapitesting.backend.entity.TestResultStatus.PASSED, 90L);
        assertThat(response.summary().failureCauseCounts()).containsEntry(com.aiapitesting.backend.entity.FailureCause.API_WRONG_STATUS, 7L);
        assertThat(response.summary().unstableCount()).isEqualTo(2);
        assertThat(response.summary().failedWithoutBugCount()).isEqualTo(20);
        assertThat(response.summary().bugResultIds()).containsExactly(resultWithBug);
        assertThat(response.autoIncludedTestCaseIds()).containsExactly(autoIncluded);
        // Không bao giờ nạp cả lần chạy.
        verify(testResultRepository, never()).findAllByExecutionOrderByTestCaseCreatedAt(any());
        verify(executionFailureAnalyzer).stabilityOf(List.of(failed));
    }

    @SuppressWarnings("unchecked")
    @Test
    void getExecutionPage_stillRunning_doesNotComputeStability_andEmptyPageSkipsBugLookup() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        UUID executionId = UUID.randomUUID();
        TestExecution execution = execution(executionId, ExecutionStatus.RUNNING);
        when(testExecutionRepository.findByIdAndProject(executionId, project)).thenReturn(Optional.of(execution));
        when(testResultRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 50), 0));

        com.aiapitesting.backend.dto.response.TestExecutionPageResponse response =
                testExecutionService.getExecutionPage(projectId, executionId, 0, 50, null, null, null, null);

        assertThat(response.results().data()).isEmpty();
        assertThat(response.results().totalPages()).isZero();
        assertThat(response.summary().total()).isZero();
        assertThat(response.summary().failedWithoutBugCount()).isZero();
        verifyNoInteractions(executionFailureAnalyzer);
        verify(bugReportRepository, never()).findAllBySourceTestResultIdIn(anyList());
    }

    @Test
    void getExecutionPage_rejectsBadPagingAndUnknownExecution() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        UUID executionId = UUID.randomUUID();
        when(testExecutionRepository.findByIdAndProject(executionId, project)).thenReturn(Optional.of(execution(executionId, ExecutionStatus.COMPLETED)));

        assertThatThrownBy(() -> testExecutionService.getExecutionPage(projectId, executionId, -1, 50, null, null, null, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> testExecutionService.getExecutionPage(projectId, executionId, 0, 0, null, null, null, null))
                .isInstanceOf(InvalidRequestException.class);

        UUID missing = UUID.randomUUID();
        when(testExecutionRepository.findByIdAndProject(missing, project)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> testExecutionService.getExecutionPage(projectId, missing, 0, 50, null, null, null, null))
                .isInstanceOf(TestExecutionNotFoundException.class);
    }

    private static TestResultRepository.ExecutionStatusCount statusCount(com.aiapitesting.backend.entity.TestResultStatus status, long count) {
        return new TestResultRepository.ExecutionStatusCount() {
            @Override
            public UUID getExecutionId() {
                return UUID.randomUUID();
            }

            @Override
            public com.aiapitesting.backend.entity.TestResultStatus getStatus() {
                return status;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    private static TestResultRepository.FailureCauseCount causeCount(com.aiapitesting.backend.entity.FailureCause cause, long count) {
        return new TestResultRepository.FailureCauseCount() {
            @Override
            public com.aiapitesting.backend.entity.FailureCause getCause() {
                return cause;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, List<DependencyEdge>> anyMap() {
        return org.mockito.ArgumentMatchers.any(Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> anySetOfUUID() {
        return org.mockito.ArgumentMatchers.any(Set.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, List<AssertionSpec>> anyAssertionMap() {
        return org.mockito.ArgumentMatchers.any(Map.class);
    }
}
