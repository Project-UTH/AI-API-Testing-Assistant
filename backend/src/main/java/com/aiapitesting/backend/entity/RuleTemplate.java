package com.aiapitesting.backend.entity;

/**
 * 17q G3 - mẫu luật nghiệp vụ, theo danh mục docs/eval/rule-template-catalog.md (T1-T9 đợt đầu; ONLY_ONE/ZERO_OR_ONE là T4).
 * Mỗi mẫu có bộ sinh test TẤT ĐỊNH (RuleTestGenerator) - AI (G3.3) chỉ được đề xuất luật thuộc các mẫu này, không tự viết test.
 * Lưu VARCHAR (không MySQL ENUM) để thêm mẫu mới về sau.
 */
public enum RuleTemplate {
    /** T1 - so sánh 2 field (a op b), op: LT/LTE/EQ/GTE/GT. */
    COMPARE_FIELDS,
    /** T2 - có field a (tuỳ chọn: bằng giá trị cụ thể) thì phải có các field then. */
    REQUIRES,
    /** T3 - nhóm field phải có đủ hoặc không có field nào. */
    ALL_OR_NONE,
    /** T4 - đúng 1 field trong nhóm. */
    ONLY_ONE,
    /** T4 - tối đa 1 field trong nhóm. */
    ZERO_OR_ONE,
    /** T5 - ít nhất 1 field trong nhóm. */
    OR,
    /** T6 - ngày/giờ so với bây giờ + offset (không ở quá khứ = GTE 0 ngày). */
    DATE_RELATIVE,
    /** T7 - khoảng giá trị nghiệp vụ mà spec không khai. */
    VALUE_RANGE,
    /** T8 - hàm tổng hợp trên mảng (SUM/COUNT/DISTINCT) so với hằng. */
    ARRAY_AGGREGATE,
    /** T9 - tạo trùng theo khoá duy nhất bị từ chối (gửi lại request 2 lần). */
    UNIQUE
}
