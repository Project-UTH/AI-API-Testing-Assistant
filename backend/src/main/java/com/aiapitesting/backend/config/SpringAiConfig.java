package com.aiapitesting.backend.config;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiConfig {

    /**
     * Sinh test case chỉ là tác vụ trích xuất JSON có cấu trúc theo schema cho sẵn, không cần suy
     * luận nhiều bước - tắt hẳn adaptive thinking (mặc định BẬT trên Claude Sonnet 5/Opus 5) để
     * toàn bộ max-tokens dành cho JSON output thật, tránh bị chiếm hết ngân sách token bởi phần
     * suy nghĩ nội bộ dẫn tới response rỗng (đã xác nhận qua lỗi Jackson "No content to map").
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultOptions(AnthropicChatOptions.builder().thinkingDisabled())
                .build();
    }
}
