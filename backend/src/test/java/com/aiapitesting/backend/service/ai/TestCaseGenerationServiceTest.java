package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.entity.TestGenerationEvent;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.AiGenerationFailedException;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.ForbiddenException;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseAssertionRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.service.ProjectService;
import com.aiapitesting.backend.service.TestCasePathValidator;
import com.aiapitesting.backend.service.TestCaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
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

    @Mock
    private TestCaseDependencyRepository testCaseDependencyRepository;

    @Mock
    private TestCaseAssertionRepository testCaseAssertionRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private BugReportRepository bugReportRepository;

    @Mock
    private TestCasePathValidator testCasePathValidator;

    @Mock
    private TestCaseService testCaseService;

    @Mock
    private TestGenerationEventRepository testGenerationEventRepository;

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
        // owner bắt buộc phải có (khác null như DB thật) - ensureWithinDailyQuota() đọc thẳng
        // project.getOwner().getAiDailyTokenLimitOverride() (Module 11d), thiếu owner sẽ NPE ngay ở
        // MỌI test gọi generate(), không chỉ test quota.
        User owner = User.builder().id(UUID.randomUUID()).email("owner@test.com").build();
        project = Project.builder().id(projectId).owner(owner).build();
        endpoint = Endpoint.builder()
                .id(endpointId)
                .project(project)
                .method("POST")
                .path("/users")
                .summary("Create user")
                .schema("{\"requestBody\":{}}")
                .build();
        // @Value không được @InjectMocks tự set (không phải mock, cần Spring context để resolve
        // placeholder) - mặc định còn lại 0 nếu không set tay, khiến MỌI test generate() luôn bị
        // chặn bởi quota (0 token dùng >= 0 token giới hạn). Set 1 giá trị đủ lớn để các test hiện
        // có (không liên quan quota) giữ nguyên hành vi cũ.
        org.springframework.test.util.ReflectionTestUtils.setField(
                testCaseGenerationService, "dailyTokenLimit", 100_000L);
    }

    @Test
    void generate_success_deletesOldCasesThenSavesNewOnes() throws Exception {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        stubAiResponse(List.of(
                new GeneratedTestCase(
                        "Positive - Tao user hop le", "mo ta",
                        Map.of("Content-Type", "application/json"), "{\"email\":\"a@b.com\"}", 201,
                        "/users", Map.of(), null, null),
                new GeneratedTestCase(
                        "Negative - Thieu email", "mo ta",
                        Map.of("Content-Type", "application/json"), "{}", 400,
                        "/users", Map.of(), null, null)
        ));
        when(testCaseRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        CompletableFuture<List<TestCaseResponse>> future = testCaseGenerationService.generate(projectId, endpointId, null);
        List<TestCaseResponse> result = future.get();

        assertThat(result).hasSize(2);
        // Xoá-và-thay test case AI_GENERATED cũ (không đụng tới test case tự thêm tay), ĐỒNG THỜI
        // dọn sạch nhóm SECURITY cũ còn sót (request=null -> includeSecurity=false lần này).
        verify(testCaseRepository).deleteAllByEndpointAndSourceAndLockedFalse(endpoint, TestCaseSource.AI_GENERATED);
        verify(testCaseRepository).deleteAllByEndpointAndSourceAndLockedFalse(endpoint, TestCaseSource.SECURITY);
        // Dọn TestResult + TestCaseDependency (phía consumer) trước khi xoá - tránh lỗi khoá ngoại
        // 1451 - gọi 2 lần vì cả 2 nhóm (AI_GENERATED và SECURITY) đều được dọn trong lần sinh này.
        verify(testResultRepository, org.mockito.Mockito.times(2)).deleteAllByTestCaseIn(anyList());
        verify(testCaseDependencyRepository, org.mockito.Mockito.times(2)).deleteAllByTestCaseIn(anyList());

        ArgumentCaptor<List<TestCase>> savedCaptor = ArgumentCaptor.forClass(List.class);
        verify(testCaseRepository).saveAll(savedCaptor.capture());
        assertThat(savedCaptor.getValue())
                .extracting(TestCase::getSource)
                .containsOnly(TestCaseSource.AI_GENERATED);

        // Lịch sử (Module 8) - phải lưu đúng 1 sự kiện sinh test case, snapshot chứa đúng nội dung
        // AI vừa sinh, tách biệt hoàn toàn với bảng test_cases sống.
        ArgumentCaptor<TestGenerationEvent> eventCaptor = ArgumentCaptor.forClass(TestGenerationEvent.class);
        verify(testGenerationEventRepository).save(eventCaptor.capture());
        TestGenerationEvent event = eventCaptor.getValue();
        assertThat(event.getEndpoint()).isEqualTo(endpoint);
        assertThat(event.getTestCaseCount()).isEqualTo(2);
        assertThat(event.getSnapshotJson())
                .contains("Positive - Tao user hop le")
                .contains("Negative - Thieu email");
        // Token usage thật lấy từ ChatResponse.getMetadata().getUsage() (Module 11, theo dõi chi
        // phí AI) - phải khớp đúng giá trị stubAiResponse() đã set (100/50/150).
        assertThat(event.getPromptTokens()).isEqualTo(100);
        assertThat(event.getCompletionTokens()).isEqualTo(50);
        assertThat(event.getTotalTokens()).isEqualTo(150);
    }

    @Test
    void generate_dailyQuotaAlreadyReached_throwsAiQuotaExceeded_neverCallsAi() {
        org.springframework.test.util.ReflectionTestUtils.setField(testCaseGenerationService, "dailyTokenLimit", 1000L);
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        when(testGenerationEventRepository.sumTotalTokensByOwnerAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(1000L);

        assertThatThrownBy(() -> testCaseGenerationService.generate(projectId, endpointId, null))
                .isInstanceOf(com.aiapitesting.backend.exception.AiQuotaExceededException.class);

        // Chan SOM truoc khi goi AI - khong ton request AI nao khi da vuot quota (dung tinh chat
        // "chan som" da thiet ke, khong phai chi bat loi sau khi da lo goi).
        verifyNoInteractions(chatClient);
        verify(testCaseRepository, never()).saveAll(anyList());
    }

    @Test
    void generate_ownerHasQuotaOverride_usesOverrideInsteadOfGlobalDefault() {
        // dailyTokenLimit toan he thong rat lon (100_000, set o setUp) nhung owner co override rieng
        // rat nho (500) - phai bi chan theo override, khong phai theo gia tri toan he thong.
        project.getOwner().setAiDailyTokenLimitOverride(500);
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        when(testGenerationEventRepository.sumTotalTokensByOwnerAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(500L);

        assertThatThrownBy(() -> testCaseGenerationService.generate(projectId, endpointId, null))
                .isInstanceOf(com.aiapitesting.backend.exception.AiQuotaExceededException.class)
                .hasMessageContaining("500");

        verifyNoInteractions(chatClient);
    }

    @Test
    void generate_ownerHasHigherQuotaOverride_allowsPastGlobalDefault() throws Exception {
        // Nguoc lai: override CAO hon mac dinh he thong - phai duoc goi AI dù vuot mac dinh he thong.
        org.springframework.test.util.ReflectionTestUtils.setField(testCaseGenerationService, "dailyTokenLimit", 100L);
        project.getOwner().setAiDailyTokenLimitOverride(1_000_000);
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        when(testGenerationEventRepository.sumTotalTokensByOwnerAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(500L); // vuot 100 (mac dinh he thong) nhung con xa 1_000_000 (override)
        stubAiResponse(List.of(new GeneratedTestCase(
                "Positive", "mo ta", Map.of(), "{}", 201, "/users", Map.of(), null, null)));
        when(testCaseRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<TestCaseResponse> result = testCaseGenerationService.generate(projectId, endpointId, null).get();

        assertThat(result).hasSize(1);
    }

    @Test
    void generate_aiReturnsEmptyList_throwsAiGenerationFailed() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        stubAiResponse(List.of());

        assertThatThrownBy(() -> testCaseGenerationService.generate(projectId, endpointId, null))
                .isInstanceOf(AiGenerationFailedException.class);

        verify(testCaseRepository, never()).saveAll(anyList());
    }

    @Test
    void generate_aiReturnsInvalidStatus_throwsAiGenerationFailed() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        stubAiResponse(List.of(new GeneratedTestCase(
                "Positive", "mo ta", Map.of(), "{}", 999, "/users", Map.of(), null, null)));

        assertThatThrownBy(() -> testCaseGenerationService.generate(projectId, endpointId, null))
                .isInstanceOf(AiGenerationFailedException.class);

        verify(testCaseRepository, never()).saveAll(anyList());
    }

    @Test
    void generate_endpointNotInProject_throwsEndpointNotFound() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testCaseGenerationService.generate(projectId, endpointId, null))
                .isInstanceOf(EndpointNotFoundException.class);

        verifyNoInteractions(testCaseRepository);
    }

    @Test
    void generate_notOwner_throwsForbidden() {
        when(projectService.getOwnedProject(projectId)).thenThrow(new ForbiddenException("Ban khong co quyen truy cap project nay"));

        assertThatThrownBy(() -> testCaseGenerationService.generate(projectId, endpointId, null))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(endpointRepository);
        verifyNoInteractions(testCaseRepository);
    }

    @Test
    void generate_existingAiCaseHasDependents_blocksRegenerateBeforeDeleting() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        stubAiResponse(List.of(new GeneratedTestCase(
                "Positive", "mo ta", Map.of(), "{}", 200, "/users", Map.of(), null, null)));

        TestCase existingAiCase = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint)
                .source(TestCaseSource.AI_GENERATED).build();
        when(testCaseRepository.findAllByEndpointAndSourceAndLockedFalse(endpoint, TestCaseSource.AI_GENERATED))
                .thenReturn(List.of(existingAiCase));
        org.mockito.Mockito.doThrow(new com.aiapitesting.backend.exception.TestCaseHasDependentsException("co nguoi phu thuoc"))
                .when(testCaseService).ensureNoDependents(List.of(existingAiCase));

        assertThatThrownBy(() -> testCaseGenerationService.generate(projectId, endpointId, null))
                .isInstanceOf(com.aiapitesting.backend.exception.TestCaseHasDependentsException.class);

        verify(testCaseRepository, never()).deleteAllByEndpointAndSourceAndLockedFalse(any(), any());
        verify(testCaseRepository, never()).saveAll(anyList());
    }

    @Test
    void generate_onlySecuritySelected_existingAiCaseHasDependents_blocksBeforeDeletingOrCallingAi() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));

        // Nhóm AI_GENERATED KHÔNG được tick lần này (chỉ tick Security), nhưng vẫn còn 1 case cũ
        // đang bị test case khác phụ thuộc -> phải chặn xoá TRƯỚC khi đụng gì tới nhóm Security.
        TestCase existingAiCase = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint)
                .source(TestCaseSource.AI_GENERATED).build();
        when(testCaseRepository.findAllByEndpointAndSourceAndLockedFalse(endpoint, TestCaseSource.AI_GENERATED))
                .thenReturn(List.of(existingAiCase));
        org.mockito.Mockito.doThrow(new com.aiapitesting.backend.exception.TestCaseHasDependentsException("co nguoi phu thuoc"))
                .when(testCaseService).ensureNoDependents(List.of(existingAiCase));

        assertThatThrownBy(() -> testCaseGenerationService.generate(
                projectId, endpointId,
                new com.aiapitesting.backend.dto.request.GenerateTestCasesRequest(true, false, false, false, false)))
                .isInstanceOf(com.aiapitesting.backend.exception.TestCaseHasDependentsException.class);

        verify(testCaseRepository, never()).deleteAllByEndpointAndSourceAndLockedFalse(any(), any());
        verify(testCaseRepository, never()).saveAll(anyList());
        // Bị chặn trước khi kịp đụng tới nhóm Security -> không lãng phí lệnh gọi AI nào.
        verifyNoInteractions(chatClient);
    }

    @Test
    void generate_includeSecurityTrue_alsoGeneratesAndSavesSecurityGroupSeparately() throws Exception {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        // Cùng 1 stub AI trả về cho cả 2 lần gọi (Cơ bản + Security) - đủ để kiểm tra tách source đúng.
        stubAiResponse(List.of(new GeneratedTestCase(
                "Security - Thieu token", "mo ta", Map.of(), null, 401, "/users",
                Map.of(), com.aiapitesting.backend.entity.TestCaseAuthOverride.NONE, null)));
        when(testCaseRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        CompletableFuture<List<TestCaseResponse>> future = testCaseGenerationService.generate(
                projectId, endpointId,
                new com.aiapitesting.backend.dto.request.GenerateTestCasesRequest(true, false, true, true, true));
        future.get();

        // Cả 2 nhóm đều được xoá-và-thay đúng phạm vi source riêng - không nhóm nào đụng nhóm kia.
        verify(testCaseRepository).deleteAllByEndpointAndSourceAndLockedFalse(endpoint, TestCaseSource.AI_GENERATED);
        verify(testCaseRepository).deleteAllByEndpointAndSourceAndLockedFalse(endpoint, TestCaseSource.SECURITY);
        verify(testCaseRepository, org.mockito.Mockito.times(2)).saveAll(anyList());
        // 2 lần gọi AI thật (Cơ bản + Security) -> 2 dòng lịch sử sinh test case riêng biệt.
        verify(testGenerationEventRepository, org.mockito.Mockito.times(2)).save(any(TestGenerationEvent.class));
    }

    @Test
    void generate_noCategorySelected_throwsInvalidRequestWithoutCallingAi() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));

        assertThatThrownBy(() -> testCaseGenerationService.generate(
                projectId, endpointId,
                new com.aiapitesting.backend.dto.request.GenerateTestCasesRequest(false, false, false, false, false)))
                .isInstanceOf(com.aiapitesting.backend.exception.InvalidRequestException.class);

        verifyNoInteractions(chatClient);
        verifyNoInteractions(testCaseRepository);
    }

    @Test
    void generate_onlySecuritySelected_deletesStaleBasicGroupWithoutCallingAi() throws Exception {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        stubAiResponse(List.of(new GeneratedTestCase(
                "Security - Thieu token", "mo ta", Map.of(), null, 401, "/users",
                Map.of(), com.aiapitesting.backend.entity.TestCaseAuthOverride.NONE, null)));
        when(testCaseRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        CompletableFuture<List<TestCaseResponse>> future = testCaseGenerationService.generate(
                projectId, endpointId,
                new com.aiapitesting.backend.dto.request.GenerateTestCasesRequest(true, false, false, false, false));
        future.get();

        // Không tích Positive/Negative/Boundary nào -> vẫn phải xoá sạch case AI_GENERATED cũ (chưa
        // khoá) còn sót từ 1 lần sinh trước, NHƯNG không được gọi AI cho nhóm này - chỉ 1 lần gọi AI
        // duy nhất (Security), không lãng phí lời gọi cho nhóm không cần.
        verify(testCaseRepository).deleteAllByEndpointAndSourceAndLockedFalse(endpoint, TestCaseSource.AI_GENERATED);
        verify(testCaseRepository).deleteAllByEndpointAndSourceAndLockedFalse(endpoint, TestCaseSource.SECURITY);
        verify(testCaseRepository, org.mockito.Mockito.times(1)).saveAll(anyList());
    }

    @Test
    void generate_onlyPositiveSelected_deletesStaleSecurityGroupWithoutCallingAi() throws Exception {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        stubAiResponse(List.of(new GeneratedTestCase(
                "Positive", "mo ta", Map.of(), "{}", 200, "/users", Map.of(), null, null)));
        when(testCaseRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        CompletableFuture<List<TestCaseResponse>> future = testCaseGenerationService.generate(
                projectId, endpointId,
                new com.aiapitesting.backend.dto.request.GenerateTestCasesRequest(false, false, true, false, false));
        future.get();

        // Không tích Security -> vẫn phải xoá sạch case SECURITY cũ (chưa khoá) còn sót từ 1 lần
        // sinh trước, NHƯNG không được gọi AI cho nhóm này - chỉ 1 lần gọi AI duy nhất (Cơ bản).
        verify(testCaseRepository).deleteAllByEndpointAndSourceAndLockedFalse(endpoint, TestCaseSource.AI_GENERATED);
        verify(testCaseRepository).deleteAllByEndpointAndSourceAndLockedFalse(endpoint, TestCaseSource.SECURITY);
        verify(testCaseRepository, org.mockito.Mockito.times(1)).saveAll(anyList());
    }

    @SuppressWarnings("unchecked")
    private void stubAiResponse(List<GeneratedTestCase> result) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(100, 50, 150))
                .build();
        ChatResponse chatResponse = new ChatResponse(List.of(), metadata);
        when(chatClient.prompt(any(Prompt.class)).call()
                .responseEntity(any(ParameterizedTypeReference.class)))
                .thenReturn(new ResponseEntity<>(chatResponse, result));
    }
}
