package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.request.GenerateTestCasesRequest;
import com.aiapitesting.backend.dto.response.ChecklistItemResponse;
import com.aiapitesting.backend.dto.response.ChecklistItemStatus;
import com.aiapitesting.backend.dto.response.ChecklistItemType;
import com.aiapitesting.backend.dto.response.GenerateTestCasesResponse;
import com.aiapitesting.backend.dto.response.GenerationPlanSummary;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
import com.aiapitesting.backend.entity.TestCaseReliability;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.entity.TestGenerationEvent;
import com.aiapitesting.backend.exception.AiGenerationFailedException;
import com.aiapitesting.backend.exception.AiQuotaExceededException;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.InvalidRequestException;
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
import com.aiapitesting.backend.service.TestCaseReliabilityValidator;
import com.aiapitesting.backend.service.TestCaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Sinh test case bằng AI. Không đọc/ghi Project.targetAuthType hay targetAuthValueEncrypted
 * dưới bất kỳ hình thức nào - việc gắn auth thật vào request thuộc về Module 6 lúc thực thi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestCaseGenerationService {

    private static final int MIN_STATUS = 100;
    private static final int MAX_STATUS = 599;
    /** Độ dài cột TestCase.checklistItem (VARCHAR(255)). */
    private static final int CHECKLIST_LABEL_MAX = 255;

    private final ChatClient chatClient;
    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseDependencyRepository testCaseDependencyRepository;
    private final TestCaseAssertionRepository testCaseAssertionRepository;
    private final TestResultRepository testResultRepository;
    private final BugReportRepository bugReportRepository;
    private final TestCasePathValidator testCasePathValidator;
    private final TestCaseReliabilityValidator testCaseReliabilityValidator;
    private final TestCaseReliabilityConfirmationService testCaseReliabilityConfirmationService;
    private final TestCaseService testCaseService;
    private final TestCaseChecklistPlanner testCaseChecklistPlanner;
    private final TestGenerationEventRepository testGenerationEventRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("classpath:prompts/generate-test-case.st")
    private Resource promptResource = new ClassPathResource("prompts/generate-test-case.st");

    @Value("${ai.quota.daily-token-limit}")
    private long dailyTokenLimit;

    @Async
    @Transactional
    public CompletableFuture<GenerateTestCasesResponse> generate(UUID projectId, UUID endpointId, GenerateTestCasesRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        Endpoint endpoint = endpointRepository.findByIdAndProject(endpointId, project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));
        ensureWithinDailyQuota(project);
        boolean includeSecurity = request != null && request.includeSecurity();
        // request == null giữ hành vi mặc định cũ: sinh cả 3 nhóm. Khi có body, đọc đúng giá trị đã gửi.
        boolean includePositive = request == null || request.includePositive();
        boolean includeNegative = request == null || request.includeNegative();
        boolean includeBoundary = request == null || request.includeBoundary();
        if (!includePositive && !includeNegative && !includeBoundary && !includeSecurity) {
            throw new InvalidRequestException(
                    "Phải chọn ít nhất 1 loại test case để sinh (Positive/Negative/Boundary/Security)");
        }

        // Ghi lại loại test case vừa chọn làm phạm vi đo coverage của endpoint: trang Coverage chỉ tính các loại
        // này (cột null = chưa từng chọn → đủ 4 loại).
        endpoint.setScopePositive(includePositive);
        endpoint.setScopeNegative(includeNegative);
        endpoint.setScopeBoundary(includeBoundary);
        endpoint.setScopeSecurity(includeSecurity);
        endpointRepository.save(endpoint);

        // Mỗi lần bấm "Sinh Test Case" khai báo lại toàn bộ tập test case AI-quản-lý cho endpoint
        // này = đúng những gì đang được tick. Nhóm có tick -> gọi AI sinh lại (xoá-và-thay). Nhóm
        // KHÔNG tick -> không gọi AI, chỉ xoá sạch case cũ (chưa khoá) còn sót từ lần sinh trước, để
        // tránh tình trạng case Security cũ (hoặc Positive/Negative/Boundary cũ) còn lưu lại dù lần
        // này người dùng chủ động bỏ tick. Case đã khoá luôn được giữ nguyên ở cả 2 nhánh.
        List<GroupResult> groups = new ArrayList<>();
        if (includePositive || includeNegative || includeBoundary) {
            groups.add(generateGroup(endpoint, TestCaseSource.AI_GENERATED, false,
                    includePositive, includeNegative, includeBoundary));
        } else {
            deleteExistingGroup(endpoint, TestCaseSource.AI_GENERATED);
        }
        if (includeSecurity) {
            groups.add(generateGroup(endpoint, TestCaseSource.SECURITY, true,
                    includePositive, includeNegative, includeBoundary));
        } else {
            deleteExistingGroup(endpoint, TestCaseSource.SECURITY);
        }

        GenerationPlanSummary planSummary = merge(groups.stream().map(GroupResult::planSummary).toList());
        if (groups.size() > 1) {
            // Báo cáo đọc lại sau khi tải trang lấy từ dòng lịch sử MỚI NHẤT - ghi bản gộp cả 2 nhóm vào đó.
            TestGenerationEvent last = groups.get(groups.size() - 1).event();
            last.setPlanSummaryJson(writePlanSummaryAsJson(planSummary));
            testGenerationEventRepository.save(last);
        }
        List<TestCaseResponse> saved = groups.stream()
                .flatMap(g -> g.saved().stream())
                .map(TestCaseResponse::from)
                .toList();
        return CompletableFuture.completedFuture(new GenerateTestCasesResponse(saved, planSummary));
    }

    /** Gộp báo cáo của nhóm Cơ bản + Security trong cùng 1 lần bấm "Sinh Test Case" (Module 15i). */
    private GenerationPlanSummary merge(List<GenerationPlanSummary> summaries) {
        if (summaries.size() == 1) {
            return summaries.get(0);
        }
        List<ChecklistItemResponse> items = new ArrayList<>();
        List<String> extraCaseNames = new ArrayList<>();
        int[] totals = new int[9];
        for (GenerationPlanSummary s : summaries) {
            items.addAll(s.items());
            extraCaseNames.addAll(s.extraCaseNames());
            int[] values = {s.checklistTotal(), s.alreadyCoveredCount(), s.aiAskedCount(), s.aiSatisfiedCount(),
                    s.systemFilledCount(), s.notFillableCount(), s.notApplicableCount(), s.aiExtraCount(), s.checklistCaseCount()};
            for (int i = 0; i < totals.length; i++) {
                totals[i] += values[i];
            }
        }
        return new GenerationPlanSummary(totals[0], totals[1], totals[2], totals[3], totals[4], totals[5], totals[6],
                totals[7], totals[8], extraCaseNames, items);
    }

    /**
     * Báo cáo checklist của lần sinh gần nhất (Module 15h) - để xem lại sau khi tải lại trang. Từ 15i
     * mọi lần sinh đều có báo cáo (cả nhóm Security, gộp nếu sinh cả 2 nhóm) nên chỉ cần đọc dòng lịch
     * sử MỚI NHẤT. Null khi chưa sinh lần nào hoặc dòng đó tạo trước khi có tính năng.
     */
    @Transactional(readOnly = true)
    public GenerationPlanSummary getLatestPlanSummary(UUID projectId, UUID endpointId) {
        Project project = projectService.getOwnedProject(projectId);
        Endpoint endpoint = endpointRepository.findByIdAndProject(endpointId, project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));
        return testGenerationEventRepository.findFirstByEndpointOrderByCreatedAtDesc(endpoint)
                .filter(event -> event.getPlanSummaryJson() != null)
                .map(event -> readPlanSummary(event.getPlanSummaryJson()))
                .orElse(null);
    }

    private GenerationPlanSummary readPlanSummary(String json) {
        try {
            return objectMapper.readValue(json, GenerationPlanSummary.class);
        } catch (Exception e) {
            // Không để 1 dòng JSON hỏng làm trang Endpoint trả 500 (bài học 15d) - coi như chưa có báo cáo.
            log.warn("Khong doc duoc bao cao checklist da luu: {}", e.toString());
            return null;
        }
    }

    /** Module 15i - cả 2 nhóm đều có planSummary; event để generate() ghi báo cáo gộp vào dòng lịch sử cuối cùng. */
    private record GroupResult(List<TestCase> saved, GenerationPlanSummary planSummary, TestGenerationEvent event) {
    }

    /** 1 case sẽ lưu cùng nguồn gốc + mục checklist mà nó đạt (Module 15h). */
    private record PlannedCase(GeneratedTestCase generated, TestCaseGenerationOrigin origin, String checklistItem,
                               boolean tier1Blind) {
        PlannedCase(GeneratedTestCase generated, TestCaseGenerationOrigin origin, String checklistItem) {
            this(generated, origin, checklistItem, false);
        }
    }

    /**
     * Chặn SỚM trước khi gọi AI - không tốn request nếu đã vượt quota. Kiểm tra 1 lần ở đầu
     * generate() vì 1 lần sinh có thể kéo theo 2 lệnh gọi AI (Cơ bản + Security), phải cùng bị
     * chặn hoặc cùng được phép.
     */
    private void ensureWithinDailyQuota(Project project) {
        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant startOfTomorrow = startOfToday.plus(1, ChronoUnit.DAYS);
        long usedToday = testGenerationEventRepository.sumTotalTokensByOwnerAndCreatedAtBetween(
                project.getOwner(), startOfToday, startOfTomorrow);
        // null = chưa ghi đè riêng, dùng mặc định hệ thống.
        Integer override = project.getOwner().getAiDailyTokenLimitOverride();
        long effectiveLimit = override != null ? override : dailyTokenLimit;
        if (usedToday >= effectiveLimit) {
            throw new AiQuotaExceededException(
                    "Đã đạt giới hạn " + effectiveLimit + " token AI hôm nay, vui lòng thử lại vào ngày mai");
        }
    }

    /**
     * Bản không throw của ensureWithinDailyQuota() - dùng cho Tier 2 (Module 15d), nơi hết quota chỉ
     * cần BỎ QUA bước xác nhận thêm (giữ nguyên kết quả Tier 1), không được chặn việc lưu test case
     * chính đã sinh thành công.
     */
    private boolean isWithinDailyQuota(Project project) {
        try {
            ensureWithinDailyQuota(project);
            return true;
        } catch (AiQuotaExceededException e) {
            return false;
        }
    }

    private Integer sumNullable(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a + b;
    }

    /**
     * Xoá sạch test case chưa khoá thuộc đúng 1 source (Cơ bản hoặc Security) của endpoint, không
     * gọi AI và không ghi TestGenerationEvent - dùng khi nhóm đó KHÔNG được tick trong lần sinh này
     * (dọn case cũ còn sót từ 1 lần sinh trước đó có tick), hoặc làm bước dọn dẹp trước khi
     * generateGroup() lưu batch mới. Test case đã khoá luôn được giữ nguyên (đã lọc LockedFalse).
     */
    private void deleteExistingGroup(Endpoint endpoint, TestCaseSource source) {
        List<TestCase> existing = testCaseRepository.findAllByEndpointAndSourceAndLockedFalse(endpoint, source);
        // Chặn xoá nếu còn test case khác đang phụ thuộc dữ liệu từ 1 trong các test case sắp bị xoá.
        testCaseService.ensureNoDependents(existing);

        // Dọn BugReport + TestResult + TestCaseDependency + TestCaseAssertion trước khi xoá, tránh
        // lỗi khoá ngoại.
        try {
            bugReportRepository.deleteAllByTestCaseIn(existing);
            testResultRepository.deleteAllByTestCaseIn(existing);
            testCaseDependencyRepository.deleteAllByTestCaseIn(existing);
            testCaseAssertionRepository.deleteAllByTestCaseIn(existing);
            testCaseRepository.deleteAllByEndpointAndSourceAndLockedFalse(endpoint, source);
        } catch (DataIntegrityViolationException e) {
            // 2 lượt "Sinh Test Case" cho cùng endpoint chạy đồng thời (vd 2 tab) có thể lọt qua
            // chặn ở frontend - lượt sau xoá theo (endpoint, source) đụng vào test case mới lượt
            // trước vừa tạo, vỡ khoá ngoại. Không phải lỗi hệ thống, báo người dùng thử lại.
            log.warn("Xung dot dong thoi khi xoa test case cu cho endpoint {} (source {}): {}",
                    endpoint.getId(), source, e.toString());
            throw new AiGenerationFailedException(
                    "Endpoint này đang được sinh test case ở nơi khác cùng lúc (có thể do mở nhiều tab) - vui lòng tải lại trang và thử lại");
        }
    }

    /**
     * Sinh + lưu 1 nhóm test case (Cơ bản hoặc Security) - xoá-và-thay đúng theo source, không đụng
     * nhóm khác. Mỗi nhóm là 1 lệnh gọi AI + 1 dòng TestGenerationEvent riêng.
     * includePositive/includeNegative/includeBoundary chỉ có ý nghĩa khi includeSecurity=false.
     *
     * Module 15h: lập checklist EP/BVA → chấm trước case sẽ còn lại (đã khoá/MANUAL) → giao AI các mục
     * còn lại → CHẤM NỘI DUNG case AI trả về (không tin lời AI tự khai) → tự bù mục AI làm thiếu/sai từ
     * 1 case gốc. Vẫn đúng 1 lệnh gọi AI chính. Module 15i: áp dụng cho cả nhóm Security.
     */
    private GroupResult generateGroup(
            Endpoint endpoint, TestCaseSource source, boolean includeSecurity,
            boolean includePositive, boolean includeNegative, boolean includeBoundary
    ) {
        Map<TestCaseChecklistPlanner.ChecklistItem, ChecklistItemStatus> statuses = new LinkedHashMap<>();
        Map<TestCaseChecklistPlanner.ChecklistItem, String> reasons = new HashMap<>();
        // Tên các test case đạt từng mục - để báo cáo chỉ rõ case nào ứng với mục nào.
        Map<TestCaseChecklistPlanner.ChecklistItem, List<String>> coveredBy = new HashMap<>();
        // Module 15i - cả nhóm Security cũng có checklist riêng (thiếu token, token sai). Giao AI TOÀN BỘ
        // mục (bỏ giới hạn 15 mục/lần của 15h - người dùng chốt nâng max-tokens thay vì cắt bớt).
        TestCaseChecklistPlanner.Spec spec = testCaseChecklistPlanner.readSpec(endpoint);
        List<TestCaseChecklistPlanner.ChecklistItem> items = includeSecurity
                ? testCaseChecklistPlanner.planSecurity(endpoint.isRequiresAuth())
                : testCaseChecklistPlanner.plan(spec, includePositive, includeNegative, includeBoundary);
        List<GeneratedTestCase> survivors = survivingCases(endpoint, source);
        for (TestCaseChecklistPlanner.ChecklistItem item : items) {
            if (item.notApplicableReason() != null) {
                statuses.put(item, ChecklistItemStatus.NOT_APPLICABLE);
                reasons.put(item, item.notApplicableReason());
                continue;
            }
            for (GeneratedTestCase survivor : survivors) {
                if (testCaseChecklistPlanner.satisfies(spec, item, survivor)) {
                    statuses.put(item, ChecklistItemStatus.ALREADY_COVERED);
                    coveredBy.computeIfAbsent(item, k -> new ArrayList<>()).add(survivor.name());
                }
            }
        }
        List<TestCaseChecklistPlanner.ChecklistItem> askAi = items.stream()
                .filter(item -> !statuses.containsKey(item))
                .toList();

        Prompt prompt = buildPrompt(endpoint, includeSecurity,
                includePositive, includeNegative, includeBoundary, askAi);

        List<GeneratedTestCase> generated;
        Usage usage;
        try {
            // responseEntity() lấy được cả ChatResponse gốc (chứa usage token thật) và danh sách đã
            // parse trong cùng 1 lệnh gọi - không tốn thêm lượt gọi AI nào.
            ResponseEntity<ChatResponse, List<GeneratedTestCase>> response = chatClient.prompt(prompt).call()
                    .responseEntity(new ParameterizedTypeReference<List<GeneratedTestCase>>() {
                    });
            generated = response.entity();
            usage = response.response().getMetadata().getUsage();
        } catch (Exception e) {
            // Log lỗi gốc để chẩn đoán, không log prompt/nội dung nhạy cảm.
            log.warn("Goi AI that bai khi sinh test case cho endpoint {}: {}", endpoint.getId(), e.toString());
            throw new AiGenerationFailedException("Không thể sinh test case từ AI, vui lòng thử lại");
        }
        validate(generated);

        deleteExistingGroup(endpoint, source);

        List<PlannedCase> planned = gradeAndFill(spec, items, statuses, reasons, coveredBy, generated, survivors,
                includeSecurity || includePositive, includeSecurity);
        GenerationPlanSummary planSummary = summarize(items, statuses, reasons, coveredBy, askAi.size(), planned);

        List<TestCase> entities = planned.stream()
                .map(p -> toEntity(endpoint, p.generated(), source, p.origin(), p.checklistItem(), p.tier1Blind()))
                .toList();

        // Module 15g - lưu event Tier 1 NGAY ở đây (chỉ với token `usage` của lệnh gọi chính), TRƯỚC
        // khi kiểm quota cho Tier 2. Lý do: isWithinDailyQuota() đọc tổng token từ DB
        // (sumTotalTokensByOwnerAndCreatedAtBetween) - nếu lưu event SAU khi đã quyết định có gọi
        // Tier 2 hay không (như bản cũ), quyết định đó dựa trên số liệu CŨ, chưa cộng phần token Tier 1
        // vừa tiêu - lỗ hổng TOCTOU thật (lệch tối đa 1 lần gọi Tier 2 ngoài ý muốn), phát hiện khi đối
        // chiếu lại bằng agent phản biện đọc code thật. Lưu trước rồi UPDATE lại (không tạo dòng mới)
        // nếu Tier 2 có chạy - vẫn giữ đúng nguyên tắc "1 dòng sự kiện duy nhất cho 1 lượt sinh" (15d).
        // Module 15h - snapshot chứa CẢ case hệ thống bù để trang Lịch sử hiện đúng số case thực tế.
        // Giữ chính đối tượng vừa tạo (JPA persist trả về cùng instance) để Tier 2 / báo cáo gộp 15i cập nhật lại.
        TestGenerationEvent event = TestGenerationEvent.builder()
                .endpoint(endpoint)
                .testCaseCount(entities.size())
                .snapshotJson(writeSnapshotAsJson(planned.stream().map(PlannedCase::generated).toList()))
                .planSummaryJson(writePlanSummaryAsJson(planSummary))
                .promptTokens(usage == null ? null : usage.getPromptTokens())
                .completionTokens(usage == null ? null : usage.getCompletionTokens())
                .totalTokens(usage == null ? null : usage.getTotalTokens())
                .build();
        testGenerationEventRepository.save(event);

        // Module 15d (Tier 2) - CHỈ gọi thêm AI khi có case bị Tier 1 gắn cờ, gộp hết vào ĐÚNG 1 lệnh
        // gọi bổ sung (không gọi riêng từng case). Kiểm tra quota TRƯỚC (không throw - khác
        // ensureWithinDailyQuota() ở đầu generate(), ở đây hết quota chỉ bỏ qua Tier 2, KHÔNG chặn
        // việc lưu test case Tier 1 đã có sẵn).
        List<TestCase> flagged = entities.stream()
                .filter(tc -> tc.getReliabilityStatus() == TestCaseReliability.NEEDS_REVIEW)
                .toList();
        Usage tier2Usage = null;
        if (!flagged.isEmpty() && isWithinDailyQuota(endpoint.getProject())) {
            tier2Usage = testCaseReliabilityConfirmationService.confirm(endpoint, flagged);
        }
        if (tier2Usage != null) {
            event.setPromptTokens(sumNullable(event.getPromptTokens(), tier2Usage.getPromptTokens()));
            event.setCompletionTokens(sumNullable(event.getCompletionTokens(), tier2Usage.getCompletionTokens()));
            event.setTotalTokens(sumNullable(event.getTotalTokens(), tier2Usage.getTotalTokens()));
            testGenerationEventRepository.save(event);
        }

        return new GroupResult(testCaseRepository.saveAll(entities), planSummary, event);
    }

    /**
     * Module 15h - chấm từng case AI trả về theo NỘI DUNG, gắn nguồn gốc, rồi tự bù mục còn thiếu.
     * Khi người dùng KHÔNG tick Positive: AI vẫn sinh 1 case gốc hợp lệ để hệ thống dùng làm nền bù,
     * nhưng case gốc thuần (không đạt mục checklist nào khác) bị bỏ, không lưu thành test case.
     */
    private List<PlannedCase> gradeAndFill(
            TestCaseChecklistPlanner.Spec spec,
            List<TestCaseChecklistPlanner.ChecklistItem> items,
            Map<TestCaseChecklistPlanner.ChecklistItem, ChecklistItemStatus> statuses,
            Map<TestCaseChecklistPlanner.ChecklistItem, String> reasons,
            Map<TestCaseChecklistPlanner.ChecklistItem, List<String>> coveredBy,
            List<GeneratedTestCase> generated,
            List<GeneratedTestCase> survivors,
            boolean includePositive,
            boolean security
    ) {
        List<PlannedCase> planned = new ArrayList<>();
        GeneratedTestCase baseline = null;
        boolean baselineDropped = false;
        // Case hợp lệ nhưng đã đạt 1 mục CỤ THỂ hơn (vd age=18 đúng minimum) cũng đạt luôn mục "Dữ liệu
        // hợp lệ" - báo cáo chỉ liệt kê nó dưới mục cụ thể, trừ khi không có case thuần hợp lệ nào.
        List<String> positiveAlsoBySpecificCases = new ArrayList<>();
        for (GeneratedTestCase g : generated) {
            List<TestCaseChecklistPlanner.ChecklistItem> hits = items.stream()
                    .filter(item -> !statuses.containsKey(item) || statuses.get(item) == ChecklistItemStatus.AI_SATISFIED)
                    .filter(item -> testCaseChecklistPlanner.satisfies(spec, item, g))
                    .toList();
            boolean validBaseline = testCaseChecklistPlanner.isValidBaseline(spec, g);
            if (validBaseline && baseline == null) {
                baseline = g;
            }
            if (!includePositive && validBaseline && hits.isEmpty() && !baselineDropped) {
                baselineDropped = true;
                continue;
            }
            List<TestCaseChecklistPlanner.ChecklistItem> specificHits = hits.stream()
                    .filter(item -> item.type() != ChecklistItemType.POSITIVE_BASELINE)
                    .toList();
            for (TestCaseChecklistPlanner.ChecklistItem item : hits) {
                statuses.put(item, ChecklistItemStatus.AI_SATISFIED);
                if (item.type() == ChecklistItemType.POSITIVE_BASELINE && !specificHits.isEmpty()) {
                    positiveAlsoBySpecificCases.add(g.name());
                } else {
                    coveredBy.computeIfAbsent(item, k -> new ArrayList<>()).add(g.name());
                }
            }
            TestCaseChecklistPlanner.ChecklistItem main = hits.isEmpty() ? null : specificHits.isEmpty() ? hits.get(0) : specificHits.get(0);
            planned.add(main == null
                    ? new PlannedCase(g, TestCaseGenerationOrigin.AI_EXTRA, null)
                    : new PlannedCase(g, TestCaseGenerationOrigin.AI_CHECKLIST, label(main), main.tier1CannotSee()));
        }
        for (TestCaseChecklistPlanner.ChecklistItem item : items) {
            if (item.type() == ChecklistItemType.POSITIVE_BASELINE
                    && statuses.get(item) == ChecklistItemStatus.AI_SATISFIED && !coveredBy.containsKey(item)) {
                coveredBy.put(item, positiveAlsoBySpecificCases);
            }
        }
        if (baseline == null) {
            baseline = survivors.stream().filter(s -> testCaseChecklistPlanner.isValidBaseline(spec, s)).findFirst().orElse(null);
        }
        // Security chỉ đổi cách gọi (authOverride), không cần body hợp lệ tuyệt đối - không có case
        // hợp lệ nào thì lấy tạm case AI sinh đầu tiên làm gốc (vd case injection).
        if (baseline == null && security && !generated.isEmpty()) {
            baseline = generated.get(0);
        }

        for (TestCaseChecklistPlanner.ChecklistItem item : items) {
            if (statuses.containsKey(item)) {
                continue;
            }
            GeneratedTestCase filled = baseline == null ? null : testCaseChecklistPlanner.fill(spec, item, baseline);
            if (filled == null) {
                statuses.put(item, ChecklistItemStatus.NOT_FILLABLE);
                reasons.put(item, baseline == null
                        ? "AI không sinh được case hợp lệ nào (kỳ vọng thành công, không vi phạm ràng buộc) để hệ thống dùng làm gốc tạo case bù"
                        : "Hệ thống không tạo được case bù đúng 1 lỗi cho mục này từ case gốc (vd không đối chiếu được vị trí tham số path)");
            } else {
                statuses.put(item, ChecklistItemStatus.SYSTEM_FILLED);
                coveredBy.computeIfAbsent(item, k -> new ArrayList<>()).add(filled.name());
                planned.add(new PlannedCase(filled, TestCaseGenerationOrigin.SYSTEM_FILLED, label(item), item.tier1CannotSee()));
            }
        }
        return planned;
    }

    /** Case sẽ CÒN LẠI sau lần sinh này (đã khoá trong đúng nhóm đang sinh + mọi case tự thêm) - mục nào chúng đã đạt thì không giao AI nữa. */
    private List<GeneratedTestCase> survivingCases(Endpoint endpoint, TestCaseSource source) {
        return testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint).stream()
                .filter(tc -> tc.getSource() == TestCaseSource.MANUAL
                        || (tc.getSource() == source && tc.isLocked()))
                .map(testCaseChecklistPlanner::toCandidate)
                .toList();
    }

    private GenerationPlanSummary summarize(
            List<TestCaseChecklistPlanner.ChecklistItem> items,
            Map<TestCaseChecklistPlanner.ChecklistItem, ChecklistItemStatus> statuses,
            Map<TestCaseChecklistPlanner.ChecklistItem, String> reasons,
            Map<TestCaseChecklistPlanner.ChecklistItem, List<String>> coveredBy,
            int aiAskedCount, List<PlannedCase> planned
    ) {
        List<ChecklistItemResponse> responses = items.stream()
                .map(item -> new ChecklistItemResponse(item.type(), item.field(), item.description(),
                        statuses.get(item), reasons.get(item), coveredBy.getOrDefault(item, List.of())))
                .toList();
        List<String> extraCaseNames = planned.stream()
                .filter(p -> p.origin() == TestCaseGenerationOrigin.AI_EXTRA)
                .map(p -> p.generated().name())
                .toList();
        int checklistCaseCount = (int) planned.stream()
                .filter(p -> p.origin() == TestCaseGenerationOrigin.AI_CHECKLIST
                        || p.origin() == TestCaseGenerationOrigin.SYSTEM_FILLED)
                .count();
        return new GenerationPlanSummary(
                items.size(),
                count(statuses, ChecklistItemStatus.ALREADY_COVERED),
                aiAskedCount,
                count(statuses, ChecklistItemStatus.AI_SATISFIED),
                count(statuses, ChecklistItemStatus.SYSTEM_FILLED),
                count(statuses, ChecklistItemStatus.NOT_FILLABLE),
                count(statuses, ChecklistItemStatus.NOT_APPLICABLE),
                extraCaseNames.size(),
                checklistCaseCount,
                extraCaseNames,
                responses);
    }

    private int count(Map<TestCaseChecklistPlanner.ChecklistItem, ChecklistItemStatus> statuses, ChecklistItemStatus status) {
        return (int) statuses.values().stream().filter(s -> s == status).count();
    }

    private String label(TestCaseChecklistPlanner.ChecklistItem item) {
        String text = item.description();
        return text.length() <= CHECKLIST_LABEL_MAX ? text : text.substring(0, CHECKLIST_LABEL_MAX - 3) + "...";
    }

    private Prompt buildPrompt(
            Endpoint endpoint, boolean includeSecurity,
            boolean includePositive, boolean includeNegative, boolean includeBoundary,
            List<TestCaseChecklistPlanner.ChecklistItem> checklist
    ) {
        // Module 15i - prompt tách theo loại test case: khung chung (generate-test-case.st) + phần luật
        // của nhóm (generate/basic.st hoặc generate/security.st) + phần luật/checklist riêng của từng
        // loại đã tick (generate/positive|negative|boundary.st). Java chỉ CHỌN file và GHÉP - toàn bộ
        // chữ của prompt vẫn nằm trong .st. Vẫn đúng 1 lệnh gọi AI cho mỗi nhóm.
        String groupRules;
        if (includeSecurity) {
            groupRules = render(readTemplate("prompts/generate/security.st"), checklistVariables(checklist, "SECURITY_"));
        } else {
            StringBuilder sections = new StringBuilder();
            List<String> selected = new ArrayList<>();
            appendCategory(includePositive, "Positive", "prompts/generate/positive.st", "POSITIVE_", checklist, sections, selected);
            appendCategory(includeNegative, "Negative", "prompts/generate/negative.st", "NEGATIVE_", checklist, sections, selected);
            appendCategory(includeBoundary, "Boundary Value", "prompts/generate/boundary.st", "BOUNDARY_", checklist, sections, selected);
            Map<String, Object> basic = new HashMap<>();
            basic.put("includePositive", includePositive);
            basic.put("selectedGroups", String.join(", ", selected));
            basic.put("categorySections", sections.toString().stripTrailing());
            groupRules = render(readTemplate("prompts/generate/basic.st"), basic);
        }

        // Đọc rõ UTF-8 - trên Windows, charset mặc định của tiến trình JVM có thể không phải UTF-8,
        // khiến nội dung .st (tiếng Việt có dấu) bị đọc sai byte.
        String templateText;
        try {
            templateText = promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được prompt template generate-test-case.st", e);
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("method", endpoint.getMethod());
        variables.put("path", endpoint.getPath());
        variables.put("summary", endpoint.getSummary() == null ? "(không có mô tả)" : endpoint.getSummary());
        variables.put("schemaJson", endpoint.getSchema());
        variables.put("groupRules", groupRules.stripTrailing());
        return template(templateText).create(variables);
    }

    private void appendCategory(
            boolean ticked, String label, String resourcePath, String typePrefix,
            List<TestCaseChecklistPlanner.ChecklistItem> checklist, StringBuilder sections, List<String> selected
    ) {
        if (!ticked) {
            return;
        }
        selected.add(label);
        sections.append(render(readTemplate(resourcePath), checklistVariables(checklist, typePrefix)).stripTrailing())
                .append("\n\n");
    }

    /** Biến checklist cho 1 file loại: chỉ các mục thuộc đúng loại đó (theo tiền tố tên ChecklistItemType). */
    private Map<String, Object> checklistVariables(List<TestCaseChecklistPlanner.ChecklistItem> checklist, String typePrefix) {
        List<TestCaseChecklistPlanner.ChecklistItem> own = checklist.stream()
                .filter(item -> item.type().name().startsWith(typePrefix))
                .toList();
        Map<String, Object> variables = new HashMap<>();
        variables.put("hasChecklist", !own.isEmpty());
        variables.put("checklistText", checklistText(own));
        return variables;
    }

    private String readTemplate(String resourcePath) {
        try {
            return new ClassPathResource(resourcePath).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được prompt template " + resourcePath, e);
        }
    }

    private String render(String templateText, Map<String, Object> variables) {
        return template(templateText).render(variables);
    }

    private PromptTemplate template(String templateText) {
        return PromptTemplate.builder()
                .template(templateText)
                .renderer(StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build())
                .build();
    }

    /** Checklist dạng danh sách đánh số cho prompt - giá trị biến không bị StringTemplate parse, an toàn với ký tự đặc biệt. */
    private String checklistText(List<TestCaseChecklistPlanner.ChecklistItem> checklist) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < checklist.size(); i++) {
            text.append("  ").append(i + 1).append(". ").append(checklist.get(i).description());
            if (i < checklist.size() - 1) {
                text.append('\n');
            }
        }
        return text.toString();
    }

    private void validate(List<GeneratedTestCase> generated) {
        if (generated == null || generated.isEmpty()) {
            throw new AiGenerationFailedException("AI không sinh được test case nào cho endpoint này");
        }
        for (GeneratedTestCase testCase : generated) {
            if (testCase.name() == null || testCase.name().isBlank()) {
                throw new AiGenerationFailedException("AI trả về test case thiếu tên");
            }
            if (testCase.expectedStatus() == null
                    || testCase.expectedStatus() < MIN_STATUS
                    || testCase.expectedStatus() > MAX_STATUS) {
                throw new AiGenerationFailedException("AI trả về mã trạng thái HTTP không hợp lệ");
            }
            testCasePathValidator.validate(
                    testCase.resolvedPath(),
                    writeFallbacksAsJson(testCase.pathParamFallbacks()),
                    AiGenerationFailedException::new);
        }
    }

    private TestCase toEntity(
            Endpoint endpoint, GeneratedTestCase generated, TestCaseSource source,
            TestCaseGenerationOrigin origin, String checklistItem, boolean tier1Blind
    ) {
        // Module 15b (Tier 1) - đối chiếu tất định với spec NGAY LÚC map sang entity, không gọi AI
        // thêm. Dùng thẳng generated (Map có sẵn) thay vì re-parse lại entity vừa build, tránh vòng
        // JSON-serialize-rồi-deserialize không cần thiết.
        TestCaseReliabilityValidator.Result reliability = testCaseReliabilityValidator.validate(endpoint, generated, source);
        // Module 15i - mục checklist mà Tier 1 không nhìn thấy lý do lỗi (thiếu tham số query/header bắt buộc, id
        // không tồn tại): bộ chấm checklist đã đọc nội dung case chặt hơn nên tin kết luận đó, tránh gắn "Cần xem
        // lại" oan rồi tốn 1 lệnh Tier 2. Chỉ ghi đè khi Tier 1 cảnh báo - không bao giờ hạ 1 case RELIABLE.
        if (tier1Blind && reliability.status() == TestCaseReliability.NEEDS_REVIEW) {
            reliability = TestCaseReliabilityValidator.Result.reliable();
        }
        return TestCase.builder()
                .endpoint(endpoint)
                .name(generated.name())
                .description(generated.description())
                .requestHeaders(writeHeadersAsJson(generated.requestHeaders()))
                .requestBody(generated.requestBody())
                .rationale(generated.rationale())
                .reliabilityStatus(reliability.status())
                .reliabilityNotes(writeReliabilityNotesAsJson(reliability.notes()))
                .expectedStatus(generated.expectedStatus())
                .resolvedPath(generated.resolvedPath())
                .pathParamFallbacks(writeFallbacksAsJson(generated.pathParamFallbacks()))
                .source(source)
                .generationOrigin(origin)
                .checklistItem(checklistItem)
                .authOverride(generated.authOverride() == null ? TestCaseAuthOverride.DEFAULT : generated.authOverride())
                .build();
    }

    private String writeReliabilityNotesAsJson(List<String> notes) {
        if (notes == null || notes.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(notes);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeHeadersAsJson(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeFallbacksAsJson(Map<String, String> pathParamFallbacks) {
        if (pathParamFallbacks == null || pathParamFallbacks.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(pathParamFallbacks);
        } catch (Exception e) {
            return null;
        }
    }

    private String writePlanSummaryAsJson(GenerationPlanSummary planSummary) {
        try {
            return objectMapper.writeValueAsString(planSummary);
        } catch (Exception e) {
            // Báo cáo chỉ để xem lại - không đáng làm hỏng cả lượt sinh vì nó.
            log.warn("Khong serialize duoc bao cao checklist: {}", e.toString());
            return null;
        }
    }

    private String writeSnapshotAsJson(List<GeneratedTestCase> generated) {
        try {
            return objectMapper.writeValueAsString(generated);
        } catch (Exception e) {
            throw new IllegalStateException("Không serialize được snapshot lịch sử sinh test case", e);
        }
    }
}
