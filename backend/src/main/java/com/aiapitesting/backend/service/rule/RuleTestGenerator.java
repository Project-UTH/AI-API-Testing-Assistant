package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.service.BodyPath;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.FieldRule;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.ParamLocation;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.Spec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 17q G3 - sinh test TẤT ĐỊNH từ 1 luật nghiệp vụ đã duyệt: copy test gốc hợp lệ rồi sửa ĐÚNG các field của luật để vi phạm luật
 * (cộng 1 test biên hợp lệ khi mẫu có biên rõ). Không gọi AI. Mọi bản nháp đều được TỰ KIỂM: phải không vi phạm ràng buộc nào của spec
 * (nếu vi phạm, lý do API từ chối có thể là spec chứ không phải luật - không tạo test). Chỉ làm việc trên body JSON; luật ở query/header
 * chưa hỗ trợ sinh (báo lý do, người dùng tự viết test).
 */
@Component
public class RuleTestGenerator {

    private static final Pattern TODAY_TOKEN = Pattern.compile("\\{\\{today(?:([+-])(\\d{1,4})d)?}}");
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final TestCaseChecklistPlanner planner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RuleTestGenerator(TestCaseChecklistPlanner planner) {
        this.planner = planner;
    }

    /**
     * @param violation true = test vi phạm luật (kỳ vọng "bị từ chối"); false = test biên hợp lệ (kỳ vọng như test gốc)
     * @param sendTwice true = gửi request 2 lần liên tiếp (mẫu UNIQUE); lần 2 mới được chấm
     */
    public record Draft(String name, String description, GeneratedTestCase testCase, boolean violation, boolean sendTwice) {
    }

    public record Result(List<Draft> drafts, String reason) {
        static Result of(List<Draft> drafts, String reasonIfEmpty) {
            return new Result(drafts, drafts.isEmpty() ? reasonIfEmpty : null);
        }

        static Result none(String reason) {
            return new Result(List.of(), reason);
        }
    }

    public Result generate(RuleTemplate template, RuleParams p, Spec spec, GeneratedTestCase baseline, String ruleLabel) {
        if (baseline == null) {
            return Result.none("Chưa có test gốc hợp lệ đã Pass để mượn dữ liệu - sinh test case (tick Positive) hoặc tự thêm 1 test hợp lệ, chạy cho Pass rồi sinh lại");
        }
        if (!spec.bodyIsJson()) {
            return Result.none("Endpoint không có body JSON/form - luật nghiệp vụ chỉ sinh được test cho tham số trong body");
        }
        Map<String, Object> body = parse(baseline.requestBody());
        List<Mutation> mutations;
        try {
            mutations = switch (template) {
                case COMPARE_FIELDS -> compare(p, body);
                case REQUIRES -> requires(p, body);
                case ALL_OR_NONE -> allOrNone(p, body);
                case ONLY_ONE -> onlyOne(p, body, spec, true);
                case ZERO_OR_ONE -> onlyOne(p, body, spec, false);
                case OR -> or(p, body);
                case DATE_RELATIVE -> dateRelative(p);
                case VALUE_RANGE -> valueRange(p, spec);
                case ARRAY_AGGREGATE -> arrayAggregate(p, body);
                case UNIQUE -> unique(body);
            };
        } catch (Skip skip) {
            return Result.none(skip.getMessage());
        }

        List<Draft> drafts = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (Mutation m : mutations) {
            Map<String, Object> copy = deepCopy(body);
            if (!m.apply.apply(copy)) {
                rejected.add(m.label + ": không dựng được từ test gốc");
                continue;
            }
            String json = write(copy);
            if (json == null) {
                continue;
            }
            // Tự kiểm: bản nháp không được vi phạm ràng buộc nào của spec (kể cả test biên hợp lệ).
            GeneratedTestCase probe = withBody(baseline, json, baseline.expectedStatus() == null ? 200 : baseline.expectedStatus(), baseline.name());
            if (!planner.isValidBaseline(spec, probe)) {
                rejected.add(m.label + ": giá trị sinh ra vi phạm ràng buộc khác của spec nên không dùng làm bằng chứng cho luật");
                continue;
            }
            String prefix = m.violation ? "Nghiệp vụ - vi phạm: " : "Nghiệp vụ - biên hợp lệ: ";
            String name = prefix + ruleLabel + (m.label.isBlank() ? "" : " (" + m.label + ")");
            Integer expected = m.violation ? 400 : baseline.expectedStatus();
            GeneratedTestCase generated = new GeneratedTestCase(name,
                    "Hệ thống sinh từ luật nghiệp vụ đã duyệt: " + ruleLabel, baseline.requestHeaders(), json, expected,
                    baseline.resolvedPath(), baseline.pathParamFallbacks(), baseline.authOverride(),
                    (m.violation ? "Vi phạm đúng 1 luật: " : "Biên hợp lệ của luật: ") + ruleLabel);
            drafts.add(new Draft(name, generated.description(), generated, m.violation, m.sendTwice));
        }
        return Result.of(drafts, rejected.isEmpty() ? "Không dựng được test nào từ test gốc hiện có" : String.join("; ", rejected));
    }

    // ---- các mẫu ----

    private List<Mutation> compare(RuleParams p, Map<String, Object> body) {
        BigDecimal a = number(body, p.left());
        BigDecimal b = number(body, p.right());
        if (a != null && b != null) {
            if (!holds(p.op(), a, b)) {
                throw new Skip("Test gốc đã vi phạm luật '" + p.left() + " " + p.op() + " " + p.right() + "' - luật hoặc test gốc sai");
            }
            List<Mutation> out = new ArrayList<>();
            BigDecimal violating = switch (p.op()) {
                case "LTE", "EQ" -> b.add(BigDecimal.ONE);
                case "LT" -> b;
                case "GTE" -> b.subtract(BigDecimal.ONE);
                default -> b; // GT
            };
            out.add(set(p.left(), violating, true, p.left() + " = " + plain(violating)));
            BigDecimal boundary = switch (p.op()) {
                case "LTE", "GTE" -> b;
                case "LT" -> b.subtract(BigDecimal.ONE);
                case "GT" -> b.add(BigDecimal.ONE);
                default -> null;
            };
            if (boundary != null && isInteger(a) && isInteger(b)) {
                out.add(set(p.left(), boundary, false, p.left() + " = " + plain(boundary)));
            }
            return out;
        }
        // Chuỗi ngày: vế phải là literal/placeholder ngày trong test gốc
        Object av = BodyPath.get(body, p.left());
        Object bv = BodyPath.get(body, p.right());
        if (av instanceof String as && bv instanceof String bs) {
            int dir = switch (p.op()) {
                case "LTE", "LT", "EQ" -> 1;
                default -> -1;
            };
            Optional<String> beyond = shiftDate(bs, dir * (("EQ".equals(p.op()) || "LTE".equals(p.op()) || "GTE".equals(p.op())) ? 1 : 0));
            if (beyond.isEmpty()) {
                throw new Skip("Giá trị ngày của '" + p.right() + "' trong test gốc không đổi được (cần dạng yyyy-MM-dd hoặc {{today+Nd}})");
            }
            List<Mutation> out = new ArrayList<>();
            out.add(set(p.left(), beyond.get(), true, p.left() + " = " + beyond.get()));
            return out;
        }
        throw new Skip("Test gốc thiếu giá trị số/ngày của '" + p.left() + "' hoặc '" + p.right() + "'");
    }

    private List<Mutation> requires(RuleParams p, Map<String, Object> body) {
        if (!BodyPath.contains(body, p.field())) {
            throw new Skip("Test gốc không có field điều kiện '" + p.field() + "' nên luật không được kích hoạt");
        }
        if (p.fieldValue() != null && !p.fieldValue().equals(String.valueOf(BodyPath.get(body, p.field())))) {
            throw new Skip("Test gốc có '" + p.field() + "' khác '" + p.fieldValue() + "' nên luật không được kích hoạt");
        }
        List<Mutation> out = new ArrayList<>();
        for (String then : p.fieldsOrEmpty()) {
            if (BodyPath.contains(body, then) && out.size() < 3) {
                out.add(remove(List.of(then), true, "bỏ " + then));
            }
        }
        if (out.isEmpty()) {
            throw new Skip("Test gốc không có field nào trong nhóm bắt buộc kèm theo để bỏ đi");
        }
        return out;
    }

    private List<Mutation> allOrNone(RuleParams p, Map<String, Object> body) {
        long present = p.fieldsOrEmpty().stream().filter(f -> BodyPath.contains(body, f)).count();
        if (present != p.fieldsOrEmpty().size()) {
            throw new Skip("Test gốc cần có ĐỦ mọi field trong nhóm (hoặc không có field nào) để bỏ bớt 1 field");
        }
        return List.of(remove(List.of(p.fieldsOrEmpty().get(p.fieldsOrEmpty().size() - 1)), true,
                "bỏ " + p.fieldsOrEmpty().get(p.fieldsOrEmpty().size() - 1)));
    }

    private List<Mutation> onlyOne(RuleParams p, Map<String, Object> body, Spec spec, boolean exactlyOne) {
        List<String> present = p.fieldsOrEmpty().stream().filter(f -> BodyPath.contains(body, f)).toList();
        if (present.size() > 1 || (exactlyOne && present.isEmpty())) {
            throw new Skip("Test gốc phải có đúng 1 field trong nhóm" + (exactlyOne ? "" : " (hoặc không có)") + " để thêm field thứ 2");
        }
        List<Mutation> out = new ArrayList<>();
        for (String other : p.fieldsOrEmpty()) {
            if (!present.contains(other)) {
                Object sample = sampleValue(spec, other);
                if (sample != null) {
                    out.add(set(other, sample, true, "thêm " + other));
                    break;
                }
            }
        }
        if (out.isEmpty()) {
            throw new Skip("Không dựng được giá trị hợp lệ cho field thứ 2 của nhóm");
        }
        if (exactlyOne) {
            out.add(remove(present, true, "không có field nào trong nhóm"));
        }
        return out;
    }

    private List<Mutation> or(RuleParams p, Map<String, Object> body) {
        List<String> present = p.fieldsOrEmpty().stream().filter(f -> BodyPath.contains(body, f)).toList();
        if (present.isEmpty()) {
            throw new Skip("Test gốc không có field nào trong nhóm - luật 'ít nhất 1 field' đã bị vi phạm sẵn");
        }
        return List.of(remove(present, true, "bỏ cả nhóm"));
    }

    private List<Mutation> dateRelative(RuleParams p) {
        boolean hours = "HOUR".equals(p.unit());
        int offset = p.offset() == null ? 0 : p.offset();
        List<Mutation> out = new ArrayList<>();
        String violating;
        String boundary = null;
        if (hours) {
            int ref = offset * 60;
            boolean lower = "GTE".equals(p.op()) || "GT".equals(p.op());
            violating = nowExpr(lower ? ref - 5 : ref + 5);
        } else {
            switch (p.op()) {
                case "GTE" -> {
                    violating = dayExpr(offset - 1);
                    boundary = dayExpr(offset);
                }
                case "GT" -> {
                    violating = dayExpr(offset);
                    boundary = dayExpr(offset + 1);
                }
                case "LTE" -> {
                    violating = dayExpr(offset + 1);
                    boundary = dayExpr(offset);
                }
                default -> { // LT
                    violating = dayExpr(offset);
                    boundary = dayExpr(offset - 1);
                }
            }
        }
        out.add(set(p.field(), violating, true, p.field() + " = " + violating));
        if (boundary != null) {
            out.add(set(p.field(), boundary, false, p.field() + " = " + boundary));
        }
        return out;
    }

    private List<Mutation> valueRange(RuleParams p, Spec spec) {
        boolean integer = RuleFieldRef.parse(p.field()).find(spec).map(r -> "integer".equals(r.type())).orElse(false);
        BigDecimal step = integer ? BigDecimal.ONE : new BigDecimal("0.01");
        List<Mutation> out = new ArrayList<>();
        if (p.min() != null) {
            out.add(set(p.field(), p.min().subtract(step), true, p.field() + " = " + plain(p.min().subtract(step))));
            out.add(set(p.field(), p.min(), false, p.field() + " = " + plain(p.min())));
        }
        if (p.max() != null) {
            out.add(set(p.field(), p.max().add(step), true, p.field() + " = " + plain(p.max().add(step))));
            out.add(set(p.field(), p.max(), false, p.field() + " = " + plain(p.max())));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Mutation> arrayAggregate(RuleParams p, Map<String, Object> body) {
        Object raw = BodyPath.get(body, p.array());
        if (!(raw instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map)) {
            throw new Skip("Test gốc cần có mảng '" + p.array() + "' với ít nhất 1 phần tử để thao tác");
        }
        String fn = p.fn();
        if ("DISTINCT".equals(fn)) {
            return List.of(new Mutation("nhân đôi phần tử đầu", true, false, root -> {
                Object arr = BodyPath.get(root, p.array());
                if (!(arr instanceof List<?> l) || l.isEmpty()) {
                    return false;
                }
                ((List<Object>) l).add(deepCopyValue(l.get(0)));
                return true;
            }));
        }
        BigDecimal current = "COUNT".equals(fn) ? BigDecimal.valueOf(list.size()) : sum(list, p.item());
        if (current == null) {
            throw new Skip("Các phần tử mảng '" + p.array() + "' không có field số '" + p.item() + "'");
        }
        if (!holds(p.op(), current, p.value())) {
            throw new Skip("Test gốc đã vi phạm luật tổng hợp trên '" + p.array() + "' (hiện " + plain(current) + ") - luật hoặc test gốc sai");
        }
        // Hai phía của giới hạn (BVA): các giá trị VI PHẠM phải bị từ chối, các giá trị SÁT giới hạn phía hợp lệ phải được chấp nhận.
        // Chỉ thử 1 phía vi phạm (như trước) bỏ lọt lỗi dạng 'total > 100' thay vì 'total != 100' (H10) và lỗi lệch biên 1 đơn vị.
        BigDecimal value = p.value();
        List<BigDecimal> violations = switch (p.op()) {
            case "EQ" -> List.of(value.add(BigDecimal.ONE), value.subtract(BigDecimal.ONE));
            case "LTE" -> List.of(value.add(BigDecimal.ONE));
            case "GTE" -> List.of(value.subtract(BigDecimal.ONE));
            default -> List.of(value); // LT, GT: đúng bằng giá trị là vi phạm
        };
        List<BigDecimal> validEdges = switch (p.op()) {
            case "LTE", "GTE" -> List.of(value);
            case "LT" -> List.of(value.subtract(BigDecimal.ONE));
            case "GT" -> List.of(value.add(BigDecimal.ONE));
            default -> List.of(); // EQ: test gốc đã đúng bằng giá trị
        };
        List<Mutation> out = new ArrayList<>();
        for (BigDecimal target : violations) {
            if (!"COUNT".equals(fn) || target.signum() >= 0) {
                out.add(aggregateMutation(p, fn, current, target, true));
            }
        }
        for (BigDecimal target : validEdges) {
            // đúng vị trí của test gốc thì không cần thử lại; đếm phần tử thì giữ tối thiểu 1 phần tử để thao tác tiếp
            if (target.compareTo(current) != 0 && (!"COUNT".equals(fn) || target.signum() > 0)) {
                out.add(aggregateMutation(p, fn, current, target, false));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Mutation aggregateMutation(RuleParams p, String fn, BigDecimal current, BigDecimal target, boolean violation) {
        BigDecimal delta = target.subtract(current);
        String label = fn + (p.item() == null ? "" : "(" + p.item() + ")") + " = " + plain(target) + (violation ? "" : " (sát giới hạn, hợp lệ)");
        return new Mutation(label, violation, false, root -> {
            Object arr = BodyPath.get(root, p.array());
            if (!(arr instanceof List<?> l) || l.isEmpty()) {
                return false;
            }
            List<Object> items = (List<Object>) l;
            if ("COUNT".equals(fn)) {
                int want = target.intValue();
                if (want < 0) {
                    return false;
                }
                while (items.size() < want) {
                    items.add(deepCopyValue(items.get(0)));
                }
                while (items.size() > want) {
                    items.remove(items.size() - 1);
                }
                return true;
            }
            Map<String, Object> first = (Map<String, Object>) items.get(0);
            BigDecimal old = toNumber(first.get(p.item()));
            if (old == null) {
                return false;
            }
            BigDecimal updated = old.add(delta);
            first.put(p.item(), isInteger(old) && isInteger(updated) ? (Object) updated.longValueExact() : (Object) updated.doubleValue());
            return true;
        });
    }

    private List<Mutation> unique(Map<String, Object> body) {
        return List.of(new Mutation("gửi lại request lần 2", true, true, root -> true));
    }

    // ---- mutation helpers ----

    private record Mutation(String label, boolean violation, boolean sendTwice, java.util.function.Function<Map<String, Object>, Boolean> apply) {
    }

    private static final class Skip extends RuntimeException {
        Skip(String message) {
            super(message, null, false, false);
        }
    }

    private Mutation set(String field, Object value, boolean violation, String label) {
        return new Mutation(label, violation, false, root -> BodyPath.set(root, field, value instanceof BigDecimal bd
                ? (isInteger(bd) ? (Object) bd.longValue() : (Object) bd.doubleValue()) : value));
    }

    private Mutation remove(List<String> fields, boolean violation, String label) {
        return new Mutation(label, violation, false, root -> {
            fields.forEach(f -> BodyPath.remove(root, f));
            return true;
        });
    }

    // ---- giá trị ----

    private BigDecimal number(Map<String, Object> body, String field) {
        return toNumber(BodyPath.get(body, field));
    }

    private static BigDecimal toNumber(Object value) {
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof String s && s.matches("-?\\d+(\\.\\d+)?")) {
            return new BigDecimal(s);
        }
        return null;
    }

    private static boolean isInteger(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static boolean holds(String op, BigDecimal a, BigDecimal b) {
        int c = a.compareTo(b);
        return switch (op) {
            case "LT" -> c < 0;
            case "LTE" -> c <= 0;
            case "EQ" -> c == 0;
            case "GTE" -> c >= 0;
            default -> c > 0;
        };
    }

    @SuppressWarnings("unchecked")
    private static BigDecimal sum(List<?> list, String item) {
        BigDecimal total = BigDecimal.ZERO;
        for (Object element : list) {
            BigDecimal v = element instanceof Map<?, ?> m ? toNumber(((Map<String, Object>) m).get(item)) : null;
            if (v == null) {
                return null;
            }
            total = total.add(v);
        }
        return total;
    }

    /** Giá trị hợp lệ theo spec để thêm 1 field vào body (enum đầu, min, hoặc mẫu theo kiểu); null nếu không đoán được. */
    private Object sampleValue(Spec spec, String field) {
        FieldRule rule = RuleFieldRef.parse(field).find(spec).orElse(null);
        if (rule == null || rule.location() != ParamLocation.BODY) {
            return null;
        }
        if (rule.enumValues() != null && !rule.enumValues().isEmpty()) {
            return rule.enumValues().get(0);
        }
        if (rule.numeric()) {
            return rule.minimum() != null ? rule.minimum().add(rule.minimumExclusive() ? BigDecimal.ONE : BigDecimal.ZERO).longValue() : 1L;
        }
        if ("boolean".equals(rule.type())) {
            return Boolean.TRUE;
        }
        if (rule.hasFormatOrPattern()) {
            return null; // định dạng/pattern riêng: không đoán được giá trị hợp lệ
        }
        int length = Math.max(rule.minLength() == null ? 1 : rule.minLength(), 1);
        return length > 200 ? null : "x".repeat(length);
    }

    // ---- ngày ----

    private static String dayExpr(int days) {
        return days == 0 ? "{{today}}" : "{{today" + (days > 0 ? "+" : "-") + Math.abs(days) + "d}}";
    }

    private static String nowExpr(int minutes) {
        return minutes == 0 ? "{{now}}" : "{{now" + (minutes > 0 ? "+" : "-") + Math.abs(minutes) + "m}}";
    }

    /** Dịch 1 ngày dạng yyyy-MM-dd hoặc {{today+Nd}} đi {@code days} ngày; rỗng nếu không dịch được. */
    static Optional<String> shiftDate(String text, int days) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = TODAY_TOKEN.matcher(text.trim());
        if (m.matches()) {
            int current = m.group(2) == null ? 0 : Integer.parseInt(m.group(2)) * ("-".equals(m.group(1)) ? -1 : 1);
            return Optional.of(dayExpr(current + days));
        }
        try {
            return Optional.of(LocalDate.parse(text.trim()).plusDays(days).toString());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ---- JSON ----

    private Map<String, Object> parse(String json) {
        try {
            return json == null || json.isBlank() ? new LinkedHashMap<>() : objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String write(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> deepCopy(Map<String, Object> body) {
        return parse(write(body));
    }

    private Object deepCopyValue(Object value) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(value), Object.class);
        } catch (Exception e) {
            return value;
        }
    }

    private static GeneratedTestCase withBody(GeneratedTestCase base, String body, Integer expected, String name) {
        return new GeneratedTestCase(name, base.description(), base.requestHeaders(), body, expected, base.resolvedPath(),
                base.pathParamFallbacks(), base.authOverride(), base.rationale());
    }
}
