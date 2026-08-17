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
import com.aiapitesting.backend.exception.EndpointNotFoundException;
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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private final TestCasePathValidator testCasePathValidator;
    private final TestCaseService testCaseService;
    private final TestGenerationEventRepository testGenerationEventRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("classpath:prompts/generate-test-case.st")
    private Resource promptResource = new ClassPathResource("prompts/generate-test-case.st");

    @Async
    @Transactional
    public CompletableFuture<List<TestCaseResponse>> generate(UUID projectId, UUID endpointId, GenerateTestCasesRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        Endpoint endpoint = endpointRepository.findByIdAndProject(endpointId, project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));
        boolean includeSecurity = request != null && request.includeSecurity();
        boolean includeAssertions = request != null && request.includeAssertions();

        // Luôn sinh lại nhóm Cơ bản (Positive/Negative/Boundary) - hành vi mặc định không đổi.
        // Nhóm Security (nếu bật) là 1 lần gọi AI hoàn toàn riêng, xoá/lưu theo đúng source=SECURITY
        // để không đụng tới nhóm Cơ bản và ngược lại (đúng yêu cầu roadmap Module 9a). includeAssertions
        // áp dụng cho CẢ 2 lần gọi (nếu bật) - không phải 1 nhóm riêng, chỉ là AI có được yêu cầu tự
        // đề xuất thêm assertion cho mỗi test case đang sinh hay không (đúng chốt trong plan Module 9b).
        List<TestCase> saved = new ArrayList<>(
                generateGroup(endpoint, TestCaseSource.AI_GENERATED, false, includeAssertions));
        if (includeSecurity) {
            saved.addAll(generateGroup(endpoint, TestCaseSource.SECURITY, true, includeAssertions));
        }

        return CompletableFuture.completedFuture(saved.stream().map(TestCaseResponse::from).toList());
    }

    /**
     * Sinh + lưu 1 nhóm test case (Cơ bản hoặc Security) - xoá-và-thay đúng phạm vi theo source,
     * không đụng nhóm khác. Mỗi nhóm là 1 lần gọi AI riêng và 1 dòng lịch sử (TestGenerationEvent)
     * riêng vì đúng bản chất đã xảy ra 2 lần gọi AI thật khi cả 2 nhóm cùng được yêu cầu.
     */
    private List<TestCase> generateGroup(
            Endpoint endpoint, TestCaseSource source, boolean includeSecurity, boolean includeAssertions
    ) {
        Prompt prompt = buildPrompt(endpoint, includeSecurity, includeAssertions);

        List<GeneratedTestCase> generated;
        try {
            generated = chatClient.prompt(prompt).call()
                    .entity(new ParameterizedTypeReference<List<GeneratedTestCase>>() {
                    });
        } catch (Exception e) {
            // Log lỗi gốc từ AI provider để chẩn đoán (rate limit, sai key, timeout...) - không log
            // prompt/nội dung nhạy cảm, chỉ log loại lỗi + message do provider trả về.
            log.warn("Goi AI that bai khi sinh test case cho endpoint {}: {}", endpoint.getId(), e.toString());
            throw new AiGenerationFailedException("Không thể sinh test case từ AI, vui lòng thử lại");
        }
        validate(generated);

        // Chặn regenerate nếu còn test case khác (kể cả ở endpoint khác) đang phụ thuộc dữ liệu từ
        // 1 trong các test case cùng source sắp bị xoá (Test Data Chaining, Module 7).
        List<TestCase> existing = testCaseRepository.findAllByEndpointAndSource(endpoint, source);
        testCaseService.ensureNoDependents(existing);

        // Dọn TestResult + TestCaseDependency + TestCaseAssertion (phía consumer - chính các test
        // case này có thể tự phụ thuộc nguồn khác) trước khi xoá, tránh lỗi khoá ngoại 1451 (cùng
        // loại đã fix ở TestCaseService.delete()).
        testResultRepository.deleteAllByTestCaseIn(existing);
        testCaseDependencyRepository.deleteAllByTestCaseIn(existing);
        testCaseAssertionRepository.deleteAllByTestCaseIn(existing);
        testCaseRepository.deleteAllByEndpointAndSource(endpoint, source);
        List<TestCase> saved = testCaseRepository.saveAll(generated.stream()
                .map(g -> toEntity(endpoint, g, source)).toList());

        // saveAll() giữ nguyên thứ tự input -> ghép lại đúng theo index với `generated` để biết
        // assertion nào (nếu AI có sinh) thuộc về test case nào vừa lưu.
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

        // Lưu snapshot lịch sử (Module 8) - đúng nội dung AI sinh ra TẠI THỜI ĐIỂM NÀY, tách biệt
        // với bảng test_cases sống vì lần regenerate sau sẽ xoá hẳn bộ này.
        testGenerationEventRepository.save(TestGenerationEvent.builder()
                .endpoint(endpoint)
                .testCaseCount(saved.size())
                .snapshotJson(writeSnapshotAsJson(generated))
                .build());

        return saved;
    }

    private Prompt buildPrompt(Endpoint endpoint, boolean includeSecurity, boolean includeAssertions) {
        // Đọc rõ UTF-8 thay vì để Resource tự suy ra charset - trên Windows, charset mặc định của
        // tiến trình JVM (sun.jnu.encoding) có thể không phải UTF-8 dù JVM 18+ đã set file.encoding=UTF-8,
        // khiến nội dung .st (tiếng Việt có dấu) bị đọc sai byte, gửi hướng dẫn lỗi font cho AI.
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
                "includeAssertions", includeAssertions));
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
