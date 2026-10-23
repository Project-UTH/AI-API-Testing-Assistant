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
     * Cùng phiên bản test đổi Pass/Fail xen kẽ từ 2 lần trở lên trong >= 5 lần chạy: kết quả không tất định nên 1 lần
     * Fail này không phải bằng chứng chắc chắn. KHÔNG thuộc nhóm "không phải do API" (black-box không biết nguyên nhân ở
     * test hay API đích) nhưng cũng không được tự mở lại/đóng Bug Report dựa trên nó.
     */
    FLAKY("Không ổn định", false),
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
    /**
     * 404 khi test dùng GIÁ TRỊ DỰ PHÒNG cho tham số đường dẫn ({id}...) và chưa từng Pass: nhiều khả năng id đó không tồn
     * tại trên hệ thống đích nên test chưa chạm tới logic cần kiểm (Negative/Boundary chưa tới bước validate). Thuộc nhóm
     * "không phải do API": không tính là đã kiểm thấy lệch ở Coverage, không tự tạo bug.
     */
    RESOURCE_NOT_FOUND("Có thể id không tồn tại", true),
    /**
     * Mã trạng thái thực tế lệch kỳ vọng nhưng CÙNG NHÓM nghĩa (401/403 cùng là từ chối xác thực, 400/422 cùng là từ chối
     * input, 200/201/204 cùng là thành công): API hành xử nhất quán, nhiều khả năng kỳ vọng của test chưa khớp cách API
     * chọn mã. Chỉ là nhãn gợi ý, kết quả vẫn FAILED. KHÔNG BAO GIỜ gắn khi API trả 5xx, khi test Security bỏ/sai token mà
     * API trả 2xx (bỏ qua xác thực), hoặc test kỳ vọng lỗi mà API chấp nhận (4xx → 2xx). Không tự tạo bug.
     */
    EXPECTATION_MISMATCH("Kỳ vọng có thể quá chặt", false),
    /** Chưa đủ dữ liệu để kết luận. */
    UNDETERMINED("Chưa đủ dữ liệu", false),
    /**
     * Module 17 (1.5) - API trả 409 (xung đột trạng thái: trùng dữ liệu, tài nguyên đã tồn tại, mỗi tài khoản chỉ 1...) trong khi
     * kỳ vọng mã khác. Là hành vi có chủ đích của API, không phải lỗi môi trường - nhưng test chưa chạm tới logic cần kiểm vì
     * trạng thái dữ liệu hiện tại chặn lại. KHÔNG thuộc nhóm "không phải do API" (có thể là lỗi thật), không tự tạo bug lặp.
     * Thêm vào CUỐI enum (cột lưu là VARCHAR, không đổi tên/thứ tự các hằng cũ).
     */
    STATE_CONFLICT("Xung đột trạng thái dữ liệu", false),
    /**
     * Module 17 (17a) - kết quả phân quyền của test dùng profile vai trò LỆCH ma trận: vai trò khai là bị cấm nhưng gọi được (2xx -
     * thiếu kiểm quyền/leo quyền) hoặc vai trò khai là được phép nhưng bị 403. Đây là phát hiện thật (hoặc ma trận khai sai), KHÔNG
     * phải lỗi môi trường - không nằm trong nhóm bị loại khỏi Bug Report.
     */
    AUTHZ_VIOLATION("Sai lệch phân quyền", false),
    /**
     * Module 17 (S2) - test kỳ vọng thành công (2xx) nhưng API trả 400/422 và phản hồi NÓI RÕ dữ liệu đầu vào không hợp lệ (ngày quá
     * khứ, mã không có trong danh mục...): nhiều khả năng DỮ LIỆU TEST sai/cũ chứ không phải bug API. Không thuộc nhóm "không phải do
     * API" (black-box không loại trừ được việc API validate sai) nhưng không tự tạo/đóng Bug Report dựa trên nó. Thêm vào CUỐI enum.
     */
    TEST_DATA_SUSPECT("Nghi dữ liệu test không hợp lệ", false),
    /**
     * Module 17 (17b-2) - test mass-assignment: API NHẬN field đặc quyền không khai báo (phản chiếu lại giá trị đánh dấu) hoặc lỗi 5xx khi
     * nhận chúng. Là PHÁT HIỆN bảo mật thật (không phải lỗi môi trường/dữ liệu test) nên không bị loại khỏi Bug Report. Thêm vào CUỐI enum.
     */
    MASS_ASSIGNMENT("Nhận field không khai báo (mass assignment)", false),
    /**
     * Test kỳ vọng thành công nhưng API trả 403 (đã xác thực, KHÔNG đủ quyền) trong khi chính token mặc định đó được chấp nhận ở test khác
     * cùng lượt: nhiều khả năng token/vai trò mặc định thiếu quyền cho endpoint này (vd gọi endpoint của vai trò khác) hơn là lỗi API. Không
     * thuộc nhóm "không phải do API" (có thể API chặn nhầm) nhưng không tự tạo/đóng Bug Report. Thêm vào CUỐI enum.
     */
    TOKEN_FORBIDDEN("Nghi token thiếu quyền cho endpoint này", false),
    /**
     * API trả 401 (chưa xác thực) cho request có token mặc định ĐÃ được API chấp nhận ở test khác cùng lượt, trong khi test kỳ vọng 400/422 (body
     * sai kiểu/enum/định dạng...). Dấu hiệu API xử lý lỗi đầu vào sai mã (vd lỗi đọc JSON bị chuyển sang luồng xác thực) - là PHÁT HIỆN thật
     * của API đích, KHÔNG bị loại khỏi Bug Report. Black-box không chứng minh tuyệt đối (không có request đối chứng riêng). Thêm vào CUỐI enum.
     */
    API_WRONG_STATUS("API trả sai mã lỗi (401 thay vì 400)", false),
    /**
     * 17q G3 - test sinh từ luật nghiệp vụ NGƯỜI DÙNG ĐÃ DUYỆT, kỳ vọng "bị từ chối" nhưng API trả 2xx (nhận dữ liệu vi phạm luật). Oracle là luật
     * do người chịu trách nhiệm nên là PHÁT HIỆN thật của API đích (trừ khi luật sai - người duyệt tự xét), KHÔNG bị loại khỏi Bug Report.
     * Thêm vào CUỐI enum.
     */
    RULE_VIOLATION_ACCEPTED("API nhận dữ liệu vi phạm luật nghiệp vụ", false);

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
     * Bằng chứng mạnh rằng fail KHÔNG phải do API đang test: không tính là "đã
     * kiểm và thấy lệch" ở Coverage, không tự mở lại Bug Report. DATA_STATE cố ý KHÔNG nằm ở đây (bằng chứng
     * yếu - tránh loại oan một fail thật).
     */
    public boolean isNotApiFault() {
        return notApiFault;
    }

    /**
     * Kết quả này không đủ tin cậy để tự động mở lại/đóng Bug Report: fail không phải do API (môi trường/token/cấu
     * hình test) hoặc test không ổn định (lần Fail này có thể chỉ là 1 lần "xui").
     */
    public boolean suppressesBugAutomation() {
        return notApiFault || this == FLAKY || this == EXPECTATION_MISMATCH || this == STATE_CONFLICT
                || this == TEST_DATA_SUSPECT || this == TOKEN_FORBIDDEN;
    }
}
