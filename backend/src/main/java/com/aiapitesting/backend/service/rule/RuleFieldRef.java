package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.service.TestCaseChecklistPlanner;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.FieldRule;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.ParamLocation;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.Spec;

import java.util.Locale;
import java.util.Optional;

/**
 * 17q G3 - tham chiếu field trong tham số luật: "salaryMin", "criteria[0].weight" (body), "query:status", "header:X-Tenant", "path:id".
 * Không có tiền tố = body (đa số luật nghiệp vụ nằm trong body).
 */
public record RuleFieldRef(ParamLocation location, String name) {

    public static RuleFieldRef parse(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String trimmed = ref.trim();
        int colon = trimmed.indexOf(':');
        if (colon > 0) {
            String prefix = trimmed.substring(0, colon).toLowerCase(Locale.ROOT);
            String name = trimmed.substring(colon + 1).trim();
            ParamLocation location = switch (prefix) {
                case "body" -> ParamLocation.BODY;
                case "query" -> ParamLocation.QUERY;
                case "header" -> ParamLocation.HEADER;
                case "path" -> ParamLocation.PATH;
                default -> null;
            };
            return location == null || name.isEmpty() ? null : new RuleFieldRef(location, name);
        }
        return new RuleFieldRef(ParamLocation.BODY, trimmed);
    }

    /** Luật của spec ứng với tham chiếu này (khớp tên + vị trí; header không phân biệt hoa thường). */
    public Optional<FieldRule> find(Spec spec) {
        return spec.rules().stream()
                .filter(r -> r.location() == location)
                .filter(r -> location == ParamLocation.HEADER ? r.name().equalsIgnoreCase(name) : r.name().equals(name))
                .findFirst();
    }

    /** Tên hiển thị trong câu luật/lý do: body thì chỉ tên, vị trí khác thì kèm tiền tố. */
    public String label() {
        return location == ParamLocation.BODY ? name : location.name().toLowerCase(Locale.ROOT) + ":" + name;
    }

    static boolean isNumeric(FieldRule rule) {
        return rule.numeric();
    }

    static boolean isText(FieldRule rule) {
        return rule.type() == null || "string".equals(rule.type());
    }

    static TestCaseChecklistPlanner.ParamLocation body() {
        return ParamLocation.BODY;
    }
}
