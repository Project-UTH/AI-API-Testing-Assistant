package com.aiapitesting.backend.dto.response;

import java.util.Map;

/**
 * Kết quả cuối của job trích luật (type RULES). Luật vẫn ở PROPOSED - job không tự duyệt. stoppedReason != null khi dừng sớm
 * (BUDGET_STOPPED, DAILY_QUOTA) nhưng phần đã làm được giữ lại.
 */
public record RulesJobResult(
        int endpointCount, int lotsPlanned, int lotsDone, int estimatedTokens, int fromDocument, int fromInstruction, int inferred,
        int rejectedAsDuplicateOfSpec, int conflicts, int skippedExisting, int skippedOverCap, int skippedInferred, Map<String, Integer> dropped,
        String stoppedReason, int tokenBudget
) {
}
