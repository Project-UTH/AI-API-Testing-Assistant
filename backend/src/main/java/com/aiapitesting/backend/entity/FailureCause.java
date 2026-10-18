package com.aiapitesting.backend.entity;

/**
 * Module 16 - nguyên nhân NGHI NGỜ của 1 kết quả FAILED/ERROR, chấm tất định lúc chạy (không gọi AI) từ
 * dữ liệu app tự thu được khi gọi API black-box. Chỉ là nghi ngờ kèm bằng chứng, KHÔNG khẳng định: kiểm
 * thử black-box không nhìn thấy bên trong hệ thống đích và không phân biệt được bug thật với kỳ vọng
 * test sai. Ngưỡng của từng luật là tham số thiết kế chưa calibrate (xem FailureCauseClassifier).
 */
public enum FailureCause {
    /** Lỗi cấu hình test (vd thiếu giá trị tham số) - không gọi được target. */
    TEST_SETUP("Lỗi cấu hình test", true),
    /** Hệ thống đích/mạng: timeout, không kết nối được, 502/503/504/429. */
    ENVIRONMENT("Nghi lỗi môi trường", true),
    /** Token/xác thực của project: nhận 401/403 trong khi kỳ vọng khác và dùng token mặc định. */
    AUTH_CONFIG("Nghi token/xác thực", true),
    /**
     * Mã trạng thái ĐÚNG kỳ vọng nhưng assertion về nội dung phản hồi sai - cho biết rõ lần fail đến từ nội dung
     * phản hồi chứ không phải mã trạng thái. Không thuộc nhóm "không phải do API": có thể assertion viết chưa đúng
     * (jsonPath, giá trị kỳ vọng) hoặc API trả nội dung khác mong đợi - black-box không phân biệt được.
     */
    ASSERTION_FAILED("Assertion sai", false),
    /** Dữ liệu/trạng thái hệ thống đích đổi: từng Pass rồi Fail liên tiếp, không xen kẽ. */
    DATA_STATE("Nghi dữ liệu/trạng thái đổi", false),
    /** Fail lặp lại cùng 1 kết quả nhiều lần, chưa từng Pass - có thể là bug thật hoặc kỳ vọng test sai. */
    STABLE_FAILURE("Lỗi ổn định", false),
    /** Chưa đủ dữ liệu để kết luận. */
    UNDETERMINED("Chưa đủ dữ liệu", false);

    private final String label;
    private final boolean notApiFault;

    FailureCause(String label, boolean notApiFault) {
        this.label = label;
        this.notApiFault = notApiFault;
    }

    public String label() {
        return label;
    }

    /**
     * Bằng chứng mạnh rằng fail KHÔNG phải do API đang test: loại khỏi tỷ lệ fail của Risk, không tính là "đã
     * kiểm và thấy lệch" ở Coverage, không tự mở lại Bug Report. DATA_STATE cố ý KHÔNG nằm ở đây (bằng chứng
     * yếu - tránh loại oan một fail thật).
     */
    public boolean isNotApiFault() {
        return notApiFault;
    }
}
