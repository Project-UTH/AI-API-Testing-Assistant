package com.aiapitesting.backend.service;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.ForbiddenException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.ProjectNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.ProjectRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.security.AesEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private TestExecutionRepository testExecutionRepository;

    @Mock
    private TestCaseDependencyRepository testCaseDependencyRepository;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private AesEncryptionService aesEncryptionService;

    @InjectMocks
    private ProjectService projectService;

    private User owner;
    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(UUID.randomUUID()).email("owner@example.com").build();
        projectId = UUID.randomUUID();
        project = Project.builder().id(projectId).owner(owner).build();
    }

    @Test
    void delete_removesTestResultsExecutionsTestCasesThenEndpointsThenProject() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(currentUserService.getCurrentUser()).thenReturn(owner);

        projectService.delete(projectId);

        // Dọn TestResult, rồi TestExecution, rồi test case, rồi endpoint, rồi project - tránh vi
        // phạm khoá ngoại (test_results.test_case_id / test_executions.project_id /
        // test_cases.endpoint_id / endpoints.project_id)
        InOrder inOrder = inOrder(testResultRepository, testExecutionRepository, testCaseDependencyRepository,
                testCaseRepository, endpointRepository, projectRepository);
        inOrder.verify(testResultRepository).deleteAllByTestCaseEndpointProject(project);
        inOrder.verify(testExecutionRepository).deleteAllByProject(project);
        inOrder.verify(testCaseDependencyRepository).deleteAllByProject(project);
        inOrder.verify(testCaseRepository).deleteAllByEndpointProject(project);
        inOrder.verify(endpointRepository).deleteAllByProject(project);
        inOrder.verify(projectRepository).delete(project);
    }

    @Test
    void delete_projectNotFound_throwsAndSkipsCascade() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.delete(projectId))
                .isInstanceOf(ProjectNotFoundException.class);

        verifyNoInteractions(testResultRepository);
        verifyNoInteractions(testExecutionRepository);
        verifyNoInteractions(testCaseRepository);
        verifyNoInteractions(endpointRepository);
        verify(projectRepository, never()).delete(any());
    }

    @Test
    void delete_notOwner_throwsForbiddenAndSkipsCascade() {
        User otherUser = User.builder().id(UUID.randomUUID()).email("other@example.com").build();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(currentUserService.getCurrentUser()).thenReturn(otherUser);

        assertThatThrownBy(() -> projectService.delete(projectId))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(testResultRepository);
        verifyNoInteractions(testExecutionRepository);
        verifyNoInteractions(testCaseRepository);
        verifyNoInteractions(endpointRepository);
        verify(projectRepository, never()).delete(any());
    }

    @Test
    void updateTargetAuth_withBearerToken_encryptsAndSaves() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        when(aesEncryptionService.encrypt("secret-token")).thenReturn("encrypted-token");
        when(projectRepository.save(project)).thenReturn(project);

        projectService.updateTargetAuth(projectId, TargetAuthType.BEARER_TOKEN, "secret-token");

        assertThat(project.getTargetAuthType()).isEqualTo(TargetAuthType.BEARER_TOKEN);
        assertThat(project.getTargetAuthValueEncrypted()).isEqualTo("encrypted-token");
    }

    @Test
    void updateTargetAuth_withNone_clearsExistingAuth() {
        project.setTargetAuthType(TargetAuthType.API_KEY);
        project.setTargetAuthValueEncrypted("old-encrypted-value");
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(currentUserService.getCurrentUser()).thenReturn(owner);
        when(projectRepository.save(project)).thenReturn(project);

        projectService.updateTargetAuth(projectId, TargetAuthType.NONE, null);

        assertThat(project.getTargetAuthType()).isEqualTo(TargetAuthType.NONE);
        assertThat(project.getTargetAuthValueEncrypted()).isNull();
        verifyNoInteractions(aesEncryptionService);
    }

    @Test
    void updateTargetAuth_authTypeWithoutValue_throwsInvalidRequest() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(currentUserService.getCurrentUser()).thenReturn(owner);

        assertThatThrownBy(() -> projectService.updateTargetAuth(projectId, TargetAuthType.API_KEY, " "))
                .isInstanceOf(InvalidRequestException.class);

        verify(projectRepository, never()).save(any());
    }
}
