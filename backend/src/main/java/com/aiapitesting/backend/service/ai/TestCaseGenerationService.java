package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.ai.GeneratedAssertion;
import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.request.GenerateTestCasesRequest;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAssertion;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
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
import com.aiapitesting.backend.service.TestCasePathValidator;
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

    private final ChatClient chatClient;
    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseDependencyRepository testCaseDependencyRepository;
    private final TestCaseAssertionRepository testCaseAssertionRepository;
    private final TestResultRepository testResultRepository;
    private final BugReportRepository bugReportRepository;
    private final TestCasePathValidator testCasePathValidator;
    private final TestCaseService testCaseService;
    private final TestGenerationEventRepository testGenerationEventRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("classpath:prompts/generate-test-case.st")
    private Resource promptResource = new ClassPathResource("prompts/generate-test-case.st");

    @Value("${ai.quota.daily-token-limit}")
    private long dailyTokenLimit;

    @Async
    @Transactional
    public CompletableFuture<List<TestCaseResponse>> generate(UUID projectId, UUID endpointId, GenerateTestCasesRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        Endpoint endpoint = endpointRepository.findByIdAndProject(endpointId, project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));
        ensureWithinDailyQuota(project);
        boolean includeSecurity = request != null && request.includeSecurity();
        boolean includeAssertions = request != null && request.includeAssertions();
        // request == null giữ hành vi mặc định cũ: sinh cả 3 nhóm. Khi có body, đọc đúng giá trị đã gửi.
        boolean includePositive = request == null || request.includePositive();
        boolean includeNegative = request == null || request.includeNegative();
        boolean includeBoundary = request == null || request.includeBoundary();
        if (!includePositive && !includeNegative && !includeBoundary && !includeSecurity) {
            throw new InvalidRequestException(
                    "Phải chọn ít nhất 1 loại test case để sinh (Positive/Negative/Boundary/Security)");
        }

        // Mỗi lần bấm "Sinh Test Case" khai báo lại toàn bộ tập test case AI-quản-lý cho endpoint
        // này = đúng những gì đang được tick. Nhóm có tick -> gọi AI sinh lại (xoá-và-thay). Nhóm
        // KHÔNG tick -> không gọi AI, chỉ xoá sạch case cũ (chưa khoá) còn sót từ lần sinh trước, để
        // tránh tình trạng case Security cũ (hoặc Positive/Negative/Boundary cũ) còn lưu lại dù lần
        // này người dùng chủ động bỏ tick. Case đã khoá 🔒 luôn được giữ nguyên ở cả 2 nhánh.
        List<TestCase> saved = new ArrayList<>();
        if (includePositive || includeNegative || includeBoundary) {
            saved.addAll(generateGroup(endpoint, TestCaseSource.AI_GENERATED, false, includeAssertions,
                    includePositive, includeNegative, includeBoundary));
        } else {
            deleteExistingGroup(endpoint, TestCaseSource.AI_GENERATED);
        }
        if (includeSecurity) {
            saved.addAll(generateGroup(endpoint, TestCaseSource.SECURITY, true, includeAssertions,
                    includePositive, includeNegative, includeBoundary));
        } else {
            deleteExistingGroup(endpoint, TestCaseSource.SECURITY);
        }

        return CompletableFuture.completedFuture(saved.stream().map(TestCaseResponse::from).toList());
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
     * Xoá sạch test case chưa khoá thuộc đúng 1 source (Cơ bản hoặc Security) của endpoint, không
     * gọi AI và không ghi TestGenerationEvent - dùng khi nhóm đó KHÔNG được tick trong lần sinh này
     * (dọn case cũ còn sót từ 1 lần sinh trước đó có tick), hoặc làm bước dọn dẹp trước khi
     * generateGroup() lưu batch mới. Test case đã khoá 🔒 luôn được giữ nguyên (đã lọc LockedFalse).
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
     */
    private List<TestCase> generateGroup(
            Endpoint endpoint, TestCaseSource source, boolean includeSecurity, boolean includeAssertions,
            boolean includePositive, boolean includeNegative, boolean includeBoundary
    ) {
        Prompt prompt = buildPrompt(endpoint, includeSecurity, includeAssertions,
                includePositive, includeNegative, includeBoundary);

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

        List<TestCase> saved = testCaseRepository.saveAll(generated.stream()
                .map(g -> toEntity(endpoint, g, source)).toList());

        // saveAll() giữ nguyên thứ tự input - ghép theo index với `generated` để biết assertion
        // thuộc về test case nào vừa lưu.
        List<TestCaseAssertion> assertionsToSave = new ArrayList<>();
        for (int i = 0; i < saved.size(); i++) {
            List<GeneratedAssertion> generatedAssertions = generated.get(i).assertions();
            if (generatedAssertions == null) {
                continue;
            }
            TestCase savedCase = saved.get(i);
            for (GeneratedAssertion ga : generatedAssertions) {
                if (ga.jsonPath() == null || ga.jsonPath().isBlank() || ga.operator() == null) {
                    continue;
                }
                assertionsToSave.add(TestCaseAssertion.builder()
                        .testCase(savedCase)
                        .jsonPath(ga.jsonPath())
                        .operator(ga.operator())
                        .expectedValue(ga.expectedValue())
                        .build());
            }
        }
        if (!assertionsToSave.isEmpty()) {
            testCaseAssertionRepository.saveAll(assertionsToSave);
        }

        // Snapshot lịch sử tách biệt bảng test_cases sống - lần regenerate sau sẽ xoá hẳn bộ này.
        testGenerationEventRepository.save(TestGenerationEvent.builder()
                .endpoint(endpoint)
                .testCaseCount(saved.size())
                .snapshotJson(writeSnapshotAsJson(generated))
                .promptTokens(usage == null ? null : usage.getPromptTokens())
                .completionTokens(usage == null ? null : usage.getCompletionTokens())
                .totalTokens(usage == null ? null : usage.getTotalTokens())
                .build());

        return saved;
    }

    private Prompt buildPrompt(
            Endpoint endpoint, boolean includeSecurity, boolean includeAssertions,
            boolean includePositive, boolean includeNegative, boolean includeBoundary
    ) {
        // Đọc rõ UTF-8 - trên Windows, charset mặc định của tiến trình JVM có thể không phải UTF-8,
        // khiến nội dung .st (tiếng Việt có dấu) bị đọc sai byte.
        String templateText;
        try {
            templateText = promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được prompt template generate-test-case.st", e);
        }
        PromptTemplate template = PromptTemplate.builder()
                .template(templateText)
                .renderer(StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build())
                .build();
        return template.create(Map.of(
                "method", endpoint.getMethod(),
                "path", endpoint.getPath(),
                "summary", endpoint.getSummary() == null ? "(không có mô tả)" : endpoint.getSummary(),
                "schemaJson", endpoint.getSchema(),
                "includeSecurity", includeSecurity,
                "includeAssertions", includeAssertions,
                "includePositive", includePositive,
                "includeNegative", includeNegative,
                "includeBoundary", includeBoundary));
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

    private TestCase toEntity(Endpoint endpoint, GeneratedTestCase generated, TestCaseSource source) {
        return TestCase.builder()
                .endpoint(endpoint)
                .name(generated.name())
                .description(generated.description())
                .requestHeaders(writeHeadersAsJson(generated.requestHeaders()))
                .requestBody(generated.requestBody())
                .expectedStatus(generated.expectedStatus())
                .resolvedPath(generated.resolvedPath())
                .pathParamFallbacks(writeFallbacksAsJson(generated.pathParamFallbacks()))
                .source(source)
                .authOverride(generated.authOverride() == null ? TestCaseAuthOverride.DEFAULT : generated.authOverride())
                .build();
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

    private String writeSnapshotAsJson(List<GeneratedTestCase> generated) {
        try {
            return objectMapper.writeValueAsString(generated);
        } catch (Exception e) {
            throw new IllegalStateException("Không serialize được snapshot lịch sử sinh test case", e);
        }
    }
}
