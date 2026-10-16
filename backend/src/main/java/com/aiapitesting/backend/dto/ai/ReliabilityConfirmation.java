package com.aiapitesting.backend.dto.ai;

/**
 * Structured-output type từ AI khi xác nhận lại case bị Tier 1 (rule-based, tất định) gắn cờ
 * NEEDS_REVIEW (Module 15d, Tier 2). {@code index} phải khớp đúng thứ tự case đã gửi trong prompt
 * (0-based) để map lại đúng {@code TestCase} - xem {@code TestCaseReliabilityConfirmationService}.
 *
 * @param index     số thứ tự case trong danh sách đã gửi, khớp đúng input
 * @param confirmed true = đây là lỗi thật của AI sinh test case, cần xem lại; false = đúng chủ đích
 *                  (vd case Security cố ý gửi giá trị sai để test injection), rule Tier 1 báo nhầm
 * @param note      giải thích ngắn tiếng Việt cho kết luận trên
 */
public record ReliabilityConfirmation(int index, boolean confirmed, String note) {
}
