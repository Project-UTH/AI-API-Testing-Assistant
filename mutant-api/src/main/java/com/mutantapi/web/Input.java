package com.mutantapi.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.mutantapi.mutant.Mutants;
import com.mutantapi.mutant.RuleContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Doc + kiem tra 1 JSON object bang tay (khong dung @Valid) de tung buoc kiem tra co the bi tat boi mutant.
 * Cac DTO co chu thich (@Size, @DecimalMin...) chi de khai bao rang buoc cho OpenAPI - gia tri that dat o day,
 * nen co test doi chieu 2 ben (SpecMatchesBehaviourTest).
 */
public final class Input {

    private final JsonNode body;
    private final String prefix;
    private final List<String> errors;

    private Input(JsonNode body, String prefix, List<String> errors) {
        this.body = body;
        this.prefix = prefix;
        this.errors = errors;
    }

    public static Input of(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "INVALID_BODY", "Body phai la 1 JSON object");
        }
        return new Input(body, "", new ArrayList<>());
    }

    /** Doc 1 phan tu con (vd criteria[0]) dung chung danh sach loi, thong bao co tien to. */
    public Input child(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            errors.add(prefix + name + " phai la object");
            return null;
        }
        return new Input(node, prefix + name + ".", errors);
    }

    public JsonNode raw(String field) {
        return body.get(field);
    }

    public boolean present(String field) {
        JsonNode n = body.get(field);
        return n != null && !n.isNull();
    }

    /** true neu cong tac quy tac dang TAT kiem tra (field, kind) o endpoint hien tai (luon false o ban chuan). */
    public boolean off(String field, String kind) {
        return Mutants.ruleOff(RuleContext.endpoint(), (prefix + field).replaceAll("\\[\\d+]", "[]"), kind);
    }

    /** Truong vang mat / null: bao loi "bat buoc" tru khi cong tac tuong ung dang tat. */
    private boolean missingIsError(String field, boolean required, boolean isNullNode) {
        return required && !off(field, isNullNode ? "notNull" : "required");
    }

    public String string(String field, boolean required, boolean notBlank, int minLen, int maxLen) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            if (missingIsError(field, required, n != null)) {
                errors.add(prefix + field + " la bat buoc");
            }
            return null;
        }
        if (!n.isTextual()) {
            if (!off(field, "type")) {
                errors.add(prefix + field + " phai la chuoi");
            }
            return null;
        }
        String value = n.asText();
        if (notBlank && value.trim().isEmpty()) {
            if (!off(field, "notBlank")) {
                errors.add(prefix + field + " khong duoc de trong");
                return value;
            }
        } else if (!(notBlank && minLen <= 1) && value.codePointCount(0, value.length()) < minLen && !off(field, "minLength")) {
            errors.add(prefix + field + " phai co it nhat " + minLen + " ky tu");
        }
        if (value.codePointCount(0, value.length()) > maxLen && !off(field, "maxLength")) {
            errors.add(prefix + field + " toi da " + maxLen + " ky tu");
        }
        return value;
    }

    public BigDecimal decimal(String field, boolean required, BigDecimal min, BigDecimal max) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            if (missingIsError(field, required, n != null)) {
                errors.add(prefix + field + " la bat buoc");
            }
            return null;
        }
        if (!n.isNumber()) {
            if (!off(field, "type")) {
                errors.add(prefix + field + " phai la so");
            }
            return null;
        }
        BigDecimal value = n.decimalValue();
        if (min != null && value.compareTo(min) < 0 && !off(field, "min")) {
            errors.add(prefix + field + " phai >= " + min.toPlainString());
        }
        if (max != null && value.compareTo(max) > 0 && !off(field, "max")) {
            errors.add(prefix + field + " phai <= " + max.toPlainString());
        }
        return value;
    }

    public Integer integer(String field, boolean required, int min) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            if (missingIsError(field, required, n != null)) {
                errors.add(prefix + field + " la bat buoc");
            }
            return null;
        }
        if (!n.isIntegralNumber()) {
            if (!off(field, "type")) {
                errors.add(prefix + field + " phai la so nguyen");
            }
            return null;
        }
        int value = n.intValue();
        if (value < min && !off(field, "min")) {
            errors.add(prefix + field + " phai >= " + min);
        }
        return value;
    }

    public Boolean bool(String field, boolean required) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            if (missingIsError(field, required, n != null)) {
                errors.add(prefix + field + " la bat buoc");
            }
            return null;
        }
        if (!n.isBoolean()) {
            if (!off(field, "type")) {
                errors.add(prefix + field + " phai la true/false");
            }
            return null;
        }
        return n.booleanValue();
    }

    public void addError(String message) {
        errors.add(message);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** Neu co loi validate thi nem ApiException voi ma HTTP chi dinh (400 chuan; mutant co the doi). */
    public void check(int failStatus) {
        if (!errors.isEmpty()) {
            // Chi doi ma HTTP, giu nguyen ma loi VALIDATION_ERROR - dung nhu loi that "401 thay vi 400" cua API tuyen dung.
            throw new ApiException(failStatus, "VALIDATION_ERROR", "Du lieu khong hop le", List.copyOf(errors));
        }
    }
}
