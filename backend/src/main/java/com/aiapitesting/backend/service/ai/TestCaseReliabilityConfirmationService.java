package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.ai.ReliabilityConfirmation;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.TestCase;
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
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Module 15d (Tier 2) - AI xác nhận lại case bị Tier 1 ({@code TestCaseReliabilityValidator}, rule
 * tất định) gắn cờ NEEDS_REVIEW, phân biệt lỗi AI thật khỏi false positive (vd case Security cố ý
 * gửi giá trị sai để test injection). CHỈ gọi khi có ≥1 case bị gắn cờ trong batch, gộp TẤT CẢ vào
 * ĐÚNG 1 lệnh gọi AI bổ sung - chi phí tỉ lệ với số lượng vấn đề thật cần xác minh, không phải hằng
 * số. Lỗi gọi AI ở đây KHÔNG được chặn việc lưu test case - Tier 1 đã có kết quả hợp lệ sẵn, Tier 2
 * chỉ bổ sung thêm thông tin, bỏ qua an toàn khi thất bại.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TestCaseReliabilityConfirmationService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("classpath:prompts/confirm-test-case-reliability.st")
    private Resource promptResource = new ClassPathResource("prompts/confirm-test-case-reliability.st");

    private record FlaggedCaseInput(
            int index, String name, String description, String requestBody,
            Integer expectedStatus, List<String> violations
    ) {
    }

    /**
     * @param flaggedCases case đã bị Tier 1 gắn cờ NEEDS_REVIEW (chưa lưu DB) - được SỬA TRỰC TIẾP
     *                     (set reliabilityAiConfirmed/reliabilityAiNote) nếu AI trả lời thành công.
     * @return usage token thật của lệnh gọi này, null nếu bị bỏ qua/lỗi (không tính gì vào quota).
     */
    public Usage confirm(Endpoint endpoint, List<TestCase> flaggedCases) {
        if (flaggedCases.isEmpty()) {
            return null;
        }
        Prompt prompt;
        try {
            prompt = buildPrompt(endpoint, flaggedCases);
        } catch (Exception e) {
            log.warn("Khong dung duoc prompt xac nhan do tin cay cho endpoint {}: {}", endpoint.getId(), e.toString());
            return null;
        }

        List<ReliabilityConfirmation> confirmations;
        Usage usage;
        try {
            ResponseEntity<ChatResponse, List<ReliabilityConfirmation>> response = chatClient.prompt(prompt).call()
                    .responseEntity(new ParameterizedTypeReference<List<ReliabilityConfirmation>>() {
                    });
            confirmations = response.entity();
            usage = response.response().getMetadata().getUsage();
        } catch (Exception e) {
            // KHÔNG throw - Tier 1 đã lưu kết quả hợp lệ, Tier 2 thất bại chỉ bỏ qua phần bổ sung.
            log.warn("Goi AI xac nhan do tin cay that bai cho endpoint {}: {}", endpoint.getId(), e.toString());
            return null;
        }

        applyConfirmations(flaggedCases, confirmations);
        return usage;
    }

    private void applyConfirmations(List<TestCase> flaggedCases, List<ReliabilityConfirmation> confirmations) {
        if (confirmations == null) {
            return;
        }
        for (ReliabilityConfirmation c : confirmations) {
            if (c.index() < 0 || c.index() >= flaggedCases.size()) {
                continue; // index AI trả về không hợp lệ - bỏ qua đúng phần tử đó, không đoán mò.
            }
            TestCase testCase = flaggedCases.get(c.index());
            testCase.setReliabilityAiConfirmed(c.confirmed());
            testCase.setReliabilityAiNote(c.note());
        }
        // Case nào AI không trả lời (thiếu index trong response) giữ nguyên
        // reliabilityAiConfirmed=null - không suy diễn, hiển thị lại đúng kết quả Tier 1 như cũ.
    }

    private Prompt buildPrompt(Endpoint endpoint, List<TestCase> flaggedCases) throws IOException {
        String templateText = promptResource.getContentAsString(StandardCharsets.UTF_8);
        PromptTemplate template = PromptTemplate.builder()
                .template(templateText)
                .renderer(StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build())
                .build();

        List<FlaggedCaseInput> inputs = new java.util.ArrayList<>();
        for (int i = 0; i < flaggedCases.size(); i++) {
            TestCase tc = flaggedCases.get(i);
            inputs.add(new FlaggedCaseInput(
                    i, tc.getName(), tc.getDescription(), tc.getRequestBody(),
                    tc.getExpectedStatus(), parseViolations(tc.getReliabilityNotes())));
        }

        String flaggedCasesJson;
        try {
            flaggedCasesJson = objectMapper.writeValueAsString(inputs);
        } catch (Exception e) {
            throw new IOException("Khong serialize duoc danh sach case bi gan co", e);
        }

        return template.create(Map.of(
                "schemaJson", endpoint.getSchema() == null ? "{}" : endpoint.getSchema(),
                "flaggedCasesJson", flaggedCasesJson,
                "caseCount", flaggedCases.size()));
    }

    @SuppressWarnings("unchecked")
    private List<String> parseViolations(String reliabilityNotesJson) {
        if (reliabilityNotesJson == null || reliabilityNotesJson.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = objectMapper.readValue(reliabilityNotesJson, Object.class);
            return parsed instanceof List ? (List<String>) parsed : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}
