package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.ai.RegeneratedScenario;
import com.aiapitesting.backend.dto.response.ConfidenceAgreementLevel;
import com.aiapitesting.backend.dto.response.ConfidenceCheckResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.AiGenerationFailedException;
import com.aiapitesting.backend.exception.AiQuotaExceededException;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.TestCaseNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestCaseConfidenceCheckServiceTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    @Mock
    private ProjectService projectService;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestGenerationEventRepository testGenerationEventRepository;

    @InjectMocks
    private TestCaseConfidenceCheckService service;

    private Project project;
    private Endpoint endpoint;
    private TestCase testCase;
    private UUID projectId;
    private UUID endpointId;
    private UUID testCaseId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        endpointId = UUID.randomUUID();
        testCaseId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).email("owner@test.com").build();
        project = Project.builder().id(projectId).owner(owner).build();
        endpoint = Endpoint.builder().id(endpointId).project(project).method("POST").path("/orders")
                .schema("{\"requestBody\":{}}").createdAt(Instant.now()).build();
        testCase = TestCase.builder().id(testCaseId).endpoint(endpoint).name("Positive - tao don hang")
                .description("mo ta").expectedStatus(201).requestBody("{\"quantity\":10}")
                .createdAt(Instant.now()).build();

        org.springframework.test.util.ReflectionTestUtils.setField(service, "dailyTokenLimit", 100_000L);
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        // lenient() - test "endpoint không tồn tại" không bao giờ chạm tới dòng này (fail sớm hơn).
        org.mockito.Mockito.lenient()
                .when(testCaseRepository.findByIdAndEndpoint(testCaseId, endpoint)).thenReturn(Optional.of(testCase));
    }

    @SuppressWarnings("unchecked")
    private void stubAiResponse(RegeneratedScenario result) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(30, 15, 45)).build();
        ChatResponse chatResponse = new ChatResponse(List.of(), metadata);
        when(chatClient.prompt(any(Prompt.class)).call().responseEntity(RegeneratedScenario.class))
                .thenReturn(new ResponseEntity<>(chatResponse, result));
    }

    @Test
    void check_bothExpectedStatusAndShapeMatch_returnsHighAgreement() throws Exception {
        stubAiResponse(new RegeneratedScenario(201, "{\"quantity\":99}", "Dien field quantity hop le"));

        ConfidenceCheckResponse result = service.check(projectId, endpointId, testCaseId).get();

        assertThat(result.agreementLevel()).isEqualTo(ConfidenceAgreementLevel.HIGH);
        assertThat(result.expectedStatusMatches()).isTrue();
        assertThat(result.requestBodyShapeMatches()).isTrue();
    }

    @Test
    void check_onlyExpectedStatusMatches_returnsPartialAgreement() throws Exception {
        // AI doan dung status nhung khac tap field (vd dat them field khac).
        stubAiResponse(new RegeneratedScenario(201, "{\"quantity\":99,\"note\":\"extra\"}", "note"));

        ConfidenceCheckResponse result = service.check(projectId, endpointId, testCaseId).get();

        assertThat(result.agreementLevel()).isEqualTo(ConfidenceAgreementLevel.PARTIAL);
        assertThat(result.expectedStatusMatches()).isTrue();
        assertThat(result.requestBodyShapeMatches()).isFalse();
    }

    @Test
    void check_neitherMatches_returnsLowAgreement() throws Exception {
        stubAiResponse(new RegeneratedScenario(400, "{\"wrongField\":1}", "note"));

        ConfidenceCheckResponse result = service.check(projectId, endpointId, testCaseId).get();

        assertThat(result.agreementLevel()).isEqualTo(ConfidenceAgreementLevel.LOW);
        assertThat(result.expectedStatusMatches()).isFalse();
        assertThat(result.requestBodyShapeMatches()).isFalse();
    }

    @Test
    void check_bothBodiesNull_shapeStillMatches() throws Exception {
        TestCase noBodyCase = TestCase.builder().id(testCaseId).endpoint(endpoint).name("GET case")
                .expectedStatus(200).requestBody(null).createdAt(Instant.now()).build();
        when(testCaseRepository.findByIdAndEndpoint(testCaseId, endpoint)).thenReturn(Optional.of(noBodyCase));
        stubAiResponse(new RegeneratedScenario(200, null, "Khong can body"));

        ConfidenceCheckResponse result = service.check(projectId, endpointId, testCaseId).get();

        assertThat(result.agreementLevel()).isEqualTo(ConfidenceAgreementLevel.HIGH);
    }

    @Test
    void check_quotaExceeded_throwsBeforeCallingAi() {
        when(testGenerationEventRepository.sumTotalTokensByOwnerAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(100_000L);

        assertThatThrownBy(() -> service.check(projectId, endpointId, testCaseId))
                .isInstanceOf(AiQuotaExceededException.class);

        org.mockito.Mockito.verifyNoInteractions(chatClient);
    }

    @Test
    void check_aiCallThrows_wrapsAsAiGenerationFailed() {
        when(chatClient.prompt(any(Prompt.class)).call().responseEntity(RegeneratedScenario.class))
                .thenThrow(new RuntimeException("AI provider loi"));

        assertThatThrownBy(() -> service.check(projectId, endpointId, testCaseId))
                .isInstanceOf(AiGenerationFailedException.class);
    }

    @Test
    void check_endpointNotInProject_throwsEndpointNotFound() {
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.check(projectId, endpointId, testCaseId))
                .isInstanceOf(EndpointNotFoundException.class);
    }

    @Test
    void check_testCaseNotInEndpoint_throwsTestCaseNotFound() {
        when(testCaseRepository.findByIdAndEndpoint(testCaseId, endpoint)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.check(projectId, endpointId, testCaseId))
                .isInstanceOf(TestCaseNotFoundException.class);
    }
}
