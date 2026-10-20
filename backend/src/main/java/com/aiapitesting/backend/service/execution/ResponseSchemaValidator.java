package com.aiapitesting.backend.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Đối chiếu phản hồi thật của API với schema response khai báo trong OpenAPI spec (tất định, 0 token). Cố ý CHỈ kiểm
 * 3 thứ ít sai nhất: kiểu dữ liệu, trường bắt buộc (required) và phần tử mảng, chỉ khi phản hồi là JSON - bỏ qua format, enum, min/max,
 * oneOf/anyOf, additionalProperties để tránh báo sai (spec thực tế hay lệch phản hồi, tín hiệu thật đã nhiều). Kết quả
 * chỉ là CẢNH BÁO: không đổi Pass/Fail của test.
 */
public final class ResponseSchemaValidator {

    /** Số vi phạm tối đa trả về - đủ để hiểu, không làm phình kết quả. */
    static final int MAX_VIOLATIONS = 5;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_ARRAY_ELEMENTS = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponseSchemaValidator() {
    }

    /**
     * @param schemaJson   schema của 1 response (dạng OpenAPI, đã inline $ref)
     * @param responseBody thân phản hồi thật
     * @return danh sách vi phạm (rỗng = khớp, hoặc không đủ dữ liệu để kết luận)
     */
    public static List<String> validate(String schemaJson, String responseBody) {
        List<String> violations = new ArrayList<>();
        if (schemaJson == null || schemaJson.isBlank()) {
            return violations;
        }
        JsonNode schema;
        try {
            schema = MAPPER.readTree(schemaJson);
        } catch (Exception e) {
            return violations; // schema hỏng thì không thể kết luận gì về phản hồi
        }
        // Phản hồi rỗng/không phải JSON: không đủ cơ sở để kết luận (spec hay khai "*/*" cho cả phản hồi không phải JSON)
        // nên KHÔNG cảnh báo - chỉ kiểm khi đọc được JSON.
        if (responseBody == null || responseBody.isBlank()) {
            return violations;
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(responseBody);
        } catch (Exception e) {
            return violations;
        }
        check(schema, body, "$", 0, false, violations);
        return violations;
    }

    /**
     * @param nullForbidden true chỉ với trường BẮT BUỘC: framework (springdoc...) hầu như không đánh dấu nullable nên API
     *                      thường trả null cho trường tuỳ chọn dù spec khai kiểu thường - cảnh báo ở đó chỉ là báo sai.
     */
    private static void check(JsonNode schema, JsonNode value, String path, int depth, boolean nullForbidden, List<String> out) {
        if (out.size() >= MAX_VIOLATIONS || depth > MAX_DEPTH || schema == null || !schema.isObject()) {
            return;
        }
        String type = declaredType(schema);
        if (value == null || value.isNull()) {
            if (nullForbidden && type != null && !schema.path("nullable").asBoolean(false)) {
                out.add(path + ": giá trị null nhưng spec không cho phép null (nullable)");
            }
            return;
        }
        JsonNode allOf = schema.get("allOf");
        if (allOf != null && allOf.isArray()) {
            for (JsonNode part : allOf) {
                check(part, value, path, depth + 1, nullForbidden, out);
            }
        }
        if (type != null && !typeMatches(type, value)) {
            out.add(path + ": kiểu thực tế là " + actualType(value) + " nhưng spec khai báo " + type);
            return;
        }
        if (value.isObject()) {
            JsonNode required = schema.get("required");
            JsonNode properties = schema.get("properties");
            if (required != null && required.isArray()) {
                for (JsonNode name : required) {
                    if (out.size() >= MAX_VIOLATIONS) {
                        break;
                    }
                    String field = name.asText();
                    boolean writeOnly = properties != null && properties.path(field).path("writeOnly").asBoolean(false);
                    if (!value.has(field) && !writeOnly) {
                        out.add(path + "." + field + ": thiếu trường bắt buộc");
                    }
                }
            }
            if (properties != null && properties.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> property = fields.next();
                    if (value.has(property.getKey())) {
                        boolean isRequired = false;
                        if (required != null && required.isArray()) {
                            for (JsonNode name : required) {
                                isRequired |= property.getKey().equals(name.asText());
                            }
                        }
                        check(property.getValue(), value.get(property.getKey()), path + "." + property.getKey(), depth + 1,
                                isRequired, out);
                    }
                }
            }
        } else if (value.isArray()) {
            JsonNode items = schema.get("items");
            if (items != null) {
                for (int i = 0; i < Math.min(value.size(), MAX_ARRAY_ELEMENTS); i++) {
                    check(items, value.get(i), path + "[" + i + "]", depth + 1, false, out);
                }
            }
        }
    }

    /** `type` khai báo, hoặc suy từ properties/items khi spec quên khai (OpenAPI cho phép). */
    private static String declaredType(JsonNode schema) {
        JsonNode type = schema.get("type");
        if (type != null && type.isTextual()) {
            return type.asText();
        }
        if (schema.has("properties")) {
            return "object";
        }
        if (schema.has("items")) {
            return "array";
        }
        return null;
    }

    private static boolean typeMatches(String type, JsonNode value) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber() || (value.isNumber() && value.doubleValue() == Math.rint(value.doubleValue()));
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> true; // kiểu lạ: không kết luận
        };
    }

    private static String actualType(JsonNode value) {
        if (value.isTextual()) return "string";
        if (value.isIntegralNumber()) return "integer";
        if (value.isNumber()) return "number";
        if (value.isBoolean()) return "boolean";
        if (value.isObject()) return "object";
        if (value.isArray()) return "array";
        return "không xác định";
    }
}
