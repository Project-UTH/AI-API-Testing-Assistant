package com.aiapitesting.backend.dto.response;

/**
 * 17q G3 - độ bao phủ LỚP NGHIỆP VỤ của 1 endpoint, tách hẳn lớp spec (không gộp thành 1 số): "x/y luật đã duyệt có test". Mẫu số là luật
 * ĐÃ DUYỆT (người chịu trách nhiệm) - nên chỉ được nói "đủ so với luật đã xác nhận", không phải "đủ nghiệp vụ". Null khi endpoint chưa có luật nào.
 *
 * @param approved         số luật đã duyệt
 * @param approvedWithTests số luật đã duyệt đã có ít nhất 1 test sinh ra
 * @param proposed         số luật đang chờ duyệt (PROPOSED + CONFLICT) - badge "Chờ duyệt N"
 * @param assumed          trong số luật đã duyệt, số luật GIẢ ĐỊNH (AI suy đoán, không có trích dẫn hợp lệ)
 */
public record RuleCoverage(int approved, int approvedWithTests, int proposed, int assumed) {
}
