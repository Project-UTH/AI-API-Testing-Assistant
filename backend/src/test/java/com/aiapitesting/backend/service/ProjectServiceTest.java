package com.aiapitesting.backend.service;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.ForbiddenException;
import com.aiapitesting.backend.exception.ProjectNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private CurrentUserService currentUserService;

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
    void delete_removesEndpointsBeforeProject() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(currentUserService.getCurrentUser()).thenReturn(owner);

        projectService.delete(projectId);

        InOrder inOrder = inOrder(endpointRepository, projectRepository);
        inOrder.verify(endpointRepository).deleteAllByProject(project);
        inOrder.verify(projectRepository).delete(project);
    }

    @Test
    void delete_projectNotFound_throwsAndSkipsCascade() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.delete(projectId))
                .isInstanceOf(ProjectNotFoundException.class);

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

        verifyNoInteractions(endpointRepository);
        verify(projectRepository, never()).delete(any());
    }
}
