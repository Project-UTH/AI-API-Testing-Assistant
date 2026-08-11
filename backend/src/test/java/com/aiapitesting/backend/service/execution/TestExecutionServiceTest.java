package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.dto.request.TestExecutionRequest;
import com.aiapitesting.backend.dto.response.TestExecutionResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseDependency;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.TestExecutionNotFoundException;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
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
    private TestExecutionRepository testExecutionRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private TestExecutionRunner testExecutionRunner;

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

        TestExecution saved = TestExecution.builder().id(UUID.randomUUID()).project(project)
                .status(ExecutionStatus.PENDING).startedAt(Instant.now()).build();
        when(testExecutionRepository.save(any(TestExecution.class))).thenReturn(saved);

        TestExecutionResponse response = testExecutionService.trigger(projectId, new TestExecutionRequest(List.of(id1, id2)));

        assertThat(response.id()).isEqualTo(saved.getId());
        assertThat(response.status()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(response.autoIncludedTestCaseIds()).isEmpty();

        ArgumentCaptor<List<TestCase>> orderedCaptor = ArgumentCaptor.forClass(List.class);
        verify(testExecutionRunner).runInBackground(any(TestExecution.class), orderedCaptor.capture(), any(Project.class), anyMap(), anySetOfUUID());
        assertThat(orderedCaptor.getValue()).extracting(TestCase::getId).containsExactly(id2, id1);
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

        TestExecution saved = TestExecution.builder().id(UUID.randomUUID()).project(project)
                .status(ExecutionStatus.PENDING).startedAt(Instant.now()).build();
        when(testExecutionRepository.save(any(TestExecution.class))).thenReturn(saved);

        TestExecutionResponse response = testExecutionService.trigger(projectId, new TestExecutionRequest(List.of(consumer.getId())));

        assertThat(response.autoIncludedTestCaseIds()).containsExactly(source.getId());

        ArgumentCaptor<List<TestCase>> orderedCaptor = ArgumentCaptor.forClass(List.class);
        verify(testExecutionRunner).runInBackground(any(TestExecution.class), orderedCaptor.capture(), any(Project.class), anyMap(), anySetOfUUID());
        assertThat(orderedCaptor.getValue()).extracting(TestCase::getId).containsExactly(source.getId(), consumer.getId());
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

        TestExecution saved = TestExecution.builder().id(UUID.randomUUID()).project(project)
                .status(ExecutionStatus.PENDING).startedAt(Instant.now()).build();
        when(testExecutionRepository.save(any(TestExecution.class))).thenReturn(saved);

        testExecutionService.trigger(projectId, new TestExecutionRequest(List.of(getPet.getId(), deletePet.getId())));

        ArgumentCaptor<List<TestCase>> orderedCaptor = ArgumentCaptor.forClass(List.class);
        verify(testExecutionRunner).runInBackground(any(TestExecution.class), orderedCaptor.capture(), any(Project.class), anyMap(), anySetOfUUID());
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

    @SuppressWarnings("unchecked")
    private static Map<UUID, List<DependencyEdge>> anyMap() {
        return org.mockito.ArgumentMatchers.any(Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> anySetOfUUID() {
        return org.mockito.ArgumentMatchers.any(Set.class);
    }
}
