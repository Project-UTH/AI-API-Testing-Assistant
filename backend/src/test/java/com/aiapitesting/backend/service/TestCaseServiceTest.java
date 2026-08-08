package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.TestCaseRequest;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.TestCaseNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestCaseServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @InjectMocks
    private TestCaseService testCaseService;

    private Project project;
    private Endpoint endpoint;
    private UUID projectId;
    private UUID endpointId;
    private UUID testCaseId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        endpointId = UUID.randomUUID();
        testCaseId = UUID.randomUUID();
        project = Project.builder().id(projectId).build();
        endpoint = Endpoint.builder().id(endpointId).project(project).method("POST").path("/users").build();
    }

    @Test
    void listByProject_mapsAllTestCasesForProject() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        TestCase testCase = TestCase.builder()
                .id(testCaseId).endpoint(endpoint).name("Positive").expectedStatus(200)
                .source(TestCaseSource.AI_GENERATED).build();
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(testCase));

        List<TestCaseResponse> result = testCaseService.listByProject(projectId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(testCaseId);
        assertThat(result.get(0).endpointPath()).isEqualTo("/users");
    }

    @Test
    void create_savesManualTestCase() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TestCaseRequest request = new TestCaseRequest(
                "Test thu cong", "mo ta", "{\"Content-Type\":\"application/json\"}", "{}", 400);

        TestCaseResponse response = testCaseService.create(projectId, endpointId, request);

        assertThat(response.name()).isEqualTo("Test thu cong");
        assertThat(response.source()).isEqualTo(TestCaseSource.MANUAL);

        ArgumentCaptor<TestCase> captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo(TestCaseSource.MANUAL);
        assertThat(captor.getValue().getEndpoint()).isEqualTo(endpoint);
    }

    @Test
    void create_endpointNotInProject_throwsEndpointNotFound() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.empty());

        TestCaseRequest request = new TestCaseRequest("Ten", null, null, null, 200);

        assertThatThrownBy(() -> testCaseService.create(projectId, endpointId, request))
                .isInstanceOf(EndpointNotFoundException.class);
    }

    @Test
    void update_changesFieldsButKeepsSource() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        TestCase existing = TestCase.builder()
                .id(testCaseId).endpoint(endpoint).name("Cu").expectedStatus(200)
                .source(TestCaseSource.AI_GENERATED).build();
        when(testCaseRepository.findByIdAndEndpoint(testCaseId, endpoint)).thenReturn(Optional.of(existing));
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TestCaseRequest request = new TestCaseRequest("Ten moi", "mo ta moi", null, null, 404);
        TestCaseResponse response = testCaseService.update(projectId, endpointId, testCaseId, request);

        assertThat(response.name()).isEqualTo("Ten moi");
        assertThat(response.expectedStatus()).isEqualTo(404);
        // Sua 1 case AI sinh khong bien no thanh MANUAL - giu nguyen nguon goc
        assertThat(response.source()).isEqualTo(TestCaseSource.AI_GENERATED);
    }

    @Test
    void update_testCaseNotBelongingToEndpoint_throwsTestCaseNotFound() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        when(testCaseRepository.findByIdAndEndpoint(testCaseId, endpoint)).thenReturn(Optional.empty());

        TestCaseRequest request = new TestCaseRequest("Ten", null, null, null, 200);

        assertThatThrownBy(() -> testCaseService.update(projectId, endpointId, testCaseId, request))
                .isInstanceOf(TestCaseNotFoundException.class);
    }

    @Test
    void delete_removesTestCase() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        TestCase existing = TestCase.builder()
                .id(testCaseId).endpoint(endpoint).name("Ten").expectedStatus(200)
                .source(TestCaseSource.MANUAL).build();
        when(testCaseRepository.findByIdAndEndpoint(testCaseId, endpoint)).thenReturn(Optional.of(existing));

        testCaseService.delete(projectId, endpointId, testCaseId);

        verify(testCaseRepository).delete(existing);
    }

    @Test
    void delete_testCaseNotFound_throwsTestCaseNotFound() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        when(testCaseRepository.findByIdAndEndpoint(testCaseId, endpoint)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testCaseService.delete(projectId, endpointId, testCaseId))
                .isInstanceOf(TestCaseNotFoundException.class);
    }
}
