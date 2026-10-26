package com.aiapitesting.backend.service.rule;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * 17q G3 - tham số của 1 luật nghiệp vụ (lưu JSON ở BusinessRule.paramsJson). Một record chung cho mọi mẫu - mỗi mẫu chỉ dùng vài
 * trường, {@link RuleParamsValidator} kiểm đúng trường nào bắt buộc theo mẫu. Tham chiếu field: "salaryMin", "criteria[0].weight"
 * (body lồng 1 cấp, cùng quy ước BodyPath), "query:status", "header:X-Tenant" (mặc định là body).
 *
 * @param left   COMPARE_FIELDS - field vế trái
 * @param op     COMPARE_FIELDS/DATE_RELATIVE/ARRAY_AGGREGATE - LT, LTE, EQ, GTE, GT
 * @param right  COMPARE_FIELDS - field vế phải
 * @param field  DATE_RELATIVE/VALUE_RANGE/REQUIRES (field điều kiện) - field chính
 * @param fieldValue REQUIRES - chỉ áp dụng khi field = giá trị này (null = khi field có mặt)
 * @param fields REQUIRES (các field phải có), ALL_OR_NONE/ONLY_ONE/ZERO_OR_ONE/OR (nhóm), UNIQUE (khoá)
 * @param offset DATE_RELATIVE - số đơn vị so với bây giờ (0 = hôm nay/bây giờ)
 * @param unit   DATE_RELATIVE - DAY hoặc HOUR
 * @param min    VALUE_RANGE - biên dưới (bao gồm)
 * @param max    VALUE_RANGE - biên trên (bao gồm)
 * @param array  ARRAY_AGGREGATE - field mảng (cấp 1)
 * @param item   ARRAY_AGGREGATE - field con của phần tử (SUM/DISTINCT); null với COUNT
 * @param fn     ARRAY_AGGREGATE - SUM, COUNT, DISTINCT
 * @param value  ARRAY_AGGREGATE - hằng so sánh (SUM/COUNT)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleParams(
        String left, String op, String right,
        String field, String fieldValue, List<String> fields,
        Integer offset, String unit,
        BigDecimal min, BigDecimal max,
        String array, String item, String fn, BigDecimal value
) {
    public static final List<String> OPS = List.of("LT", "LTE", "EQ", "GTE", "GT");
    public static final List<String> UNITS = List.of("DAY", "HOUR");
    public static final List<String> FNS = List.of("SUM", "COUNT", "DISTINCT");

    public List<String> fieldsOrEmpty() {
        return fields == null ? List.of() : fields;
    }
}
