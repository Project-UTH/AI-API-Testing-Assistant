package com.aiapitesting.backend.dto.response;

/**
 * Module 15e (Tier 3) - kết quả đối chiếu độ tin cậy sâu hơn bằng AI, người dùng tự bấm (tốn thêm
 * token thật). KHÔNG ghi đè reliabilityStatus/reliabilityAiConfirmed đã có từ Tier 1/2 - đây là kết
 * quả đối chiếu riêng, tạm thời, chỉ hiển thị khi người dùng chủ động yêu cầu.
 */
public record ConfidenceCheckResponse(
        ConfidenceAgreementLevel agreementLevel,
        boolean expectedStatusMatches,
        boolean requestBodyShapeMatches,
        Integer regeneratedExpectedStatus,
        String regeneratedRequestBody,
        String note
) {
}
