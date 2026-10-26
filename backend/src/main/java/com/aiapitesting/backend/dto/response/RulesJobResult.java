package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.Map;

/**
 * Kết quả cuối của pha trích checklist nghiệp vụ. Mục vẫn ở PROPOSED - job không tự duyệt. stoppedReason != null khi dừng sớm
 * (BUDGET_STOPPED, DAILY_QUOTA) nhưng phần đã làm được giữ lại. suggestStoppedReason: lý do dừng riêng của làn "AI gợi ý thêm".
 * questions: chỗ tài liệu còn thiếu mà AI nêu thành câu hỏi (không đoán); suggested: số mục làn gợi ý đã lưu (đã tính trong inferred).
 */
public record RulesJobResult(
        int endpointCount, int lotsPlanned, int lotsDone, int estimatedTokens, int fromDocument, int fromInstruction, int inferred,
        int rejectedAsDuplicateOfSpec, int conflicts, int skippedExisting, int skippedOverCap, int skippedInferred, Map<String, Integer> dropped,
        String stoppedReason, int tokenBudget, List<String> questions, int suggested, String suggestStoppedReason
) {
}
