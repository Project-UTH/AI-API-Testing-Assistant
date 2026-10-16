package com.aiapitesting.backend.service;

import io.swagger.v3.oas.models.media.Schema;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Quy tắc KIỂU DỮ LIỆU và NULL theo OpenAPI, dùng CHUNG cho Tier 1 (TestCaseReliabilityValidator)
 * và bộ chấm checklist 15h (TestCaseChecklistPlanner) - 2 nơi phải hiểu giống hệt nhau, nếu không
 * cùng 1 case có thể bị 1 bên đánh "đúng", bên kia đánh "Cần xem lại".
 *
 * Phát hiện khi verify 15h bằng AI thật (2026-10-15): Tier 1 trước đây không kiểm 2 điều này nên
 * gắn "Cần xem lại" oan cho case Negative hoàn toàn đúng ("price": "rat-re" vào field number,
 * "name": null vào field string không nullable), rồi đẩy sang Tier 2 tốn token - và Tier 2 (AI) còn
 * phán sai 1 case (cho rằng required không cấm null, trong khi OpenAPI 3.0 type string không có
 * nullable: true thì null là không hợp lệ).
 */
public final class SpecValueRules {

    private static final Set<String> CHECKED_TYPES = Set.of("integer", "number", "boolean", "string");

    private SpecValueRules() {
    }

    /** Kiểu khai báo của field (OpenAPI 3.0 type, hoặc kiểu đầu tiên khác "null" của 3.1 types); null nếu không khai. */
    public static String typeOf(Schema<?> schema) {
        if (schema == null) {
            return null;
        }
        if (schema.getType() != null) {
            return schema.getType();
        }
        if (schema.getTypes() != null) {
            return schema.getTypes().stream().filter(t -> !"null".equals(t)).findFirst().orElse(null);
        }
        return null;
    }

    /** OpenAPI 3.0: chỉ cho null khi nullable: true. OpenAPI 3.1: khi types có "null". Không khai kiểu gì thì không suy diễn (cho phép). */
    public static boolean allowsNull(Schema<?> schema) {
        if (schema == null) {
            return true;
        }
        if (Boolean.TRUE.equals(schema.getNullable())) {
            return true;
        }
        if (schema.getTypes() != null && schema.getTypes().contains("null")) {
            return true;
        }
        return typeOf(schema) == null;
    }

    /**
     * Giá trị có đúng kiểu khai báo không. fromJsonBody=true: giá trị lấy từ requestBody JSON, phải
     * đúng kiểu JSON (số là number, không phải chuỗi "599000"). false: giá trị path/query/header luôn
     * là chuỗi, chỉ cần đọc được theo kiểu đó. Kiểu object/array/không khai thì không kiểm.
     */
    public static boolean matchesType(String type, Object value, boolean fromJsonBody) {
        if (type == null || value == null || !CHECKED_TYPES.contains(type)) {
            return true;
        }
        return switch (type) {
            case "integer" -> isNumber(value, fromJsonBody) && isIntegral(String.valueOf(value));
            case "number" -> isNumber(value, fromJsonBody);
            case "boolean" -> fromJsonBody ? value instanceof Boolean : "true".equals(value) || "false".equals(value);
            default -> !fromJsonBody || value instanceof String; // string
        };
    }

    /**
     * Module 15i - minimum/maximum hiệu lực, tính cả exclusive: OpenAPI 3.0 khai {@code minimum} kèm cờ
     * {@code exclusiveMinimum: true} (giá trị đúng bằng minimum KHÔNG hợp lệ), OpenAPI 3.1 khai thẳng
     * {@code exclusiveMinimum: <số>}. Trước đây cả 2 bị bỏ qua nên biên bị hiểu lệch 1 đơn vị.
     */
    public static BigDecimal effectiveMinimum(Schema<?> schema) {
        return schema.getExclusiveMinimumValue() != null ? schema.getExclusiveMinimumValue() : schema.getMinimum();
    }

    public static boolean minimumExclusive(Schema<?> schema) {
        return schema.getExclusiveMinimumValue() != null
                || (schema.getMinimum() != null && Boolean.TRUE.equals(schema.getExclusiveMinimum()));
    }

    public static BigDecimal effectiveMaximum(Schema<?> schema) {
        return schema.getExclusiveMaximumValue() != null ? schema.getExclusiveMaximumValue() : schema.getMaximum();
    }

    public static boolean maximumExclusive(Schema<?> schema) {
        return schema.getExclusiveMaximumValue() != null
                || (schema.getMaximum() != null && Boolean.TRUE.equals(schema.getExclusiveMaximum()));
    }

    /**
     * Module 15i - giới hạn ngầm của kiểu số nguyên theo format (int32/int64), dù spec không khai
     * minimum/maximum: giá trị ngoài khoảng này không biểu diễn được bằng kiểu đó nên chắc chắn không
     * hợp lệ. Null nếu không phải integer có format int32/int64.
     */
    public static BigDecimal formatMaximum(Schema<?> schema) {
        return switch (integerFormat(schema)) {
            case "int32" -> BigDecimal.valueOf(Integer.MAX_VALUE);
            case "int64" -> BigDecimal.valueOf(Long.MAX_VALUE);
            default -> null;
        };
    }

    public static BigDecimal formatMinimum(Schema<?> schema) {
        return switch (integerFormat(schema)) {
            case "int32" -> BigDecimal.valueOf(Integer.MIN_VALUE);
            case "int64" -> BigDecimal.valueOf(Long.MIN_VALUE);
            default -> null;
        };
    }

    private static String integerFormat(Schema<?> schema) {
        if (schema == null || !"integer".equals(typeOf(schema)) || schema.getFormat() == null) {
            return "";
        }
        return schema.getFormat();
    }

    private static boolean isNumber(Object value, boolean fromJsonBody) {
        if (fromJsonBody && !(value instanceof Number)) {
            return false;
        }
        return parse(String.valueOf(value)) != null;
    }

    private static boolean isIntegral(String text) {
        BigDecimal number = parse(text);
        return number != null && number.stripTrailingZeros().scale() <= 0;
    }

    private static BigDecimal parse(String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
