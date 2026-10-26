package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
import com.aiapitesting.backend.entity.TestGenerationEvent;
import com.aiapitesting.backend.entity.User;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
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

    // ---- 17q G1 - đổi kỳ vọng test gốc không được che lệch spec ----

    @Test
    void removeStaleBaselineDrafts_deletesUnlockedUnconfirmedAiBaselines_butKeepsLockedKeptAndChainSources() {
        com.aiapitesting.backend.entity.Endpoint ep = com.aiapitesting.backend.entity.Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/jobs").build();
        var origin = com.aiapitesting.backend.entity.TestCaseGenerationOrigin.AI_BASELINE;
        com.aiapitesting.backend.entity.TestCase keep = com.aiapitesting.backend.entity.TestCase.builder().id(UUID.randomUUID()).endpoint(ep).generationOrigin(origin).locked(true).build();
        com.aiapitesting.backend.entity.TestCase draft = com.aiapitesting.backend.entity.TestCase.builder().id(UUID.randomUUID()).endpoint(ep).generationOrigin(origin).build();
        com.aiapitesting.backend.entity.TestCase chainSource = com.aiapitesting.backend.entity.TestCase.builder().id(UUID.randomUUID()).endpoint(ep).generationOrigin(origin).build();
        com.aiapitesting.backend.entity.TestCase otherLocked = com.aiapitesting.backend.entity.TestCase.builder().id(UUID.randomUUID()).endpoint(ep).generationOrigin(origin).locked(true).build();
        com.aiapitesting.backend.entity.TestCase manual = com.aiapitesting.backend.entity.TestCase.builder().id(UUID.randomUUID()).endpoint(ep).build();
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.findByIdAndProject(ep.getId(), project)).thenReturn(Optional.of(ep));
        when(testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(ep)).thenReturn(List.of(keep, draft, chainSource, otherLocked, manual));
        when(testCaseDependencyRepository.findAllByDependsOnTestCaseIdIn(any())).thenReturn(List.of(
                com.aiapitesting.backend.entity.TestCaseDependency.builder().dependsOnTestCase(chainSource).build()));

        int removed = testCaseGenerationService.removeStaleBaselineDrafts(projectId, ep.getId(), keep.getId());

        assertThat(removed).isEqualTo(1);
        org.mockito.ArgumentCaptor<List<com.aiapitesting.backend.entity.TestCase>> deleted = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(testCaseRepository).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).containsExactly(draft); // không đụng test đã khoá, test được giữ, nguồn nối chuỗi, test thủ công
    }

    @Test
    void expectationChangeNote_saysSpecDeviationWhenAdoptedCodeIsNotDeclared() {
        Endpoint declared201 = Endpoint.builder().schema("{\"responseStatusCodes\":[\"201\",\"400\"]}").build();
        assertThat(TestCaseGenerationService.expectationChangeNote(declared201, 201, 200))
                .contains("201 → 200").contains("LỆCH SPEC").contains("[201]");
    }

    @Test
    void expectationChangeNote_matchesSpec_orSpecNotInformative() {
        Endpoint declared = Endpoint.builder().schema("{\"responseStatusCodes\":[\"200\",\"201\"]}").build();
        assertThat(TestCaseGenerationService.expectationChangeNote(declared, 200, 201)).contains("khớp spec").doesNotContain("LỆCH");
        Endpoint springdoc = Endpoint.builder().schema("{\"responseStatusCodes\":[\"200\"]}").build();
        assertThat(TestCaseGenerationService.expectationChangeNote(springdoc, 200, 201)).contains("chỉ khai 200");
        Endpoint legacy = Endpoint.builder().build();
        assertThat(TestCaseGenerationService.expectationChangeNote(legacy, 200, 201)).contains("không đối chiếu được");
    }

    @SuppressWarnings("unchecked")
    // ---- Module 15h - checklist EP/BVA, chấm theo nội dung, tự bù ----

    /** email bắt buộc + age bắt buộc (18..60): checklist đủ 3 nhóm = 1 + 2 + 4 = 7 mục. */
    private static final String REGISTER_SCHEMA = """
            {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["email","age"],
              "properties":{"email":{"type":"string"},"age":{"type":"integer","minimum":18,"maximum":60}}}}}}}""";




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

}
