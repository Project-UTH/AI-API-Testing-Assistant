package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.exception.BusinessRuleException;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.FieldRule;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.ParamLocation;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.Spec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 17q G3 - kiểm TẤT ĐỊNH tham số luật theo mẫu và theo spec của endpoint (field phải tồn tại, kiểu khớp mẫu), rồi đối chiếu luật với
 * ràng buộc spec: trùng (không thêm gì so với checklist EP/BVA), mâu thuẫn (CONFLICT - "spec lệch tài liệu"), lỗi thời (field đã mất).
 * Hàm thuần, không đụng DB - dùng chung cho tạo/sửa luật (người dùng), phát hiện tự động và (G3.3) kiểm đầu ra AI.
 */
public final class RuleParamsValidator {

    private RuleParamsValidator() {
    }

    /** Kết quả đối chiếu luật với spec. */
    public enum Consistency { OK, DUPLICATES_SPEC, CONFLICT, STALE }

    public record Check(Consistency consistency, String note) {
        static Check ok() {
            return new Check(Consistency.OK, null);
        }
    }

    /** Ném BUSINESS_RULE_INVALID nếu tham số sai; trả tham số đã chuẩn hoá (giá trị mặc định, op/unit chữ hoa). */
    public static RuleParams validate(RuleTemplate template, RuleParams p, Spec spec, String method) {
        if (template == null) {
            throw BusinessRuleException.invalid("Phải chọn mẫu luật");
        }
        if (p == null) {
            throw BusinessRuleException.invalid("Thiếu tham số của luật");
        }
        String op = p.op() == null ? null : p.op().trim().toUpperCase(Locale.ROOT);
        return switch (template) {
            case COMPARE_FIELDS -> {
                FieldRule left = require(spec, p.left(), "vế trái");
                FieldRule right = require(spec, p.right(), "vế phải");
                if (p.left().trim().equals(p.right().trim())) {
                    throw BusinessRuleException.invalid("2 vế so sánh phải là 2 field khác nhau");
                }
                requireOp(op, RuleParams.OPS);
                boolean numeric = left.numeric() && right.numeric();
                boolean text = isText(left) && isText(right);
                if (!numeric && !text) {
                    throw BusinessRuleException.invalid("2 field so sánh phải cùng là số, hoặc cùng là chuỗi ngày/giờ");
                }
                yield new RuleParams(p.left().trim(), op, p.right().trim(), null, null, null, null, null, null, null, null, null, null, null);
            }
            case REQUIRES -> {
                require(spec, p.field(), "field điều kiện");
                List<String> then = requireGroup(spec, p.fields(), 1, "các field bắt buộc kèm theo");
                if (then.contains(p.field().trim())) {
                    throw BusinessRuleException.invalid("Field điều kiện không được nằm trong nhóm field bắt buộc kèm theo");
                }
                String value = p.fieldValue() == null || p.fieldValue().isBlank() ? null : p.fieldValue().trim();
                yield new RuleParams(null, null, null, p.field().trim(), value, then, null, null, null, null, null, null, null, null);
            }
            case ALL_OR_NONE, ONLY_ONE, ZERO_OR_ONE, OR -> {
                List<String> group = requireGroup(spec, p.fields(), 2, "nhóm field");
                yield new RuleParams(null, null, null, null, null, group, null, null, null, null, null, null, null, null);
            }
            case DATE_RELATIVE -> {
                FieldRule field = require(spec, p.field(), "field ngày/giờ");
                if (!isText(field)) {
                    throw BusinessRuleException.invalid("Field '" + p.field() + "' không phải chuỗi ngày/giờ");
                }
                requireOp(op, List.of("LT", "LTE", "GTE", "GT"));
                String unit = p.unit() == null ? "DAY" : p.unit().trim().toUpperCase(Locale.ROOT);
                if (!RuleParams.UNITS.contains(unit)) {
                    throw BusinessRuleException.invalid("Đơn vị phải là DAY hoặc HOUR");
                }
                int offset = p.offset() == null ? 0 : p.offset();
                if (Math.abs(offset) > 3650) {
                    throw BusinessRuleException.invalid("Khoảng lệch so với hôm nay quá lớn (tối đa 3650)");
                }
                yield new RuleParams(null, op, null, p.field().trim(), null, null, offset, unit, null, null, null, null, null, null);
            }
            case VALUE_RANGE -> {
                FieldRule field = require(spec, p.field(), "field");
                if (!field.numeric()) {
                    throw BusinessRuleException.invalid("Khoảng giá trị chỉ áp dụng cho field số; '" + p.field() + "' không phải số");
                }
                if (p.min() == null && p.max() == null) {
                    throw BusinessRuleException.invalid("Phải có ít nhất 1 biên (min hoặc max)");
                }
                if (p.min() != null && p.max() != null && p.min().compareTo(p.max()) > 0) {
                    throw BusinessRuleException.invalid("min phải nhỏ hơn hoặc bằng max");
                }
                yield new RuleParams(null, null, null, p.field().trim(), null, null, null, null, p.min(), p.max(), null, null, null, null);
            }
            case ARRAY_AGGREGATE -> {
                FieldRule array = require(spec, p.array(), "field mảng");
                if (!"array".equals(array.type())) {
                    throw BusinessRuleException.invalid("Field '" + p.array() + "' không phải mảng");
                }
                String fn = p.fn() == null ? null : p.fn().trim().toUpperCase(Locale.ROOT);
                if (fn == null || !RuleParams.FNS.contains(fn)) {
                    throw BusinessRuleException.invalid("Hàm tổng hợp phải là SUM, COUNT hoặc DISTINCT");
                }
                String item = null;
                if (!"COUNT".equals(fn)) {
                    if (p.item() == null || p.item().isBlank()) {
                        throw BusinessRuleException.invalid("Hàm " + fn + " cần field con của phần tử mảng");
                    }
                    item = p.item().trim();
                    FieldRule child = require(spec, p.array().trim() + "[0]." + item, "field con của phần tử");
                    if ("SUM".equals(fn) && !child.numeric()) {
                        throw BusinessRuleException.invalid("SUM cần field con là số; '" + item + "' không phải số");
                    }
                }
                if (!"DISTINCT".equals(fn)) {
                    requireOp(op, RuleParams.OPS);
                    if (p.value() == null) {
                        throw BusinessRuleException.invalid("Hàm " + fn + " cần hằng số để so sánh");
                    }
                }
                yield new RuleParams(null, "DISTINCT".equals(fn) ? null : op, null, null, null, null, null, null, null, null,
                        p.array().trim(), item, fn, "DISTINCT".equals(fn) ? null : p.value());
            }
            case UNIQUE -> {
                String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
                if (!m.equals("POST") && !m.equals("PUT")) {
                    throw BusinessRuleException.invalid("Luật không được tạo trùng chỉ áp dụng cho endpoint POST/PUT");
                }
                List<String> key = requireGroup(spec, p.fields(), 1, "các field làm khoá duy nhất");
                yield new RuleParams(null, null, null, null, null, key, null, null, null, null, null, null, null, null);
            }
        };
    }

    /** Mọi tham chiếu field của luật (để kiểm STALE khi spec đổi). */
    public static List<String> referencedFields(RuleTemplate template, RuleParams p) {
        List<String> refs = new ArrayList<>();
        switch (template) {
            case COMPARE_FIELDS -> {
                refs.add(p.left());
                refs.add(p.right());
            }
            case REQUIRES -> {
                refs.add(p.field());
                refs.addAll(p.fieldsOrEmpty());
            }
            case DATE_RELATIVE, VALUE_RANGE -> refs.add(p.field());
            case ARRAY_AGGREGATE -> {
                refs.add(p.array());
                if (p.item() != null) {
                    refs.add(p.array() + "[0]." + p.item());
                }
            }
            default -> refs.addAll(p.fieldsOrEmpty());
        }
        return refs;
    }

    /** Đối chiếu luật (đã hợp lệ về cú pháp) với ràng buộc spec hiện tại. */
    public static Check check(RuleTemplate template, RuleParams p, Spec spec) {
        for (String ref : referencedFields(template, p)) {
            RuleFieldRef parsed = RuleFieldRef.parse(ref);
            if (parsed == null || parsed.find(spec).isEmpty()) {
                return new Check(Consistency.STALE, "Field '" + ref + "' không còn trong spec của endpoint");
            }
        }
        return switch (template) {
            case VALUE_RANGE -> checkRange(p, find(spec, p.field()));
            case REQUIRES -> p.fieldsOrEmpty().stream().allMatch(f -> find(spec, f).required())
                    ? new Check(Consistency.DUPLICATES_SPEC, "Spec đã khai mọi field kèm theo là bắt buộc - checklist Negative đã kiểm")
                    : Check.ok();
            case OR -> p.fieldsOrEmpty().stream().anyMatch(f -> find(spec, f).required())
                    ? new Check(Consistency.DUPLICATES_SPEC, "Spec đã khai ít nhất 1 field trong nhóm là bắt buộc - luật luôn thoả")
                    : Check.ok();
            case ALL_OR_NONE -> p.fieldsOrEmpty().stream().allMatch(f -> find(spec, f).required())
                    ? new Check(Consistency.DUPLICATES_SPEC, "Spec đã khai mọi field trong nhóm là bắt buộc - checklist Negative đã kiểm")
                    : Check.ok();
            case ONLY_ONE, ZERO_OR_ONE -> p.fieldsOrEmpty().stream().filter(f -> find(spec, f).required()).count() >= 2
                    ? new Check(Consistency.CONFLICT, "Spec bắt buộc từ 2 field trở lên trong nhóm, mâu thuẫn với luật 'tối đa 1 field'")
                    : Check.ok();
            case COMPARE_FIELDS -> checkCompare(p, find(spec, p.left()), find(spec, p.right()));
            default -> Check.ok();
        };
    }

    private static Check checkRange(RuleParams p, FieldRule rule) {
        BigDecimal specMin = rule.minimum();
        BigDecimal specMax = rule.maximum();
        if ((p.max() != null && specMin != null && p.max().compareTo(specMin) < 0)
                || (p.min() != null && specMax != null && p.min().compareTo(specMax) > 0)) {
            return new Check(Consistency.CONFLICT, "Khoảng của luật [" + show(p.min()) + ", " + show(p.max()) + "] không giao với khoảng spec khai ["
                    + show(specMin) + ", " + show(specMax) + "] - spec và tài liệu nghiệp vụ lệch nhau");
        }
        // Rộng hơn spec ở bất kỳ phía nào = mâu thuẫn (luật cho phép giá trị spec cấm) - xét TRƯỚC "trùng spec".
        boolean wider = (p.min() != null && specMin != null && p.min().compareTo(specMin) < 0)
                || (p.max() != null && specMax != null && p.max().compareTo(specMax) > 0);
        if (wider) {
            return new Check(Consistency.CONFLICT, "Luật cho phép giá trị mà spec cấm (khoảng spec [" + show(specMin) + ", " + show(specMax)
                    + "]) - spec và tài liệu nghiệp vụ lệch nhau");
        }
        boolean tighter = (p.min() != null && (specMin == null || p.min().compareTo(specMin) > 0))
                || (p.max() != null && (specMax == null || p.max().compareTo(specMax) < 0));
        return tighter
                ? Check.ok()
                : new Check(Consistency.DUPLICATES_SPEC, "Khoảng của luật không hẹp hơn khoảng spec đã khai - checklist Boundary đã kiểm");
    }

    private static Check checkCompare(RuleParams p, FieldRule left, FieldRule right) {
        if (!left.numeric() || !right.numeric() || left.minimum() == null || right.maximum() == null) {
            return Check.ok();
        }
        boolean strict = "LT".equals(p.op()) || "LTE".equals(p.op()) || "EQ".equals(p.op());
        return strict && left.minimum().compareTo(right.maximum()) > 0
                ? new Check(Consistency.CONFLICT, "Spec cho '" + p.left() + "' tối thiểu " + show(left.minimum()) + " lớn hơn tối đa của '"
                        + p.right() + "' (" + show(right.maximum()) + ") - luật không bao giờ thoả")
                : Check.ok();
    }

    // ---- tiện ích ----

    private static FieldRule require(Spec spec, String ref, String what) {
        RuleFieldRef parsed = RuleFieldRef.parse(ref);
        if (parsed == null) {
            throw BusinessRuleException.invalid("Thiếu " + what);
        }
        return parsed.find(spec).orElseThrow(() -> BusinessRuleException.invalid(
                "Field '" + ref.trim() + "' (" + what + ") không có trong spec của endpoint" + nestedHint(parsed)));
    }

    private static String nestedHint(RuleFieldRef parsed) {
        return parsed.location() == ParamLocation.BODY && parsed.name().contains(".")
                ? " (chỉ hỗ trợ field lồng 1 cấp: cha.con hoặc cha[0].con)" : "";
    }

    private static FieldRule find(Spec spec, String ref) {
        return RuleFieldRef.parse(ref).find(spec).orElseThrow();
    }

    private static List<String> requireGroup(Spec spec, List<String> refs, int minSize, String what) {
        if (refs == null || refs.stream().filter(r -> r != null && !r.isBlank()).count() < minSize) {
            throw BusinessRuleException.invalid("Cần ít nhất " + minSize + " field cho " + what);
        }
        List<String> cleaned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            String trimmed = ref.trim();
            require(spec, trimmed, what);
            if (!seen.add(trimmed)) {
                throw BusinessRuleException.invalid("Field '" + trimmed + "' bị lặp trong " + what);
            }
            cleaned.add(trimmed);
        }
        return cleaned;
    }

    private static void requireOp(String op, List<String> allowed) {
        if (op == null || !allowed.contains(op)) {
            throw BusinessRuleException.invalid("Phép so sánh phải là một trong " + allowed);
        }
    }

    static boolean isText(FieldRule rule) {
        return rule.type() == null || "string".equals(rule.type());
    }

    private static String show(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }
}
