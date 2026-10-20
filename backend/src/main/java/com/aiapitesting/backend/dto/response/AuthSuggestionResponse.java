package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Module 17 (S4) - gợi ý đánh dấu endpoint cần xác thực khi spec KHÔNG khai báo xác thực nào. Chỉ là gợi ý (không tự áp dụng).
 *
 * @param hint          có nên hiện gợi ý không (spec không khai xác thực, đủ endpoint, có ít nhất 1 nhóm đề xuất cần xác thực)
 * @param alreadyManual số endpoint người dùng đã tự khai (không nằm trong gợi ý, không bị ghi đè)
 * @param groups        nhóm theo tiền tố đường dẫn; mỗi nhóm chỉ gồm endpoint chưa khai tay
 */
public record AuthSuggestionResponse(boolean hint, int total, int alreadyManual, int suggestedProtected, int suggestedPublic, List<Group> groups) {

    public record Group(String pathPrefix, boolean suggestedRequiresAuth, int count, List<String> samplePaths, List<UUID> endpointIds) {
    }

    public static AuthSuggestionResponse none(int total, int alreadyManual) {
        return new AuthSuggestionResponse(false, total, alreadyManual, 0, 0, List.of());
    }
}
