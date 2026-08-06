package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.exception.AiGenerationFailedException;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.ForbiddenException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestCaseGenerationServiceTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    @Mock
    private ProjectService projectService;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @InjectMocks
    private TestCaseGenerationService testCaseGenerationService;

    private Project project;
    private Endpoint endpoint;
    private UUID projectId;
    private UUID endpointId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        endpointId = UUID.randomUUID();
        project = Project.builder().id(projectId).build();
        endpoint = Endpoint.builder()
                .id(endpointId)
                .project(project)
                .method("POST")
                .path("/users")
                .summary("Create user")
                .schema("{\"requestBody\":{}}")
                .build();
    }

    @Test
    void generate_success_deletesOldCasesThenSavesNewOnes() throws Exception {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        stubAiResponse(List.of(
                new TestCaseGenerationService.GeneratedTestCase(
                        "Positive - Tao user hop le", "mo ta",
                        Map.of("Content-Type", "application/json"), "{\"email\":\"a@b.com\"}", 201),
                new TestCaseGenerationService.GeneratedTestCase(
                        "Negative - Thieu email", "mo ta",
                        Map.of("Content-Type", "application/json"), "{}", 400)
        ));
        when(testCaseRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        CompletableFuture<List<TestCaseResponse>> future = testCaseGenerationService.generate(projectId, endpointId);
        List<TestCaseResponse> result = future.get();

        assertThat(result).hasSize(2);
        verify(testCaseRepository).deleteAllByEndpoint(endpoint);
        verify(testCaseRepository).saveAll(anyList());
    }

    @Test
    void generate_aiReturnsEmptyList_throwsAiGenerationFailed() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        stubAiResponse(List.of());

        assertThatThrownBy(() -> testCaseGenerationService.generate(projectId, endpointId))
                .isInstanceOf(AiGenerationFailedException.class);

        verify(testCaseRepository, never()).saveAll(anyList());
    }

    @Test
    void generate_aiReturnsInvalidStatus_throwsAiGenerationFailed() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        stubAiResponse(List.of(new TestCaseGenerationService.GeneratedTestCase(
                "Positive", "mo ta", Map.of(), "{}", 999)));

        assertThatThrownBy(() -> testCaseGenerationService.generate(projectId, endpointId))
                .isInstanceOf(AiGenerationFailedException.class);

        verify(testCaseRepository, never()).saveAll(anyList());
    }

    @Test
    void generate_endpointNotInProject_throwsEndpointNotFound() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testCaseGenerationService.generate(projectId, endpointId))
                .isInstanceOf(EndpointNotFoundException.class);

        verifyNoInteractions(testCaseRepository);
    }

    @Test
    void generate_notOwner_throwsForbidden() {
        when(projectService.getOwnedProject(projectId)).thenThrow(new ForbiddenException("Ban khong co quyen truy cap project nay"));

        assertThatThrownBy(() -> testCaseGenerationService.generate(projectId, endpointId))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(endpointRepository);
        verifyNoInteractions(testCaseRepository);
    }

    @SuppressWarnings("unchecked")
    private void stubAiResponse(List<TestCaseGenerationService.GeneratedTestCase> result) {
        when(chatClient.prompt(any(Prompt.class)).call()
                .entity(any(ParameterizedTypeReference.class)))
                .thenReturn(result);
    }
}
