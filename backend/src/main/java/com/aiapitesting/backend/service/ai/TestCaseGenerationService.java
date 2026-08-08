package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.exception.AiGenerationFailedException;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.service.ProjectService;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("classpath:prompts/generate-test-case.st")
    private Resource promptResource = new ClassPathResource("prompts/generate-test-case.st");

    @Async
    @Transactional
    public CompletableFuture<List<TestCaseResponse>> generate(UUID projectId, UUID endpointId) {
        Project project = projectService.getOwnedProject(projectId);
        Endpoint endpoint = endpointRepository.findByIdAndProject(endpointId, project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));

        Prompt prompt = buildPrompt(endpoint);

        List<GeneratedTestCase> generated;
        try {
            generated = chatClient.prompt(prompt).call()
                    .entity(new ParameterizedTypeReference<List<GeneratedTestCase>>() {
                    });
        } catch (Exception e) {
            // Log lỗi gốc từ AI provider để chẩn đoán (rate limit, sai key, timeout...) - không log
            // prompt/nội dung nhạy cảm, chỉ log loại lỗi + message do provider trả về.
            log.warn("Goi AI that bai khi sinh test case cho endpoint {}: {}", endpointId, e.toString());
            throw new AiGenerationFailedException("Không thể sinh test case từ AI, vui lòng thử lại");
        }
        validate(generated);

        // Chỉ xoá-và-thay test case do AI sinh trước đó - giữ nguyên test case người dùng tự thêm tay
        testCaseRepository.deleteAllByEndpointAndSource(endpoint, TestCaseSource.AI_GENERATED);
        List<TestCase> saved = testCaseRepository.saveAll(generated.stream()
                .map(g -> toEntity(endpoint, g)).toList());
        return CompletableFuture.completedFuture(saved.stream().map(TestCaseResponse::from).toList());
    }

    private Prompt buildPrompt(Endpoint endpoint) {
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
                "schemaJson", endpoint.getSchema()));
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
        }
    }

    private TestCase toEntity(Endpoint endpoint, GeneratedTestCase generated) {
        return TestCase.builder()
                .endpoint(endpoint)
                .name(generated.name())
                .description(generated.description())
                .requestHeaders(writeHeadersAsJson(generated.requestHeaders()))
                .requestBody(generated.requestBody())
                .expectedStatus(generated.expectedStatus())
                .source(TestCaseSource.AI_GENERATED)
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

    record GeneratedTestCase(
            String name,
            String description,
            Map<String, String> requestHeaders,
            String requestBody,
            Integer expectedStatus
    ) {
    }
}
