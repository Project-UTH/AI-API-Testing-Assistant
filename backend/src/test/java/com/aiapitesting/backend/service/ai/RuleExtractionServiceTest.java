package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.response.RulesJobResult;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RequirementDocument;
import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.TestGenerationEvent;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.repository.BusinessRuleRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.UserRepository;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.FieldRule;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.ParamLocation;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.Spec;
import com.aiapitesting.backend.service.job.JobCancelledException;
import com.aiapitesting.backend.service.job.JobProgress;
import com.aiapitesting.backend.service.rule.RuleExtractionVerifier.Verdict;
import com.aiapitesting.backend.service.rule.RuleProposalWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Trích luật bằng AI: ngân sách token cứng, dừng giữ phần đã làm, token luôn được ghi, đầu ra AI luôn qua kiểm chứng. Không gọi AI thật. */
class RuleExtractionServiceTest {

    private static final String DOC = "# Tin tuyển dụng\n\nLương tối thiểu không được lớn hơn lương tối đa của tin tuyển dụng.\n";
    private static final String QUOTE = "Lương tối thiểu không được lớn hơn lương tối đa của tin tuyển dụng.";

    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final TestGenerationEventRepository events = mock(TestGenerationEventRepository.class);
    private final AiQuotaService quota = mock(AiQuotaService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TestCaseChecklistPlanner planner = mock(TestCaseChecklistPlanner.class);
    private final RuleProposalWriter writer = mock(RuleProposalWriter.class);
    private final BusinessRuleRepository ruleRepository = mock(BusinessRuleRepository.class);
    private RuleExtractionService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private Project project;
    private Endpoint endpoint;
    private RequirementDocument document;

    @BeforeEach
    void setUp() {
        service = new RuleExtractionService(chatClient, events, quota, users, planner, writer, ruleRepository);
        User owner = User.builder().id(ownerId).build();
        project = Project.builder().id(UUID.randomUUID()).owner(owner).build();
        endpoint = endpoint("POST", "/api/jobs");
        document = RequirementDocument.builder().id(UUID.randomUUID()).project(project).fileName("yeu-cau.md").content(DOC).build();
        when(users.findById(ownerId)).thenReturn(Optional.of(owner));
        when(quota.remainingToday(any(User.class))).thenReturn(1_000_000L);
        when(planner.readSpec(any())).thenReturn(new Spec("/api/jobs", true, true, List.of(field("salaryMin"), field("salaryMax"))));
        when(writer.write(any(), anyMap(), any(), any(), anyInt()))
                .thenReturn(new RuleProposalWriter.Outcome(1, 0, 0, 0, 0, 0, 0, Map.of()));
        when(writer.writeSuggestions(any(), anyMap(), any(), any()))
                .thenReturn(new RuleProposalWriter.Outcome(0, 0, 1, 0, 0, 0, 0, Map.of()));
        when(ruleRepository.findAllByProject(any())).thenReturn(List.of());
    }

    private static FieldRule field(String name) {
        return new FieldRule(name, ParamLocation.BODY, true, true, "integer", false, null, null, null, null, null, null, null, false, false, false);
    }

    private static Endpoint endpoint(String method, String path) {
        return Endpoint.builder().id(UUID.randomUUID()).method(method).path(path).build();
    }

    private void aiReturns(String text, int totalTokens) {
        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText()).thenReturn(text);
        when(response.getMetadata().getUsage().getTotalTokens()).thenReturn(totalTokens);
        when(chatClient.prompt(any(String.class)).options(any()).call().chatResponse()).thenReturn(response);
    }

    private static String ruleJson(String quote) {
        return "{\"rules\":[{\"endpoint\":\"POST /api/jobs\",\"template\":\"COMPARE_FIELDS\","
                + "\"params\":{\"left\":\"salaryMin\",\"op\":\"LTE\",\"right\":\"salaryMax\"},\"expectedStatus\":null,\"quote\":\""
                + quote + "\",\"rationale\":\"ý nghĩa\"}]}";
    }

    private RuleExtractionService.Request request(List<Endpoint> endpoints, boolean allowInferred, Integer tokenCap) {
        return new RuleExtractionService.Request(project, ownerId, endpoints, List.of(document), "", allowInferred, jobId, tokenCap);
    }

    @SuppressWarnings("unchecked")
    private List<Verdict> verdictsWritten() {
        ArgumentCaptor<List<Verdict>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).write(any(), anyMap(), captor.capture(), eq(jobId), anyInt());
        return captor.getValue();
    }

    private TestGenerationEvent eventSaved() {
        ArgumentCaptor<TestGenerationEvent> captor = ArgumentCaptor.forClass(TestGenerationEvent.class);
        verify(events).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void happyPath_verbatimQuoteBecomesDoc_tokensCountedAndLedgerRecordedAtProjectLevel() {
        aiReturns("```json\n" + ruleJson(QUOTE) + "\n```", 3_200);
        Progress ctx = new Progress();

        RulesJobResult result = service.run(request(List.of(endpoint), false, 60_000), ctx);

        List<Verdict> verdicts = verdictsWritten();
        assertThat(verdicts).singleElement().satisfies(v -> {
            assertThat(v.source()).isEqualTo(RuleSource.DOC);
            assertThat(v.documentId()).isEqualTo(document.getId());
            assertThat(DOC.substring(v.quoteOffset(), v.quoteOffset() + v.quote().length())).isEqualTo(QUOTE);
        });
        assertThat(ctx.tokensUsed()).isEqualTo(3_200);
        TestGenerationEvent event = eventSaved();
        assertThat(event.getEventType()).isEqualTo("RULE_EXTRACT");
        assertThat(event.getEndpoint()).isNull();
        assertThat(event.getOwnerId()).isEqualTo(ownerId);
        assertThat(event.getProjectId()).isEqualTo(project.getId());
        assertThat(event.getTotalTokens()).isEqualTo(3_200);
        assertThat(result.lotsDone()).isEqualTo(1);
        assertThat(result.stoppedReason()).isNull();
        assertThat(ctx.phases).containsExactly("DOC_READ", "RULES_PLAN", "RULES_AI", "RULES_VERIFY");
    }

    @Test
    void malformedAnswer_stillRecordsTheTokensSpent_andWritesNothing() {
        aiReturns("xin lỗi, tôi không thể trả JSON", 2_500);
        Progress ctx = new Progress();

        RulesJobResult result = service.run(request(List.of(endpoint), true, 60_000), ctx);

        verify(writer, never()).write(any(), anyMap(), any(), any(), anyInt());
        assertThat(ctx.tokensUsed()).isEqualTo(2_500);
        assertThat(eventSaved().getTotalTokens()).isEqualTo(2_500);
        assertThat(result.lotsDone()).isEqualTo(1);
        assertThat(ctx.failedSteps).isEqualTo(1);
    }

    @Test
    void inferredRulesAreDropped_unlessTheUserAllowsThem() {
        aiReturns(ruleJson("một đoạn trích do AI tự bịa ra không có trong tài liệu"), 1_000);
        RulesJobResult result = service.run(request(List.of(endpoint), false, 60_000), new Progress());

        assertThat(verdictsWritten()).isEmpty();
        assertThat(result.skippedInferred()).isEqualTo(1);
    }

    @Test
    void inferredRulesAreKeptWhenAllowed_labeledInferred() {
        aiReturns(ruleJson("một đoạn trích do AI tự bịa ra không có trong tài liệu"), 1_000);
        service.run(request(List.of(endpoint), true, 60_000), new Progress());

        assertThat(verdictsWritten()).singleElement().satisfies(v -> {
            assertThat(v.source()).isEqualTo(RuleSource.INFERRED);
            assertThat(v.quote()).isNull();
            assertThat(v.rationale()).startsWith("GIẢ ĐỊNH");
        });
    }

    @Test
    void dryRun_plansAndEstimatesButNeverCallsAiOrWrites() {
        Progress ctx = new Progress();
        ctx.dryRun = true;

        RulesJobResult result = service.run(request(List.of(endpoint), true, 60_000), ctx);

        verifyNoInteractions(chatClient, writer, events);
        assertThat(result.estimatedTokens()).isPositive();
        assertThat(result.lotsPlanned()).isEqualTo(1);
        assertThat(result.lotsDone()).isZero();
        assertThat(ctx.phases).containsExactly("DOC_READ", "RULES_PLAN");
    }

    @Test
    void tinyBudget_stopsBeforeAnyAiCall() {
        RulesJobResult result = service.run(request(List.of(endpoint), true, 5_000), new Progress());

        verifyNoInteractions(chatClient, writer);
        assertThat(result.stoppedReason()).isEqualTo("BUDGET_STOPPED");
        assertThat(result.lotsDone()).isZero();
    }

    @Test
    void dailyQuotaExhausted_stopsWithQuotaReason() {
        when(quota.remainingToday(any(User.class))).thenReturn(0L);

        RulesJobResult result = service.run(request(List.of(endpoint), true, 60_000), new Progress());

        verifyNoInteractions(chatClient);
        assertThat(result.stoppedReason()).isEqualTo("DAILY_QUOTA");
    }

    @Test
    void budgetSpentOnTheFirstLot_stopsTheSecondLot_butKeepsTheFirstLotsWork() {
        List<Endpoint> many = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            many.add(endpoint("GET", "/api/jobs/" + i));
        }
        aiReturns("{\"rules\":[]}", 58_000);

        RulesJobResult result = service.run(request(many, true, 60_000), new Progress());

        assertThat(result.lotsPlanned()).isEqualTo(2);
        assertThat(result.lotsDone()).isEqualTo(1);
        assertThat(result.stoppedReason()).isEqualTo("BUDGET_STOPPED");
        verify(chatClient, org.mockito.Mockito.atLeastOnce()).prompt(any(String.class));
    }

    @Test
    void cancelledJob_throwsBeforeSpendingTokens() {
        Progress ctx = new Progress();
        ctx.cancelled = true;

        assertThatThrownBy(() -> service.run(request(List.of(endpoint), true, 60_000), ctx)).isInstanceOf(JobCancelledException.class);
        verifyNoInteractions(chatClient, writer);
    }

    @Test
    void aiFailure_stopsWithAiError_andDoesNotRecordTokens() {
        when(chatClient.prompt(any(String.class)).options(any()).call().chatResponse()).thenThrow(new IllegalStateException("401 sk-secret-key"));
        Progress ctx = new Progress();

        RulesJobResult result = service.run(request(List.of(endpoint), true, 60_000), ctx);

        assertThat(result.stoppedReason()).isEqualTo("AI_ERROR");
        assertThat(result.lotsDone()).isZero();
        assertThat(ctx.tokensUsed()).isZero();
        assertThat(ctx.details).noneMatch(d -> d.contains("sk-secret-key")); // không đưa thông điệp lỗi gốc ra bước giám sát
    }

    @Test
    void secretsInTheDocumentAreMaskedBeforeTheyReachTheModel() {
        document.setContent(DOC + "\napi_key = abcdef1234567890SECRET\n");
        aiReturns("{\"rules\":[]}", 500);

        service.run(request(List.of(endpoint), true, 60_000), new Progress());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatClient, org.mockito.Mockito.atLeastOnce()).prompt(prompt.capture());
        assertThat(prompt.getAllValues().get(prompt.getAllValues().size() - 1)).contains(QUOTE).doesNotContain("abcdef1234567890SECRET");
    }

    @Test
    void parse_ignoresFencesAndCountsBrokenRules() {
        RuleExtractionService.ParsedAnswer parsed = service.parse("văn bản thừa ```json\n{\"rules\":[{\"endpoint\":\"POST /api/jobs\",\"template\":\"OR\","
                + "\"params\":{\"fields\":[\"a\",\"b\"]},\"quote\":\"q\"},{\"endpoint\":\"x\",\"params\":{\"min\":\"không phải số\"}}]}\n``` hết");
        assertThat(parsed.rules()).hasSize(1);
        assertThat(parsed.malformed()).isEqualTo(1);
        assertThatThrownBy(() -> service.parse("không có json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.parse("{\"khac\":1}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_salvagesCompleteRulesFromTruncatedJson() {
        RuleExtractionService.ParsedAnswer parsed = service.parse("{\"rules\":[{\"endpoint\":\"POST /api/jobs\",\"template\":\"OR\",\"quote\":\"a } b\"},"
                + "{\"endpoint\":\"POST /api/jobs\",\"template\":\"OR\",\"quote\":\"x\"},{\"endpoint\":\"POST /api/jo");
        assertThat(parsed.rules()).hasSize(2);
    }

    @Test
    void splitLong_cutsHugeBlocksSoEveryPieceFits() {
        String block = ("dòng dài ".repeat(50) + System.lineSeparator()).repeat(30) + "x".repeat(10_000);
        assertThat(RuleExtractionService.splitLong(block, 3_000)).allMatch(piece -> piece.length() <= 3_000).hasSizeGreaterThan(3);
    }

    @Test
    void noDocumentAndNoInstruction_stopsWithoutCallingAi() {
        RequirementDocument empty = RequirementDocument.builder().id(UUID.randomUUID()).project(project).fileName("a.md").content("").build();
        RulesJobResult result = service.run(new RuleExtractionService.Request(project, ownerId, List.of(endpoint), List.of(), "  ", true, jobId, 60_000),
                new Progress());
        verifyNoInteractions(chatClient, writer);
        assertThat(result.stoppedReason()).isEqualTo("NO_INPUT");
        assertThat(empty.getFileName()).isEqualTo("a.md");
    }

    private static String suggestJson(String status) {
        return "{\"rules\":[{\"endpoint\":\"POST /api/jobs\",\"template\":\"COMPARE_FIELDS\","
                + "\"params\":{\"left\":\"salaryMin\",\"op\":\"LTE\",\"right\":\"salaryMax\"},\"suggestedStatus\":" + status
                + ",\"quote\":\"" + QUOTE + "\",\"expectedStatus\":422,\"rationale\":\"lương tối thiểu hợp lý nhỏ hơn tối đa\"}]}";
    }

    @SuppressWarnings("unchecked")
    private List<Verdict> suggestionsWritten() {
        ArgumentCaptor<List<Verdict>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeSuggestions(any(), anyMap(), captor.capture(), eq(jobId));
        return captor.getValue();
    }

    @Test
    void noDocumentButSuggest_runsOnlyTheSuggestionLane_alwaysInferredWithoutQuote_andKeepsOnlyAllowedSuggestedStatus() {
        aiReturns(suggestJson("422"), 2_000);
        Progress ctx = new Progress();

        RulesJobResult result = service.run(new RuleExtractionService.Request(project, ownerId, List.of(endpoint), List.of(), "", true, jobId, 60_000,
                true, 0), ctx);

        assertThat(suggestionsWritten()).singleElement().satisfies(v -> {
            assertThat(v.source()).isEqualTo(RuleSource.INFERRED);
            assertThat(v.quote()).isNull();            // AI có đưa quote cũng bị bỏ
            assertThat(v.expectedStatus()).isNull();   // mã chỉ là GỢI Ý
            assertThat(v.suggestedStatus()).isEqualTo(422);
        });
        verify(writer, never()).write(any(), anyMap(), any(), any(), anyInt());
        assertThat(result.suggested()).isEqualTo(1);
        assertThat(result.stoppedReason()).isNull();
        assertThat(ctx.tokensUsed()).isEqualTo(2_000);
    }

    @Test
    void suggestedStatusOutsideTheNarrowSet_isDropped() {
        aiReturns(suggestJson("200"), 1_000);
        service.run(new RuleExtractionService.Request(project, ownerId, List.of(endpoint), List.of(), "", true, jobId, 60_000, true, 0), new Progress());
        assertThat(suggestionsWritten()).singleElement().satisfies(v -> assertThat(v.suggestedStatus()).isNull());
    }

    @Test
    void budgetCountsOnlyTokensSpentByTheExtractionPhase_notTheBaselineTokensTheJobAlreadyUsed() {
        aiReturns("{\"rules\":[]}", 1_000);
        Progress ctx = new Progress();
        ctx.tokens = 200_000; // test gốc đã ăn 200.000 token trước pha trích
        RulesJobResult result = service.run(new RuleExtractionService.Request(project, ownerId, List.of(endpoint), List.of(document), "", false, jobId,
                60_000, false, 200_000), ctx);
        assertThat(result.stoppedReason()).isNull();
        assertThat(result.lotsDone()).isEqualTo(1);
    }

    @Test
    void documentAndSuggest_runsBothLanes_andQuestionsAreCappedAndCleaned() {
        aiReturns("{\"rules\":[],\"questions\":[\"Hạn nộp tối đa bao nhiêu ngày?\",\"  \",12,\"" + "x".repeat(500) + "\"]}", 1_000);
        RulesJobResult result = service.run(new RuleExtractionService.Request(project, ownerId, List.of(endpoint), List.of(document), "", true, jobId,
                60_000, true, 0), new Progress());
        assertThat(result.questions()).hasSize(2);
        assertThat(result.questions().get(1)).hasSize(RuleExtractionService.MAX_QUESTION_CHARS);
        verify(chatClient, org.mockito.Mockito.atLeast(2)).prompt(any(String.class));
    }

    @Test
    void suggestPrompt_fencesTheEndpointDigestWithTheNonce_andNeverSendsTheDocument() {
        aiReturns("{\"rules\":[]}", 500);
        service.run(new RuleExtractionService.Request(project, ownerId, List.of(endpoint), List.of(document), "", true, jobId, 60_000, true, 0),
                new Progress());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatClient, org.mockito.Mockito.atLeast(2)).prompt(prompt.capture());
        String suggestPrompt = prompt.getAllValues().get(prompt.getAllValues().size() - 1);
        assertThat(suggestPrompt).contains("BEGIN_ENDPOINTS").contains("END_ENDPOINTS").doesNotContain(QUOTE);
    }

    @Test
    void cleanSummary_stripsControlCharsAndCuts() {
        assertThat(RuleExtractionService.cleanSummary("a" + (char) 10 + "b" + (char) 7 + " c" + "x".repeat(300))).startsWith("a b c").hasSizeLessThanOrEqualTo(120);
    }

    @Test
    void planLots_groupsByFirstSegmentAndCapsLotSize() {
        List<Endpoint> all = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            all.add(endpoint("GET", "/api/v1/users/" + i));
        }
        all.add(endpoint("GET", "/api/orders"));

        List<List<Endpoint>> lots = RuleExtractionService.planLots(all);

        assertThat(lots).hasSize(2);
        assertThat(lots.get(0)).hasSize(RuleExtractionService.LOT_SIZE);
        assertThat(lots.stream().mapToInt(List::size).sum()).isEqualTo(15);
        assertThat(RuleExtractionService.budgetOf(null)).isEqualTo(RuleExtractionService.DEFAULT_BUDGET);
        assertThat(RuleExtractionService.budgetOf(1)).isEqualTo(RuleExtractionService.MIN_BUDGET);
    }

    /** JobProgress giả ghi lại các bước, token và cờ huỷ. */
    private static final class Progress implements JobProgress {
        final List<String> phases = new ArrayList<>();
        final List<String> details = new ArrayList<>();
        int failedSteps;
        int tokens;
        boolean cancelled;
        boolean dryRun;

        @Override
        public Step start(UUID endpointId, String endpointLabel, String phase, String actor, String title) {
            phases.add(phase);
            return (status, detail, httpStatus, t) -> {
                if (detail != null) {
                    details.add(detail);
                }
                if ("FAILED".equals(status)) {
                    failedSteps++;
                }
            };
        }

        @Override
        public void checkCancelled() {
            if (cancelled) {
                throw new JobCancelledException();
            }
        }

        @Override
        public int addTokens(int t) {
            tokens += t;
            return tokens;
        }

        @Override
        public int tokensUsed() {
            return tokens;
        }

        @Override
        public int tokenCap() {
            return 0;
        }

        @Override
        public boolean isDryRun() {
            return dryRun;
        }
    }
}
