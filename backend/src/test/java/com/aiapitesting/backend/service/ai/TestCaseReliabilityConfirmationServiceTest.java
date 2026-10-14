package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.ai.ReliabilityConfirmation;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseReliability;
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
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.ParameterizedTypeReference;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestCaseReliabilityConfirmationServiceTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    @InjectMocks
    private TestCaseReliabilityConfirmationService service;

    private Endpoint endpoint;

    @BeforeEach
    void setUp() {
        Project project = Project.builder().id(UUID.randomUUID()).build();
        endpoint = Endpoint.builder()
                .id(UUID.randomUUID()).project(project).method("POST").path("/orders")
                .schema("{\"requestBody\":{}}").createdAt(Instant.now()).build();
    }

    private TestCase flaggedCase(String name, Integer expectedStatus) {
        return TestCase.builder()
                .id(UUID.randomUUID()).endpoint(endpoint).name(name).expectedStatus(expectedStatus)
                .requestBody("{}").reliabilityStatus(TestCaseReliability.NEEDS_REVIEW)
                .reliabilityNotes("[\"vi pham minimum\"]").createdAt(Instant.now()).build();
    }

    @SuppressWarnings("unchecked")
    private void stubAiResponse(List<ReliabilityConfirmation> result, Usage usage) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
        ChatResponse chatResponse = new ChatResponse(List.of(), metadata);
        when(chatClient.prompt(any(Prompt.class)).call()
                .responseEntity(any(ParameterizedTypeReference.class)))
                .thenReturn(new ResponseEntity<>(chatResponse, result));
    }

    @Test
    void confirm_emptyFlaggedList_returnsNullWithoutCallingAi() {
        Usage usage = service.confirm(endpoint, List.of());

        assertThat(usage).isNull();
        org.mockito.Mockito.verifyNoInteractions(chatClient);
    }

    @Test
    void confirm_aiConfirmsRealIssue_setsConfirmedTrueAndNote() {
        TestCase flagged = flaggedCase("Positive - quantity sai", 201);
        stubAiResponse(List.of(new ReliabilityConfirmation(0, true, "Day la loi that, quantity vi pham minimum")),
                new DefaultUsage(50, 20, 70));

        Usage usage = service.confirm(endpoint, List.of(flagged));

        assertThat(usage.getTotalTokens()).isEqualTo(70);
        assertThat(flagged.getReliabilityAiConfirmed()).isTrue();
        assertThat(flagged.getReliabilityAiNote()).contains("loi that");
    }

    @Test
    void confirm_aiRefutesAsFalsePositive_setsConfirmedFalse() {
        TestCase flagged = flaggedCase("Security - SQL injection vao email", 400);
        stubAiResponse(List.of(new ReliabilityConfirmation(0, false, "Chu dich cua case Security, khong phai loi")),
                new DefaultUsage(50, 20, 70));

        service.confirm(endpoint, List.of(flagged));

        assertThat(flagged.getReliabilityAiConfirmed()).isFalse();
        assertThat(flagged.getReliabilityAiNote()).contains("Chu dich");
    }

    @Test
    void confirm_multipleFlaggedCases_mapsByIndexCorrectly() {
        TestCase case0 = flaggedCase("Case A", 201);
        TestCase case1 = flaggedCase("Case B", 201);
        stubAiResponse(List.of(
                new ReliabilityConfirmation(1, true, "B that su sai"),
                new ReliabilityConfirmation(0, false, "A la chu dich")
        ), new DefaultUsage(10, 10, 20));

        service.confirm(endpoint, List.of(case0, case1));

        assertThat(case0.getReliabilityAiConfirmed()).isFalse();
        assertThat(case0.getReliabilityAiNote()).contains("chu dich");
        assertThat(case1.getReliabilityAiConfirmed()).isTrue();
        assertThat(case1.getReliabilityAiNote()).contains("that su sai");
    }

    @Test
    void confirm_outOfRangeIndex_ignoredSafely() {
        TestCase flagged = flaggedCase("Case A", 201);
        stubAiResponse(List.of(new ReliabilityConfirmation(5, true, "index sai")), new DefaultUsage(10, 10, 20));

        service.confirm(endpoint, List.of(flagged));

        assertThat(flagged.getReliabilityAiConfirmed()).isNull();
        assertThat(flagged.getReliabilityAiNote()).isNull();
    }

    @Test
    void confirm_aiCallThrows_returnsNullAndLeavesFieldsUnset_doesNotThrow() {
        TestCase flagged = flaggedCase("Case A", 201);
        when(chatClient.prompt(any(Prompt.class)).call().responseEntity(any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("AI provider loi"));

        Usage usage = service.confirm(endpoint, List.of(flagged));

        assertThat(usage).isNull();
        assertThat(flagged.getReliabilityAiConfirmed()).isNull();
    }
}
