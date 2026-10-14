package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.ai.RegeneratedScenario;
import com.aiapitesting.backend.dto.response.ConfidenceAgreementLevel;
import com.aiapitesting.backend.dto.response.ConfidenceCheckResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.exception.AiGenerationFailedException;
import com.aiapitesting.backend.exception.AiQuotaExceededException;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.TestCaseNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.service.ProjectService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Module 15e (Tier 3) - đối chiếu độ tin cậy SÂU HƠN bằng AI cho ĐÚNG 1 test case cụ thể, người dùng
 * TỰ BẤM (không mặc định, không tự động chạy). Khác Tier 1 (tất định)/Tier 2 (tự động, có điều
 * kiện): đây là chi phí người dùng TỰ QUYẾT ĐỊNH chi, nên giữ thủ công hợp lý - không ghi đè
 * reliabilityStatus/reliabilityAiConfirmed đã có, chỉ trả kết quả đối chiếu riêng.
 *
 * <p>Quyết định khi code: KHÔNG ghi {@link com.aiapitesting.backend.entity.TestGenerationEvent} cho
 * hành động này (khác generate()/Tier 2) - tạo 1 dòng sự kiện mới với snapshot không phải danh sách
 * test case thật sẽ gây rủi ro vỡ {@code HistoryFeedService.parseSnapshot()} (luôn đọc snapshotJson
 * như List&lt;GeneratedTestCase&gt;), đúng bài học đã rút ra ở Module 15d. Token vẫn được TÍNH vào
 * quota ngay lúc kiểm tra (đọc usage hiện tại trước khi gọi), chỉ không TÍCH LUỸ lại cho lần sau -
 * chấp nhận được vì đây là hành động thủ công, tần suất thấp, không phải chi phí hệ thống tự phát
 * sinh.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TestCaseConfidenceCheckService {

    private final ChatClient chatClient;
    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestGenerationEventRepository testGenerationEventRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("classpath:prompts/confidence-check.st")
    private Resource promptResource = new ClassPathResource("prompts/confidence-check.st");

    @Value("${ai.quota.daily-token-limit}")
    private long dailyTokenLimit;

    @Async
    @Transactional
    public CompletableFuture<ConfidenceCheckResponse> check(UUID projectId, UUID endpointId, UUID testCaseId) {
        Project project = projectService.getOwnedProject(projectId);
        Endpoint endpoint = endpointRepository.findByIdAndProject(endpointId, project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));
        TestCase testCase = testCaseRepository.findByIdAndEndpoint(testCaseId, endpoint)
                .orElseThrow(() -> new TestCaseNotFoundException("Không tìm thấy test case với id đã cho"));

        ensureWithinDailyQuota(project);

        Prompt prompt = buildPrompt(endpoint, testCase);
        RegeneratedScenario regenerated;
        try {
            ResponseEntity<ChatResponse, RegeneratedScenario> response = chatClient.prompt(prompt).call()
                    .responseEntity(RegeneratedScenario.class);
            regenerated = response.entity();
        } catch (Exception e) {
            log.warn("Goi AI doi chieu do tin cay sau hon that bai cho test case {}: {}", testCaseId, e.toString());
            throw new AiGenerationFailedException("Không thể đối chiếu độ tin cậy bằng AI, vui lòng thử lại");
        }
        if (regenerated == null) {
            throw new AiGenerationFailedException("AI không trả về kết quả đối chiếu hợp lệ");
        }

        return CompletableFuture.completedFuture(buildResponse(testCase, regenerated));
    }

    private ConfidenceCheckResponse buildResponse(TestCase testCase, RegeneratedScenario regenerated) {
        boolean expectedStatusMatches = Objects.equals(regenerated.expectedStatus(), testCase.getExpectedStatus());
        boolean requestBodyShapeMatches = sameFieldShape(testCase.getRequestBody(), regenerated.requestBody());

        ConfidenceAgreementLevel level;
        if (expectedStatusMatches && requestBodyShapeMatches) {
            level = ConfidenceAgreementLevel.HIGH;
        } else if (expectedStatusMatches || requestBodyShapeMatches) {
            level = ConfidenceAgreementLevel.PARTIAL;
        } else {
            level = ConfidenceAgreementLevel.LOW;
        }

        return new ConfidenceCheckResponse(
                level, expectedStatusMatches, requestBodyShapeMatches,
                regenerated.expectedStatus(), regenerated.requestBody(), regenerated.note());
    }

    /** So khớp TẬP FIELD (không so giá trị - 2 giá trị hợp lệ khác nhau vẫn là "đồng thuận về shape"). */
    private boolean sameFieldShape(String savedJson, String regeneratedJson) {
        Set<String> savedFields = extractFieldNames(savedJson);
        Set<String> regeneratedFields = extractFieldNames(regeneratedJson);
        return savedFields.equals(regeneratedFields);
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractFieldNames(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return parsed instanceof Map ? ((Map<String, Object>) parsed).keySet() : Set.of();
        } catch (Exception e) {
            return Set.of();
        }
    }

    private Prompt buildPrompt(Endpoint endpoint, TestCase testCase) {
        String templateText;
        try {
            templateText = promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được prompt template confidence-check.st", e);
        }
        PromptTemplate template = PromptTemplate.builder()
                .template(templateText)
                .renderer(StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build())
                .build();
        return template.create(Map.of(
                "schemaJson", endpoint.getSchema() == null ? "{}" : endpoint.getSchema(),
                "testCaseName", testCase.getName(),
                "testCaseDescription", testCase.getDescription() == null ? "(không có mô tả)" : testCase.getDescription()));
    }

    /**
     * Cùng logic ensureWithinDailyQuota() ở TestCaseGenerationService - giữ bản riêng ở đây (không
     * extract dùng chung) để không phải tái cấu trúc 1 service đã có nhiều test ổn định cho 1 tính
     * năng Tier 3 phụ, ít dùng.
     */
    private void ensureWithinDailyQuota(Project project) {
        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant startOfTomorrow = startOfToday.plus(1, ChronoUnit.DAYS);
        long usedToday = testGenerationEventRepository.sumTotalTokensByOwnerAndCreatedAtBetween(
                project.getOwner(), startOfToday, startOfTomorrow);
        Integer override = project.getOwner().getAiDailyTokenLimitOverride();
        long effectiveLimit = override != null ? override : dailyTokenLimit;
        if (usedToday >= effectiveLimit) {
            throw new AiQuotaExceededException(
                    "Đã đạt giới hạn " + effectiveLimit + " token AI hôm nay, vui lòng thử lại vào ngày mai");
        }
    }
}
