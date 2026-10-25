package com.aiapitesting.backend.service.execution;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 17q G4 - kiểm thử TẤT ĐỊNH (0 token) độ bền xử lý request lỗi định dạng, dùng cho mọi REST API ghi (POST/PUT/PATCH có body JSON):
 * <ul>
 *   <li>MALFORMED_JSON: body JSON cú pháp hỏng. API đúng trả 4xx (400/422); 5xx là lỗi xử lý (hay gặp: 500 thay vì 400); 2xx là nhận dữ liệu hỏng.</li>
 *   <li>WRONG_CONTENT_TYPE: body hợp lệ nhưng gửi với Content-Type text/plain. API đúng trả 415 (hoặc 400/422); 5xx hoặc 2xx là lỗi.</li>
 *   <li>WRONG_METHOD: gửi phương thức TRACE (không endpoint REST nào khai báo). API đúng trả 405 (hoặc 4xx/501 khác); 2xx nghĩa là TRACE đang bật (OWASP), 5xx là lỗi xử lý.
 *       CỐ Ý chỉ dùng TRACE chứ không thử PUT/PATCH/DELETE: TRACE chỉ phản chiếu request nên không bao giờ sửa hay xoá dữ liệu thật dù API lỗi.</li>
 * </ul>
 * Thuần tuý (không DB/mạng). Mã khác (401/403/404/409...) là "không kết luận": rơi về luật mã trạng thái thường với kỳ vọng 400/415/405, nên 401 thay
 * vì 400 vẫn là Fail có nhãn (không sửa kỳ vọng cho khớp).
 */
public final class HttpRobustnessProbe {

    public static final String KIND = "HTTP_ROBUSTNESS";
    public static final String RULE_SERVER_ERROR = "HTTP_ROBUSTNESS_SERVER_ERROR";
    public static final String RULE_ACCEPTED = "HTTP_ROBUSTNESS_ACCEPTED";

    public enum Variant {
        MALFORMED_JSON(400, "JSON cú pháp hỏng"), WRONG_CONTENT_TYPE(415, "Content-Type sai (text/plain)"),
        WRONG_METHOD(405, "phương thức TRACE không được hỗ trợ");

        private final int expectedStatus;
        private final String label;

        Variant(int expectedStatus, String label) {
            this.expectedStatus = expectedStatus;
            this.label = label;
        }

        public int expectedStatus() {
            return expectedStatus;
        }

        public String label() {
            return label;
        }
    }

    /** Phương thức dùng cho probe WRONG_METHOD (an toàn: chỉ phản chiếu request, không thay đổi dữ liệu). */
    public static final String PROBE_HTTP_METHOD = "TRACE";

    private static final Pattern VARIANT_JSON = Pattern.compile("\"variant\"\\s*:\\s*\"([A-Z_]+)\"");

    private HttpRobustnessProbe() {
    }

    public static String probeJson(Variant variant) {
        return "{\"variant\":\"" + variant.name() + "\"}";
    }

    /** null nếu không đọc được (probe hỏng thì bỏ qua, không làm hỏng lượt chạy). */
    public static Variant variantOf(String probeJson) {
        if (probeJson == null) {
            return null;
        }
        Matcher m = VARIANT_JSON.matcher(probeJson);
        if (!m.find()) {
            return null;
        }
        try {
            return Variant.valueOf(m.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Biến body hợp lệ thành JSON hỏng cú pháp (bỏ ngoặc đóng, thừa dấu phẩy); body không phải object/mảng thì dùng mẫu hỏng cố định. */
    public static String malformedBody(String validBody) {
        String body = validBody == null ? "" : validBody.trim();
        if (body.length() > 1 && (body.endsWith("}") || body.endsWith("]"))) {
            return body.substring(0, body.length() - 1) + ",";
        }
        return "{\"broken\":";
    }

    public static MassAssignmentProbe.Outcome evaluate(Variant variant, Integer status) {
        if (variant == null || status == null) {
            return new MassAssignmentProbe.Outcome(MassAssignmentProbe.Verdict.INCONCLUSIVE, List.of(), "Không có phản hồi");
        }
        // 501 Not Implemented là câu trả lời đúng của máy chủ không hỗ trợ TRACE, không phải lỗi xử lý.
        if (status >= 500 && !(variant == Variant.WRONG_METHOD && status == 501)) {
            return fail(RULE_SERVER_ERROR, SensitiveDataScanner.Severity.HIGH,
                    "API trả lỗi máy chủ " + status + " khi nhận request " + variant.label() + " (kỳ vọng " + variant.expectedStatus() + ")");
        }
        if (status >= 200 && status <= 299) {
            return fail(RULE_ACCEPTED, SensitiveDataScanner.Severity.MEDIUM,
                    "API chấp nhận (" + status + ") request " + variant.label() + " thay vì từ chối (kỳ vọng " + variant.expectedStatus() + ")"
                            + (variant == Variant.WRONG_METHOD ? "; TRACE đang bật là rủi ro bảo mật (cross-site tracing)" : ""));
        }
        boolean rejected = variant == Variant.WRONG_METHOD
                ? status == 405 || status == 501 || status == 400 || status == 403 || status == 404 || status == 415
                : status == 400 || status == 422 || status == 415;
        if (rejected) {
            return new MassAssignmentProbe.Outcome(MassAssignmentProbe.Verdict.PASS, List.of(),
                    "API từ chối request " + variant.label() + " đúng cách (" + status + ")");
        }
        return new MassAssignmentProbe.Outcome(MassAssignmentProbe.Verdict.INCONCLUSIVE, List.of(),
                "Mã " + status + ": probe chưa chạm tới việc xử lý request lỗi định dạng");
    }

    private static MassAssignmentProbe.Outcome fail(String rule, SensitiveDataScanner.Severity severity, String detail) {
        return new MassAssignmentProbe.Outcome(MassAssignmentProbe.Verdict.FAIL, List.of(), detail,
                new SensitiveDataScanner.Finding("(request)", rule, severity, detail));
    }
}
