package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.DependencySuggestionResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DependencySuggestionServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestCaseDependencyRepository testCaseDependencyRepository;

    @Spy
    private TestCasePathValidator testCasePathValidator = new TestCasePathValidator();

    @InjectMocks
    private DependencySuggestionService dependencySuggestionService;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = Project.builder().id(projectId).build();
    }

    @Test
    void suggest_trailingParam_findsCreatorEndpointAndEarliest2xxTestCase() {
        Endpoint getPetEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/pet/{petId}").build();
        Endpoint postPetEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/pet").build();
        TestCase consumer = TestCase.builder().id(UUID.randomUUID()).endpoint(getPetEndpoint)
                .resolvedPath("/pet/{{petId}}").build();
        TestCase earliest = TestCase.builder().id(UUID.randomUUID()).endpoint(postPetEndpoint)
                .name("Tao pet hop le").expectedStatus(201).createdAt(Instant.parse("2026-01-01T00:00:00Z")).build();
        TestCase later = TestCase.builder().id(UUID.randomUUID()).endpoint(postPetEndpoint)
                .name("Tao pet khac").expectedStatus(201).createdAt(Instant.parse("2026-01-02T00:00:00Z")).build();

        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(getPetEndpoint.getId(), project)).thenReturn(Optional.of(getPetEndpoint));
        when(testCaseRepository.findByIdAndEndpoint(consumer.getId(), getPetEndpoint)).thenReturn(Optional.of(consumer));
        when(testCaseDependencyRepository.findAllByTestCase(consumer)).thenReturn(List.of());
        when(endpointRepository.findByProjectAndPathAndMethod(project, "/pet", "POST")).thenReturn(Optional.of(postPetEndpoint));
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(postPetEndpoint)).thenReturn(List.of(earliest, later));

        List<DependencySuggestionResponse> suggestions =
                dependencySuggestionService.suggest(projectId, getPetEndpoint.getId(), consumer.getId());

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).paramName()).isEqualTo("petId");
        assertThat(suggestions.get(0).sourceTestCaseId()).isEqualTo(earliest.getId());
        assertThat(suggestions.get(0).suggestedJsonPath()).isEqualTo("$.id");
    }

    @Test
    void suggest_nestedParamInMiddleOfPath_cutsToCorrectPrefix() {
        Endpoint nestedEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET")
                .path("/owner/{ownerId}/pet/{petId}").build();
        Endpoint postOwnerEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/owner").build();
        TestCase consumer = TestCase.builder().id(UUID.randomUUID()).endpoint(nestedEndpoint)
                .resolvedPath("/owner/{{ownerId}}/pet/{{petId}}").build();
        TestCase creatorCase = TestCase.builder().id(UUID.randomUUID()).endpoint(postOwnerEndpoint)
                .name("Tao owner").expectedStatus(201).createdAt(Instant.now()).build();

        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(nestedEndpoint.getId(), project)).thenReturn(Optional.of(nestedEndpoint));
        when(testCaseRepository.findByIdAndEndpoint(consumer.getId(), nestedEndpoint)).thenReturn(Optional.of(consumer));
        when(testCaseDependencyRepository.findAllByTestCase(consumer)).thenReturn(List.of());
        when(endpointRepository.findByProjectAndPathAndMethod(project, "/owner", "POST")).thenReturn(Optional.of(postOwnerEndpoint));
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(postOwnerEndpoint)).thenReturn(List.of(creatorCase));
        // "petId" khong co endpoint POST /owner/{ownerId}/pet tuong ung -> khong co goi y
        when(endpointRepository.findByProjectAndPathAndMethod(project, "/owner/{ownerId}/pet", "POST")).thenReturn(Optional.empty());

        List<DependencySuggestionResponse> suggestions =
                dependencySuggestionService.suggest(projectId, nestedEndpoint.getId(), consumer.getId());

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).paramName()).isEqualTo("ownerId");
        assertThat(suggestions.get(0).sourceTestCaseId()).isEqualTo(creatorCase.getId());
    }

    @Test
    void suggest_noCreatorEndpoint_returnsEmpty() {
        Endpoint getPetEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/pet/{petId}").build();
        TestCase consumer = TestCase.builder().id(UUID.randomUUID()).endpoint(getPetEndpoint)
                .resolvedPath("/pet/{{petId}}").build();

        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(getPetEndpoint.getId(), project)).thenReturn(Optional.of(getPetEndpoint));
        when(testCaseRepository.findByIdAndEndpoint(consumer.getId(), getPetEndpoint)).thenReturn(Optional.of(consumer));
        when(testCaseDependencyRepository.findAllByTestCase(consumer)).thenReturn(List.of());
        when(endpointRepository.findByProjectAndPathAndMethod(project, "/pet", "POST")).thenReturn(Optional.empty());

        List<DependencySuggestionResponse> suggestions =
                dependencySuggestionService.suggest(projectId, getPetEndpoint.getId(), consumer.getId());

        assertThat(suggestions).isEmpty();
    }

    @Test
    void suggest_placeholderAlreadyHasDependency_skipsSuggestion() {
        Endpoint getPetEndpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/pet/{petId}").build();
        TestCase consumer = TestCase.builder().id(UUID.randomUUID()).endpoint(getPetEndpoint)
                .resolvedPath("/pet/{{petId}}").build();

        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(getPetEndpoint.getId(), project)).thenReturn(Optional.of(getPetEndpoint));
        when(testCaseRepository.findByIdAndEndpoint(consumer.getId(), getPetEndpoint)).thenReturn(Optional.of(consumer));

        com.aiapitesting.backend.entity.TestCaseDependency existing = com.aiapitesting.backend.entity.TestCaseDependency.builder()
                .testCase(consumer).placeholderName("petId").jsonPath("$.id").build();
        when(testCaseDependencyRepository.findAllByTestCase(consumer)).thenReturn(List.of(existing));

        List<DependencySuggestionResponse> suggestions =
                dependencySuggestionService.suggest(projectId, getPetEndpoint.getId(), consumer.getId());

        assertThat(suggestions).isEmpty();
    }
}
