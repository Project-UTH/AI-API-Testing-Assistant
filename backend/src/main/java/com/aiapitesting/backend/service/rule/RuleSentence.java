package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.entity.RuleTemplate;

import java.math.BigDecimal;
import java.util.List;

/** 17q G3 - câu luật tiếng Việt cho người đọc (danh sách luật, lý do, tên test). Hàm thuần; frontend có hàm tương ứng cho bản xem trước. */
public final class RuleSentence {

    private RuleSentence() {
    }

    public static String of(RuleTemplate template, RuleParams p) {
        return switch (template) {
            case COMPARE_FIELDS -> p.left() + " " + symbol(p.op()) + " " + p.right();
            case REQUIRES -> "Có " + p.field() + (p.fieldValue() == null ? "" : " = " + p.fieldValue()) + " thì phải có " + join(p.fieldsOrEmpty());
            case ALL_OR_NONE -> "Có đủ cả nhóm [" + join(p.fieldsOrEmpty()) + "] hoặc không có field nào";
            case ONLY_ONE -> "Đúng 1 field trong nhóm [" + join(p.fieldsOrEmpty()) + "]";
            case ZERO_OR_ONE -> "Tối đa 1 field trong nhóm [" + join(p.fieldsOrEmpty()) + "]";
            case OR -> "Ít nhất 1 field trong nhóm [" + join(p.fieldsOrEmpty()) + "]";
            case DATE_RELATIVE -> p.field() + " " + symbol(p.op()) + " bây giờ" + offset(p);
            case VALUE_RANGE -> p.field() + " trong khoảng [" + show(p.min()) + ", " + show(p.max()) + "]";
            case ARRAY_AGGREGATE -> aggregate(p);
            case UNIQUE -> "Không được tạo trùng theo [" + join(p.fieldsOrEmpty()) + "]";
        };
    }

    private static String aggregate(RuleParams p) {
        return switch (p.fn()) {
            case "SUM" -> "Tổng " + p.array() + "[].".concat(p.item()) + " " + symbol(p.op()) + " " + show(p.value());
            case "COUNT" -> "Số phần tử " + p.array() + " " + symbol(p.op()) + " " + show(p.value());
            default -> p.array() + "[]." + p.item() + " không trùng nhau";
        };
    }

    private static String offset(RuleParams p) {
        int offset = p.offset() == null ? 0 : p.offset();
        if (offset == 0) {
            return "";
        }
        return (offset > 0 ? " + " : " - ") + Math.abs(offset) + ("HOUR".equals(p.unit()) ? " giờ" : " ngày");
    }

    private static String symbol(String op) {
        return switch (op == null ? "" : op) {
            case "LT" -> "<";
            case "LTE" -> "≤";
            case "EQ" -> "=";
            case "GTE" -> "≥";
            case "GT" -> ">";
            default -> "?";
        };
    }

    private static String join(List<String> fields) {
        return String.join(", ", fields);
    }

    private static String show(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }
}
