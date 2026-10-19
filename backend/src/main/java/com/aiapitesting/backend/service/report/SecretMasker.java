package com.aiapitesting.backend.service.report;

import java.util.regex.Pattern;

/**
 * Module 17 (17d) - che thông tin nhạy cảm trước khi đưa bất kỳ đoạn văn bản nào vào file báo cáo. Bằng chứng nguyên nhân Fail và
 * thông báo lỗi có thể trích 1 phần request/response (vd header Authorization, JWT, mật khẩu trong body) - báo cáo thường được đẩy
 * lên CI/Artifact nên KHÔNG được để lộ. Che theo mẫu (không đảm bảo tuyệt đối) + luôn cắt độ dài.
 */
public final class SecretMasker {

    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(\"?(?:password|passwd|pwd|secret|token|access[_-]?token|refresh[_-]?token|api[_-]?key|x-api-key|authorization)\"?)"
                    + "\\s*[:=]\\s*\"?[^\\s,\"'}&]+");
    /** Ký tự điều khiển (trừ xuống dòng/tab) - không hợp lệ trong XML 1.0 và gây rối khi hiển thị. */
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private SecretMasker() {
    }

    /** Che bí mật, bỏ ký tự điều khiển, cắt tối đa {@code maxLength} ký tự (thêm "…"). null → null. */
    public static String mask(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String result = CONTROL.matcher(text).replaceAll("");
        result = BEARER.matcher(result).replaceAll("Bearer ***");
        result = JWT.matcher(result).replaceAll("***");
        result = KEY_VALUE.matcher(result).replaceAll("$1: ***");
        if (result.length() > maxLength) {
            result = result.substring(0, Math.max(0, maxLength - 1)) + "…";
        }
        return result;
    }
}
