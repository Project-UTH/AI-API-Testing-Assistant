package com.aiapitesting.backend.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Module 17 (S2) - nhận biết response lỗi 400/422 NÓI RÕ dữ liệu đầu vào không hợp lệ (ngày quá khứ, giá trị không có trong danh mục,
 * validation...). Tất định, chỉ nhìn các field lỗi thường gặp (error/message/errors/code/detail) hoặc chuỗi thô, bỏ dấu + chữ thường,
 * cắt độ dài. Trả về đoạn trích ngắn để làm bằng chứng, hoặc null nếu không khớp. Dấu che dữ liệu nhạy cảm bị loại trước khi so khớp.
 */
final class InputRejectionDetector {

    static final int MAX_SCAN_CHARS = 2000;
    private static final int EXCERPT_CHARS = 160;
    /** Từ khoá (không dấu, chữ thường) cho thấy API từ chối ĐẦU VÀO: tiếng Anh phổ biến + tiếng Việt. */
    private static final List<String> KEYWORDS = List.of(
            "invalid", "not valid", "validation", "in the past", "must be", "must not", "should be", "is required", "required field",
            "malformed", "not allowed", "too long", "too short", "out of range", "unprocessable", "does not exist in", "not in the list",
            "khong hop le", "khong co trong danh muc", "trong qua khu", "qua khu", "bat buoc", "khong duoc de trong", "dinh dang",
            "khong dung", "vuot qua", "khong ton tai trong");
    /** Field lỗi thường gặp (Spring, RFC 7807, Laravel/Django/Express...); nếu không có field nào thì quét cả body JSON. */
    private static final List<String> FIELDS = List.of(
            "error", "message", "errors", "code", "detail", "title", "violations", "fielderrors", "errormessage", "error_description",
            "msg", "reason", "description", "details");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InputRejectionDetector() {
    }

    static String excerptIfRejected(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        String text = relevantText(responseBody.replace(SensitiveDataScanner.MASK_MARKER, " "));
        if (text.isBlank()) {
            return null;
        }
        if (text.length() > MAX_SCAN_CHARS) {
            text = text.substring(0, MAX_SCAN_CHARS);
        }
        String folded = fold(text);
        for (String keyword : KEYWORDS) {
            if (folded.contains(keyword)) {
                String oneLine = text.replaceAll("\\s+", " ").trim();
                return oneLine.length() > EXCERPT_CHARS ? oneLine.substring(0, EXCERPT_CHARS) + "..." : oneLine;
            }
        }
        return null;
    }

    private static String relevantText(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode root = MAPPER.readTree(trimmed);
                StringBuilder sb = new StringBuilder();
                root.properties().forEach(entry -> {
                    if (FIELDS.contains(entry.getKey().toLowerCase(Locale.ROOT)) && !entry.getValue().isNull()) {
                        JsonNode node = entry.getValue();
                        sb.append(node.isValueNode() ? node.asText() : node.toString()).append(' ');
                    }
                });
                return sb.length() == 0 ? trimmed : sb.toString(); // không có field lỗi quen thuộc: quét cả body
            } catch (Exception ignored) {
                // không phải JSON hợp lệ: dùng chuỗi thô
            }
        }
        return trimmed;
    }

    private static String fold(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
