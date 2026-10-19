package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.response.ChecklistItemStatus;
import com.aiapitesting.backend.dto.response.GenerateTestCasesResponse;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.entity.TestGenerationEvent;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.AiGenerationFailedException;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.dto.response.GenerationPlanSummary;
import com.aiapitesting.backend.exception.ForbiddenException;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseAssertionRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.service.ProjectService;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner;
import com.aiapitesting.backend.service.TestCasePathValidator;
import com.aiapitesting.backend.service.TestCaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private com.aiapitesting.backend.service.TestCaseReliabilityValidator testCaseReliabilityValidator;

    @Mock
    private TestCaseReliabilityConfirmationService testCaseReliabilityConfirmationService;

    @Mock
    private TestCaseService testCaseService;

    @Mock
    private TestGenerationEventRepository testGenerationEventRepository;

    // Module 15h - planner tất định, không gọi AI/DB nên dùng instance thật (Spy) thay vì mock.
    @Spy
    private TestCaseChecklistPlanner testCaseChecklistPlanner = new TestCaseChecklistPlanner();

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

        // Mặc định RELIABLE cho các test không quan tâm tới Module 15b - lenient() vì nhiều test
        // (quota/notOwner/endpointNotFound/noCategorySelected) không chạy tới bước này, strict stubs
        // sẽ báo UnnecessaryStubbingException nếu không đánh dấu lenient.
        org.mockito.Mockito.lenient()
                .when(testCaseReliabilityValidator.validate(any(), any(), any()))
                .thenReturn(com.aiapitesting.backend.service.TestCaseReliabilityValidator.Result.reliable());
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

        CompletableFuture<GenerateTestCasesResponse> future = testCaseGenerationService.generate(projectId, endpointId, null);
        List<TestCaseResponse> result = future.get().testCases();

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
    void generate_success_mapsRationaleToSavedEntityAndResponse() throws Exception {
        // Module 15a - rationale do AI tự khai trong CÙNG 1 lệnh gọi phải được lưu nguyên vẹn vào
        // entity và trả đúng trong response, không bị rớt/đổi.
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        String expectedRationale = "Truong 'email' co trong required cua requestBody nhung bi bo qua de kiem tra validation.";
        stubAiResponse(List.of(new GeneratedTestCase(
                "Negative - Thieu email", "mo ta", Map.of(), "{}", 400, "/users", Map.of(), null,
                expectedRationale)));
        when(testCaseRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<TestCaseResponse> result = testCaseGenerationService.generate(projectId, endpointId, null).get().testCases();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).rationale()).isEqualTo(expectedRationale);

        ArgumentCaptor<List<TestCase>> savedCaptor = ArgumentCaptor.forClass(List.class);
        verify(testCaseRepository).saveAll(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).extracting(TestCase::getRationale).containsExactly(expectedRationale);
    }

    @Test
    void generate_caseFlaggedByTier1_callsTier2ConfirmationWithFlaggedCaseOnly() throws Exception {
        // Module 15d (Tier 2) - CHỈ gọi thêm AI khi có case bị Tier 1 gắn cờ NEEDS_REVIEW, và chỉ
        // gửi ĐÚNG những case bị gắn cờ (không gửi case RELIABLE kèm theo).
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        stubAiResponse(List.of(
                new GeneratedTestCase("Positive - OK", "mo ta", Map.of(), "{}", 201, "/users", Map.of(), null, "ly do"),
                new GeneratedTestCase("Positive - Can xem lai", "mo ta", Map.of(), "{}", 201, "/users", Map.of(), null, "ly do")));
        when(testCaseRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        // Case đầu RELIABLE (giữ nguyên stub mặc định ở setUp), case thứ 2 NEEDS_REVIEW.
        when(testCaseReliabilityValidator.validate(eq(endpoint), argThat(g -> "Positive - Can xem lai".equals(g.name())), any()))
                .thenReturn(new com.aiapitesting.backend.service.TestCaseReliabilityValidator.Result(
                        com.aiapitesting.backend.entity.TestCaseReliability.NEEDS_REVIEW, List.of("vi pham gia tri")));
        when(testCaseReliabilityConfirmationService.confirm(eq(endpoint), anyList())).thenReturn(null);

        testCaseGenerationService.generate(projectId, endpointId, null).get();

        ArgumentCaptor<List<TestCase>> flaggedCaptor = ArgumentCaptor.forClass(List.class);
        verify(testCaseReliabilityConfirmationService).confirm(eq(endpoint), flaggedCaptor.capture());
        assertThat(flaggedCaptor.getValue()).hasSize(1);
        assertThat(flaggedCaptor.getValue().get(0).getName()).isEqualTo("Positive - Can xem lai");
    }

    @Test
    void generate_noCaseFlagged_neverCallsTier2Confirmation() throws Exception {
        // 0 chi phí khi không có gì cần xác minh - đúng nguyên tắc "tối ưu chi phí" đã chốt.
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        stubAiResponse(List.of(new GeneratedTestCase(
                "Positive", "mo ta", Map.of(), "{}", 201, "/users", Map.of(), null, "ly do")));
        when(testCaseRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        testCaseGenerationService.generate(projectId, endpointId, null).get();

        verifyNoInteractions(testCaseReliabilityConfirmationService);
    }

    @Test
    void generate_tier2TokenUsage_mergedIntoSameTestGenerationEvent() throws Exception {
        // Gộp token Tier 2 vào CÙNG 1 dòng TestGenerationEvent với Tier 1 (không tạo dòng riêng -
        // tránh vỡ HistoryFeedService.parseSnapshot() vốn luôn đọc snapshotJson như List<GeneratedTestCase>).
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        stubAiResponse(List.of(new GeneratedTestCase(
                "Positive - Can xem lai", "mo ta", Map.of(), "{}", 201, "/users", Map.of(), null, "ly do")));
        when(testCaseRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(testCaseReliabilityValidator.validate(any(), any(), any()))
                .thenReturn(new com.aiapitesting.backend.service.TestCaseReliabilityValidator.Result(
                        com.aiapitesting.backend.entity.TestCaseReliability.NEEDS_REVIEW, List.of("vi pham")));
        when(testCaseReliabilityConfirmationService.confirm(any(), anyList()))
                .thenReturn(new DefaultUsage(20, 10, 30));
        when(testGenerationEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        testCaseGenerationService.generate(projectId, endpointId, null).get();

        // Module 15g - event được lưu 2 lần (lưu Tier 1 trước để quota Tier 2 tính đúng, UPDATE lại
        // sau khi có token Tier 2) nhưng vẫn là CÙNG 1 dòng - getValue() trả lần capture CUỐI (đã gộp).
        ArgumentCaptor<TestGenerationEvent> eventCaptor = ArgumentCaptor.forClass(TestGenerationEvent.class);
        verify(testGenerationEventRepository, times(2)).save(eventCaptor.capture());
        TestGenerationEvent event = eventCaptor.getValue();
        // stubAiResponse() set Tier 1 = 100/50/150 (xem helper cuối file); Tier 2 ở test này = 20/10/30.
        assertThat(event.getPromptTokens()).isEqualTo(120);
        assertThat(event.getCompletionTokens()).isEqualTo(60);
        assertThat(event.getTotalTokens()).isEqualTo(180);
    }

    @Test
    void generate_quotaExceededBeforeTier2_skipsConfirmationButStillSavesTestCases() throws Exception {
        // Quota đủ lúc ĐẦU generate() (check 1 lần) nhưng đã CHẠM giới hạn ngay trước lúc gọi Tier 2
        // (do chính lượt sinh Tier 1 vừa rồi cộng dồn) - Tier 2 phải bị bỏ qua, KHÔNG được chặn lưu
        // test case Tier 1 đã sinh thành công.
        org.springframework.test.util.ReflectionTestUtils.setField(testCaseGenerationService, "dailyTokenLimit", 100L);
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        // Lần gọi 1 (ensureWithinDailyQuota ở đầu generate()): dưới giới hạn -> cho qua.
        // Lần gọi 2 (isWithinDailyQuota trước Tier 2): đã đạt giới hạn -> bỏ qua Tier 2.
        when(testGenerationEventRepository.sumTotalTokensByOwnerAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(0L)
                .thenReturn(100L);
        stubAiResponse(List.of(new GeneratedTestCase(
                "Positive - Can xem lai", "mo ta", Map.of(), "{}", 201, "/users", Map.of(), null, "ly do")));
        when(testCaseRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(testCaseReliabilityValidator.validate(any(), any(), any()))
                .thenReturn(new com.aiapitesting.backend.service.TestCaseReliabilityValidator.Result(
                        com.aiapitesting.backend.entity.TestCaseReliability.NEEDS_REVIEW, List.of("vi pham")));

        List<TestCaseResponse> result = testCaseGenerationService.generate(projectId, endpointId, null).get().testCases();

        assertThat(result).hasSize(1);
        verifyNoInteractions(testCaseReliabilityConfirmationService);
        verify(testCaseRepository).saveAll(anyList());
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

        List<TestCaseResponse> result = testCaseGenerationService.generate(projectId, endpointId, null).get().testCases();

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
                new com.aiapitesting.backend.dto.request.GenerateTestCasesRequest(true, false, false, false)))
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

        CompletableFuture<GenerateTestCasesResponse> future = testCaseGenerationService.generate(
                projectId, endpointId,
                new com.aiapitesting.backend.dto.request.GenerateTestCasesRequest(true, true, true, true));
        future.get();

        // Cả 2 nhóm đều được xoá-và-thay đúng phạm vi source riêng - không nhóm nào đụng nhóm kia.
        verify(testCaseRepository).deleteAllByEndpointAndSourceAndLockedFalse(endpoint, TestCaseSource.AI_GENERATED);
        verify(testCaseRepository).deleteAllByEndpointAndSourceAndLockedFalse(endpoint, TestCaseSource.SECURITY);
        verify(testCaseRepository, org.mockito.Mockito.times(2)).saveAll(anyList());
        // 2 lần gọi AI thật (Cơ bản + Security) -> 2 dòng lịch sử sinh test case riêng biệt, cộng 1 lần
        // cập nhật dòng cuối với báo cáo checklist gộp cả 2 nhóm (Module 15i) - không tạo dòng thứ 3.
        verify(testGenerationEventRepository, org.mockito.Mockito.times(3)).save(any(TestGenerationEvent.class));
    }

    @Test
    void generate_noCategorySelected_throwsInvalidRequestWithoutCallingAi() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));

        assertThatThrownBy(() -> testCaseGenerationService.generate(
                projectId, endpointId,
                new com.aiapitesting.backend.dto.request.GenerateTestCasesRequest(false, false, false, false)))
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

        CompletableFuture<GenerateTestCasesResponse> future = testCaseGenerationService.generate(
                projectId, endpointId,
                new com.aiapitesting.backend.dto.request.GenerateTestCasesRequest(true, false, false, false));
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

        CompletableFuture<GenerateTestCasesResponse> future = testCaseGenerationService.generate(
                projectId, endpointId,
                new com.aiapitesting.backend.dto.request.GenerateTestCasesRequest(false, true, false, false));
        future.get();

        // Không tích Security -> vẫn phải xoá sạch case SECURITY cũ (chưa khoá) còn sót từ 1 lần
        // sinh trước, NHƯNG không được gọi AI cho nhóm này - chỉ 1 lần gọi AI duy nhất (Cơ bản).
        verify(testCaseRepository).deleteAllByEndpointAndSourceAndLockedFalse(endpoint, TestCaseSource.AI_GENERATED);
        verify(testCaseRepository).deleteAllByEndpointAndSourceAndLockedFalse(endpoint, TestCaseSource.SECURITY);
        verify(testCaseRepository, org.mockito.Mockito.times(1)).saveAll(anyList());
    }

    @SuppressWarnings("unchecked")
    // ---- Module 15h - checklist EP/BVA, chấm theo nội dung, tự bù ----

    /** email bắt buộc + age bắt buộc (18..60): checklist đủ 3 nhóm = 1 + 2 + 4 = 7 mục. */
    private static final String REGISTER_SCHEMA = """
            {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["email","age"],
              "properties":{"email":{"type":"string"},"age":{"type":"integer","minimum":18,"maximum":60}}}}}}}""";

    private GeneratedTestCase aiCase(String name, String body, int status) {
        return new GeneratedTestCase(name, "mo ta", Map.of(), body, status, "/users", Map.of(), null, "ly do AI");
    }

    private void stubRegisterEndpoint() {
        endpoint.setSchema(REGISTER_SCHEMA);
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        when(testCaseRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private String sentPrompt() {
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatClient, org.mockito.Mockito.atLeastOnce()).prompt(promptCaptor.capture());
        return promptCaptor.getValue().getContents();
    }

    @Test
    void generate_aiMissesAndBotchesChecklistItems_systemFillsExactlyThoseAndReportsHonestly() throws Exception {
        stubRegisterEndpoint();
        stubAiResponse(List.of(
                aiCase("Positive - hop le", "{\"email\":\"an@gmail.com\",\"age\":25}", 201),
                // Tên nói thiếu email nhưng body vẫn có email - KHÔNG được tính đạt mục "thiếu email".
                aiCase("Negative - Thieu email", "{\"email\":\"an@gmail.com\",\"age\":25}", 400),
                aiCase("Negative - Thieu age", "{\"email\":\"an@gmail.com\"}", 400),
                aiCase("Boundary - age 18", "{\"email\":\"an@gmail.com\",\"age\":18}", 201),
                aiCase("Boundary - age 17", "{\"email\":\"an@gmail.com\",\"age\":17}", 400),
                aiCase("Boundary - age 60", "{\"email\":\"an@gmail.com\",\"age\":60}", 201),
                // AI quên mục age = 61. Case dưới đây là đề xuất thêm (sai kiểu), không ứng mục nào.
                aiCase("Negative - age la chu", "{\"email\":\"an@gmail.com\",\"age\":\"hai muoi\"}", 400)));

        GenerateTestCasesResponse response = testCaseGenerationService.generate(projectId, endpointId, null).get();

        // Prompt tách theo loại (15i): mỗi loại đã tick có phần checklist riêng.
        assertThat(sentPrompt()).contains("Checklist Positive (bắt buộc)").contains("Checklist Negative (bắt buộc)")
                .contains("Checklist Boundary (bắt buộc)").contains("Thiếu field bắt buộc 'email'")
                .contains("'age' trong requestBody = 61, lớn hơn maximum = 60");
        var summary = response.planSummary();
        // 15i: P1 + Negative 5 (thiếu/null x email, age + sai kiểu age; email là chuỗi nên không có mục sai kiểu - Module 17 1.2)
        // + Boundary 5 (age min/max x2 + email rỗng).
        assertThat(summary.checklistTotal()).isEqualTo(11);
        assertThat(summary.aiAskedCount()).isEqualTo(11);
        // Case "age là chữ" trước 15i là đề xuất thêm, giờ đạt mục checklist "sai kiểu".
        assertThat(summary.aiSatisfiedCount()).isEqualTo(6);
        assertThat(summary.systemFilledCount()).isEqualTo(5);
        assertThat(summary.aiExtraCount()).isEqualTo(1); // chỉ còn case "thiếu email" nói dối
        assertThat(summary.items()).filteredOn(i -> i.status() == ChecklistItemStatus.SYSTEM_FILLED)
                .extracting(i -> i.description())
                .anyMatch(d -> d.startsWith("Thiếu field bắt buộc 'email'"))
                .anyMatch(d -> d.contains("= 61"));
        // Báo cáo chỉ rõ case nào ứng với mục nào - mục Positive chỉ liệt kê case THUẦN hợp lệ (case
        // biên age=18/age=60 cũng hợp lệ nhưng đã nằm dưới mục biên riêng của chúng, không liệt kê trùng),
        // mục thiếu email do case hệ thống bù đạt (KHÔNG phải case AI "Thieu email" nói dối).
        assertThat(summary.items()).filteredOn(i -> i.type() == com.aiapitesting.backend.dto.response.ChecklistItemType.POSITIVE_BASELINE)
                .singleElement().satisfies(i -> assertThat(i.coveredBy()).containsExactly("Positive - hop le"));
        assertThat(summary.items()).filteredOn(i -> "email".equals(i.field())
                        && i.type() == com.aiapitesting.backend.dto.response.ChecklistItemType.NEGATIVE_MISSING_FIELD)
                .singleElement().satisfies(i -> assertThat(i.coveredBy()).containsExactly("Negative - Thiếu field bắt buộc 'email'"));

        assertThat(response.testCases()).hasSize(12);
        TestCaseResponse filledEmail = response.testCases().stream()
                .filter(tc -> tc.generationOrigin() == TestCaseGenerationOrigin.SYSTEM_FILLED
                        && tc.name().equals("Negative - Thiếu field bắt buộc 'email'"))
                .findFirst().orElseThrow();
        assertThat(filledEmail.requestBody()).isEqualTo("{\"age\":25}");
        assertThat(filledEmail.expectedStatus()).isEqualTo(400);
        assertThat(filledEmail.rationale()).contains("required");
        assertThat(response.testCases()).filteredOn(tc -> tc.generationOrigin() == TestCaseGenerationOrigin.AI_CHECKLIST).hasSize(6);
        // Nhãn mục checklist của case biên là mục biên cụ thể, không phải "Dữ liệu hợp lệ".
        assertThat(response.testCases()).filteredOn(tc -> tc.name().equals("Boundary - age 18"))
                .singleElement().satisfies(tc -> assertThat(tc.checklistItem()).contains("minimum = 18"));

        // Snapshot lịch sử chứa cả case bù - trang Lịch sử hiện đúng 12 case thực tế.
        ArgumentCaptor<TestGenerationEvent> eventCaptor = ArgumentCaptor.forClass(TestGenerationEvent.class);
        verify(testGenerationEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getTestCaseCount()).isEqualTo(12);
        assertThat(eventCaptor.getValue().getSnapshotJson()).contains("Thiếu field bắt buộc 'email'");

        // Báo cáo tự đủ (không cần bộ test case sống) và được lưu vào event để xem lại sau khi tải lại trang.
        assertThat(summary.checklistCaseCount()).isEqualTo(11);
        assertThat(summary.extraCaseNames()).containsExactly("Negative - Thieu email");
        GenerationPlanSummary persisted = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(eventCaptor.getValue().getPlanSummaryJson(), GenerationPlanSummary.class);
        assertThat(persisted).isEqualTo(summary);
    }

    // ---- Báo cáo checklist đã lưu (xem lại sau khi tải lại trang) ----

    @Test
    void getLatestPlanSummary_savedEvent_returnsParsedSummary() throws Exception {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        GenerationPlanSummary saved = new GenerationPlanSummary(1, 0, 1, 1, 0, 0, 0, 1, 1,
                List.of("Negative - age la chu"), List.of());
        TestGenerationEvent event = TestGenerationEvent.builder()
                .planSummaryJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(saved)).build();
        when(testGenerationEventRepository.findFirstByEndpointOrderByCreatedAtDesc(endpoint))
                .thenReturn(Optional.of(event));

        assertThat(testCaseGenerationService.getLatestPlanSummary(projectId, endpointId)).isEqualTo(saved);
    }

    @Test
    void getLatestPlanSummary_noSavedEvent_returnsNull() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        when(testGenerationEventRepository.findFirstByEndpointOrderByCreatedAtDesc(endpoint))
                .thenReturn(Optional.empty());

        assertThat(testCaseGenerationService.getLatestPlanSummary(projectId, endpointId)).isNull();
    }

    @Test
    void getLatestPlanSummary_corruptJson_returnsNullInsteadOfThrowing() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        when(testGenerationEventRepository.findFirstByEndpointOrderByCreatedAtDesc(endpoint))
                .thenReturn(Optional.of(TestGenerationEvent.builder().planSummaryJson("{khong phai json").build()));

        assertThat(testCaseGenerationService.getLatestPlanSummary(projectId, endpointId)).isNull();
    }

    @Test
    void getLatestPlanSummary_latestEventCreatedBeforeFeature_returnsNull() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        when(testGenerationEventRepository.findFirstByEndpointOrderByCreatedAtDesc(endpoint))
                .thenReturn(Optional.of(TestGenerationEvent.builder().planSummaryJson(null).build()));

        // Dòng lịch sử mới nhất không có báo cáo -> KHÔNG lấy báo cáo của 1 lần sinh cũ hơn (đã lỗi thời).
        assertThat(testCaseGenerationService.getLatestPlanSummary(projectId, endpointId)).isNull();
    }

    @Test
    void getLatestPlanSummary_notOwner_throwsForbidden() {
        when(projectService.getOwnedProject(projectId))
                .thenThrow(new ForbiddenException("Ban khong co quyen truy cap project nay"));

        assertThatThrownBy(() -> testCaseGenerationService.getLatestPlanSummary(projectId, endpointId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void generate_lockedCaseAlreadyCoversItem_itemNotAskedAgainAndNotDuplicated() throws Exception {
        stubRegisterEndpoint();
        TestCase lockedAge61 = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).name("Boundary - age 61 (khoa)")
                .requestBody("{\"email\":\"an@gmail.com\",\"age\":61}").expectedStatus(400).resolvedPath("/users")
                .source(TestCaseSource.AI_GENERATED).locked(true).build();
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint)).thenReturn(List.of(lockedAge61));
        stubAiResponse(List.of(aiCase("Positive - hop le", "{\"email\":\"an@gmail.com\",\"age\":25}", 201)));

        GenerateTestCasesResponse response = testCaseGenerationService.generate(projectId, endpointId, null).get();

        assertThat(sentPrompt()).doesNotContain("= 61, lớn hơn maximum");
        assertThat(response.planSummary().alreadyCoveredCount()).isEqualTo(1);
        assertThat(response.planSummary().items()).filteredOn(i -> i.status() == ChecklistItemStatus.ALREADY_COVERED)
                .singleElement().satisfies(i -> assertThat(i.coveredBy()).containsExactly("Boundary - age 61 (khoa)"));
        assertThat(response.planSummary().aiAskedCount()).isEqualTo(10);
        assertThat(response.testCases()).noneMatch(tc -> tc.requestBody() != null && tc.requestBody().contains("61"));
    }

    @Test
    void generate_positiveNotTicked_pureBaselineUsedForFillingButNotSaved() throws Exception {
        stubRegisterEndpoint();
        stubAiResponse(List.of(aiCase("Positive - goc", "{\"email\":\"an@gmail.com\",\"age\":25}", 201)));

        GenerateTestCasesResponse response = testCaseGenerationService.generate(projectId, endpointId,
                new com.aiapitesting.backend.dto.request.GenerateTestCasesRequest(false, false, true, false)).get();

        // Chỉ tick Negative: 5 mục (thiếu/null x email, age + sai kiểu age), đều do hệ thống bù từ case gốc; case gốc không lưu.
        assertThat(response.testCases()).hasSize(5)
                .allMatch(tc -> tc.generationOrigin() == TestCaseGenerationOrigin.SYSTEM_FILLED)
                .allMatch(tc -> tc.expectedStatus() == 400);
        assertThat(response.planSummary().systemFilledCount()).isEqualTo(5);
        // Prompt chỉ ghép phần của loại đã tick (15i).
        assertThat(sentPrompt()).contains("Checklist Negative (bắt buộc)")
                .doesNotContain("Checklist Positive").doesNotContain("Checklist Boundary").doesNotContain("Checklist Security");
    }

    @Test
    void generate_noValidBaselineFromAi_itemsReportedNotFillableInsteadOfFakeCases() throws Exception {
        stubRegisterEndpoint();
        stubAiResponse(List.of(aiCase("Negative - Thieu email", "{\"age\":25}", 400)));

        GenerateTestCasesResponse response = testCaseGenerationService.generate(projectId, endpointId, null).get();

        assertThat(response.testCases()).hasSize(1);
        assertThat(response.planSummary().aiSatisfiedCount()).isEqualTo(1);
        assertThat(response.planSummary().notFillableCount()).isEqualTo(10);
        assertThat(response.planSummary().items()).filteredOn(i -> i.status() == ChecklistItemStatus.NOT_FILLABLE)
                .allMatch(i -> i.reason() != null && i.reason().contains("làm gốc"));
    }

    @Test
    void generate_securityOnly_hasOwnChecklist_aiCoversMissingToken_systemFillsInvalidToken() throws Exception {
        stubRegisterEndpoint();
        endpoint.setRequiresAuth(true);
        stubAiResponse(List.of(new GeneratedTestCase("Security - Thieu token", "mo ta", Map.of(), null, 401, "/users",
                Map.of(), com.aiapitesting.backend.entity.TestCaseAuthOverride.NONE, null)));

        GenerateTestCasesResponse response = testCaseGenerationService.generate(projectId, endpointId,
                new com.aiapitesting.backend.dto.request.GenerateTestCasesRequest(true, false, false, false)).get();

        // Module 15i - Security có checklist riêng (thiếu token + token sai), chấm theo authOverride + 401.
        assertThat(sentPrompt()).contains("Checklist Security (bắt buộc)").doesNotContain("Checklist Negative");
        assertThat(response.planSummary().checklistTotal()).isEqualTo(2);
        assertThat(response.planSummary().aiSatisfiedCount()).isEqualTo(1);
        assertThat(response.planSummary().systemFilledCount()).isEqualTo(1);
        assertThat(response.testCases()).hasSize(2);
        assertThat(response.testCases()).filteredOn(tc -> tc.generationOrigin() == TestCaseGenerationOrigin.SYSTEM_FILLED)
                .singleElement().satisfies(tc -> {
                    assertThat(tc.authOverride()).isEqualTo(com.aiapitesting.backend.entity.TestCaseAuthOverride.INVALID);
                    assertThat(tc.expectedStatus()).isEqualTo(401);
                });
        ArgumentCaptor<TestGenerationEvent> eventCaptor = ArgumentCaptor.forClass(TestGenerationEvent.class);
        verify(testGenerationEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getPlanSummaryJson()).contains("SECURITY_INVALID_TOKEN");
    }

    @Test
    void generate_checklistItemTier1CannotSee_trustedOverTier1Warning_otherCasesKeepIt() throws Exception {
        endpoint.setMethod("GET");
        endpoint.setPath("/api/products");
        endpoint.setSchema("""
                {"parameters":[{"name":"status","in":"query","required":true,"schema":{"type":"string"}}]}""");
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(endpointId, project)).thenReturn(Optional.of(endpoint));
        when(testCaseRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        // AI chỉ trả case gốc + 1 case đề xuất thêm; mục "thiếu tham số status" do hệ thống bù.
        stubAiResponse(List.of(
                new GeneratedTestCase("Positive - hop le", "d", Map.of(), null, 200, "/api/products?status={{status}}",
                        Map.of("status", "ACTIVE"), null, "ly do"),
                new GeneratedTestCase("De xuat them - luat nghiep vu", "d", Map.of(), null, 400,
                        "/api/products?status=lung-tung", Map.of(), null, "ly do")));
        // Tier 1 gắn "Cần xem lại" cho MỌI case (giả lập: nó không nhìn thấy lỗi thiếu tham số query).
        org.mockito.Mockito.lenient().when(testCaseReliabilityValidator.validate(any(), any(), any()))
                .thenReturn(new com.aiapitesting.backend.service.TestCaseReliabilityValidator.Result(
                        com.aiapitesting.backend.entity.TestCaseReliability.NEEDS_REVIEW, List.of("giả lập")));
        when(testCaseReliabilityConfirmationService.confirm(eq(endpoint), anyList())).thenReturn(null);

        GenerateTestCasesResponse response = testCaseGenerationService.generate(projectId, endpointId,
                new com.aiapitesting.backend.dto.request.GenerateTestCasesRequest(false, true, true, false)).get();

        assertThat(response.testCases()).filteredOn(tc -> tc.generationOrigin() == TestCaseGenerationOrigin.SYSTEM_FILLED)
                .singleElement().satisfies(tc -> {
                    assertThat(tc.name()).contains("Thiếu tham số query bắt buộc 'status'");
                    assertThat(tc.resolvedPath()).isEqualTo("/api/products");
                    assertThat(tc.reliabilityStatus())
                            .isEqualTo(com.aiapitesting.backend.entity.TestCaseReliability.RELIABLE);
                });
        // Case khác vẫn mang đúng cảnh báo của Tier 1 (chỉ mục Tier 1 không nhìn thấy mới được tin theo checklist).
        assertThat(response.testCases()).filteredOn(tc -> tc.generationOrigin() == TestCaseGenerationOrigin.AI_EXTRA)
                .singleElement().satisfies(tc -> assertThat(tc.reliabilityStatus())
                        .isEqualTo(com.aiapitesting.backend.entity.TestCaseReliability.NEEDS_REVIEW));
        // Chỉ case đề xuất thêm bị gắn cờ → Tier 2 chỉ xác nhận đúng case đó.
        ArgumentCaptor<List<TestCase>> flagged = ArgumentCaptor.forClass(List.class);
        verify(testCaseReliabilityConfirmationService).confirm(eq(endpoint), flagged.capture());
        assertThat(flagged.getValue()).extracting(TestCase::getName).doesNotContain(
                response.testCases().stream().filter(tc -> tc.generationOrigin() == TestCaseGenerationOrigin.SYSTEM_FILLED)
                        .findFirst().orElseThrow().name());
    }

    @Test
    void generate_basicAndSecurity_mergedReportSavedOnLastEvent() throws Exception {
        stubRegisterEndpoint();
        endpoint.setRequiresAuth(true);
        stubAiResponse(List.of(aiCase("Positive - hop le", "{\"email\":\"an@gmail.com\",\"age\":25}", 201)));

        GenerateTestCasesResponse response = testCaseGenerationService.generate(projectId, endpointId,
                new com.aiapitesting.backend.dto.request.GenerateTestCasesRequest(true, true, false, false)).get();

        // Positive 1 + Security 2 gộp chung 1 báo cáo; dòng lịch sử cuối (Security) mang bản gộp để đọc lại sau.
        assertThat(response.planSummary().checklistTotal()).isEqualTo(3);
        assertThat(response.planSummary().items()).extracting(i -> i.type().name())
                .containsExactly("POSITIVE_BASELINE", "SECURITY_MISSING_TOKEN", "SECURITY_INVALID_TOKEN");
        ArgumentCaptor<TestGenerationEvent> eventCaptor = ArgumentCaptor.forClass(TestGenerationEvent.class);
        verify(testGenerationEventRepository, times(3)).save(eventCaptor.capture());
        TestGenerationEvent last = eventCaptor.getAllValues().get(2);
        assertThat(last.getPlanSummaryJson()).contains("POSITIVE_BASELINE").contains("SECURITY_MISSING_TOKEN");
    }

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
