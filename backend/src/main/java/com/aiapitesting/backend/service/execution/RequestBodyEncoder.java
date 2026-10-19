package com.aiapitesting.backend.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Generalization - chọn Content-Type và cách mã hoá body khi gọi API đích (không còn ép JSON cho mọi API):
 * header Content-Type do test case tự đặt thắng; không có thì theo spec của endpoint (requestContentType); không có nữa thì JSON.
 * Body test case luôn sinh dạng JSON object, nên với form-urlencoded/multipart ta chuyển các field phẳng thành tham số form
 * (giá trị lồng nhau được gửi dạng chuỗi JSON). text/xml, text/plain... gửi body nguyên văn (người dùng tự viết đúng định dạng).
 * Giới hạn: multipart chỉ gửi field văn bản, KHÔNG gửi file.
 */
final class RequestBodyEncoder {

    enum Kind {
        JSON, FORM, MULTIPART, RAW
    }

    /** @param mediaType media type gửi đi (vd application/json); form: field phẳng khi Kind = FORM/MULTIPART */
    record Plan(Kind kind, String mediaType, Map<String, String> fields) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestBodyEncoder() {
    }

    /** @param headerContentType Content-Type người dùng đặt trong requestHeaders (null nếu không có) */
    static Plan plan(String headerContentType, String specContentType, String body) {
        String chosen = firstNonBlank(headerContentType, specContentType);
        if (chosen == null || chosen.contains("*") || chosen.toLowerCase(Locale.ROOT).contains("json")) {
            return new Plan(Kind.JSON, chosen == null || chosen.contains("*") ? "application/json" : chosen, Map.of());
        }
        String lower = chosen.toLowerCase(Locale.ROOT);
        boolean form = lower.startsWith("application/x-www-form-urlencoded");
        boolean multipart = lower.startsWith("multipart/");
        if (form || multipart) {
            Map<String, String> fields = flatFields(body);
            if (fields != null) {
                return new Plan(form ? Kind.FORM : Kind.MULTIPART, form ? "application/x-www-form-urlencoded" : "multipart/form-data", fields);
            }
            if (form) {
                return new Plan(Kind.RAW, chosen, Map.of()); // body đã là "a=1&b=2": gửi nguyên văn
            }
        }
        return new Plan(Kind.RAW, chosen, Map.of());
    }

    /** JSON object -> field phẳng (giá trị lồng nhau thành chuỗi JSON); null nếu không phải JSON object. */
    static Map<String, String> flatFields(String body) {
        if (body == null || body.isBlank() || !body.trim().startsWith("{")) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body.trim());
            if (!root.isObject()) {
                return null;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            // Form không có khái niệm null: JSON null = bỏ field (nếu không sẽ gửi chuỗi "null" và tạo kết quả sai).
            root.properties().forEach(e -> {
                if (!e.getValue().isNull()) {
                    fields.put(e.getKey(), e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString());
                }
            });
            return fields;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a.trim() : b != null && !b.isBlank() ? b.trim() : null;
    }
}
