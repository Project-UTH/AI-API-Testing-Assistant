package com.aiapitesting.backend.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Kết quả 'AI sinh test gốc + chạy thử xác nhận'.
 *
 * @param status      CONFIRMED (gốc đã Pass và được khoá), NEEDS_HUMAN (cần người can thiệp, xem reason), ALREADY_HAS_BASELINE (đã có gốc đã Pass)
 * @param reason      mã lý do khi NEEDS_HUMAN: RESOURCE_NOT_FOUND, AUTH, CREDENTIALS, SERVER_ERROR, MISSING_PARAM_VALUE, REPAIR_EXHAUSTED, TOKEN_BUDGET, EXECUTION_TIMEOUT
 * @param message     giải thích cho người dùng
 * @param lastResponseBodyMasked nội dung phản hồi lần chạy cuối, đã che dữ liệu nhạy cảm và cắt ngắn
 */
public record BaselineResponse(
        String status, String reason, String message, UUID baselineTestCaseId, List<Round> rounds, int tokensUsed, int filledCount,
        Integer lastResponseStatus, String lastResponseBodyMasked
) {
    public record Round(int round, Integer httpStatus, boolean passed, int tokens, String note) {
    }
}
