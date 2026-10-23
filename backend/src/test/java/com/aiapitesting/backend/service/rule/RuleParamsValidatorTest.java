package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.exception.BusinessRuleException;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.FieldRule;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.ParamLocation;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.Spec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleParamsValidatorTest {

    static FieldRule num(String name, boolean required, String min, String max) {
        return new FieldRule(name, ParamLocation.BODY, required, true, "integer", false, null,
                min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max), null, null, null, null, false, false, false);
    }

    static FieldRule text(String name, boolean required) {
        return new FieldRule(name, ParamLocation.BODY, required, false, "string", false, null, null, null, null, null, null, null, false, false, false);
    }

    static FieldRule array(String name) {
        return new FieldRule(name, ParamLocation.BODY, false, false, "array", false, null, null, null, null, null, null, null, false, false, false);
    }

    static Spec spec(FieldRule... rules) {
        return new Spec("/jobs", true, true, List.of(rules));
    }

    private static RuleParams compare(String left, String op, String right) {
        return new RuleParams(left, op, right, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static RuleParams group(String... fields) {
        return new RuleParams(null, null, null, null, null, List.of(fields), null, null, null, null, null, null, null, null);
    }

    @Test
    void compareFields_normalizesAndChecksFieldsAndTypes() {
        Spec spec = spec(num("salaryMin", true, null, null), num("salaryMax", true, null, null), text("title", true));

        RuleParams ok = RuleParamsValidator.validate(RuleTemplate.COMPARE_FIELDS, compare(" salaryMin ", "lte", "salaryMax"), spec, "POST");
        assertThat(ok.left()).isEqualTo("salaryMin");
        assertThat(ok.op()).isEqualTo("LTE");

        assertThatThrownBy(() -> RuleParamsValidator.validate(RuleTemplate.COMPARE_FIELDS, compare("salaryMin", "LTE", "nope"), spec, "POST"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("nope");
        assertThatThrownBy(() -> RuleParamsValidator.validate(RuleTemplate.COMPARE_FIELDS, compare("salaryMin", "LTE", "salaryMin"), spec, "POST"))
                .hasMessageContaining("khác nhau");
        assertThatThrownBy(() -> RuleParamsValidator.validate(RuleTemplate.COMPARE_FIELDS, compare("salaryMin", "LTE", "title"), spec, "POST"))
                .hasMessageContaining("cùng là số");
        assertThatThrownBy(() -> RuleParamsValidator.validate(RuleTemplate.COMPARE_FIELDS, compare("salaryMin", "BAD", "salaryMax"), spec, "POST"))
                .hasMessageContaining("Phép so sánh");
    }

    @Test
    void groups_needEnoughDistinctExistingFields() {
        Spec spec = spec(text("a", false), text("b", false));
        assertThat(RuleParamsValidator.validate(RuleTemplate.ONLY_ONE, group("a", "b"), spec, "POST").fieldsOrEmpty()).containsExactly("a", "b");
        assertThatThrownBy(() -> RuleParamsValidator.validate(RuleTemplate.OR, group("a"), spec, "POST")).hasMessageContaining("ít nhất 2");
        assertThatThrownBy(() -> RuleParamsValidator.validate(RuleTemplate.OR, group("a", "a"), spec, "POST")).hasMessageContaining("lặp");
        assertThatThrownBy(() -> RuleParamsValidator.validate(RuleTemplate.OR, group("a", "zzz"), spec, "POST")).hasMessageContaining("zzz");
    }

    @Test
    void dateRelative_valueRange_arrayAggregate_unique_validateTheirOwnRules() {
        Spec spec = spec(text("deadline", true), num("years", false, null, null), array("criteria"),
                num("criteria[0].weight", false, null, null), text("name", true));

        RuleParams date = new RuleParams(null, "gte", null, "deadline", null, null, null, null, null, null, null, null, null, null);
        RuleParams normalized = RuleParamsValidator.validate(RuleTemplate.DATE_RELATIVE, date, spec, "POST");
        assertThat(normalized.unit()).isEqualTo("DAY");
        assertThat(normalized.offset()).isZero();

        RuleParams noBound = new RuleParams(null, null, null, "years", null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> RuleParamsValidator.validate(RuleTemplate.VALUE_RANGE, noBound, spec, "POST")).hasMessageContaining("biên");
        RuleParams reversed = new RuleParams(null, null, null, "years", null, null, null, null, BigDecimal.TEN, BigDecimal.ONE, null, null, null, null);
        assertThatThrownBy(() -> RuleParamsValidator.validate(RuleTemplate.VALUE_RANGE, reversed, spec, "POST")).hasMessageContaining("min");

        RuleParams sum = new RuleParams(null, "eq", null, null, null, null, null, null, null, null, "criteria", "weight", "sum", new BigDecimal(100));
        RuleParams sumOk = RuleParamsValidator.validate(RuleTemplate.ARRAY_AGGREGATE, sum, spec, "POST");
        assertThat(sumOk.fn()).isEqualTo("SUM");
        RuleParams notArray = new RuleParams(null, "eq", null, null, null, null, null, null, null, null, "years", "weight", "SUM", BigDecimal.TEN);
        assertThatThrownBy(() -> RuleParamsValidator.validate(RuleTemplate.ARRAY_AGGREGATE, notArray, spec, "POST")).hasMessageContaining("không phải mảng");

        assertThat(RuleParamsValidator.validate(RuleTemplate.UNIQUE, group("name"), spec, "POST").fieldsOrEmpty()).containsExactly("name");
        assertThatThrownBy(() -> RuleParamsValidator.validate(RuleTemplate.UNIQUE, group("name"), spec, "GET")).hasMessageContaining("POST/PUT");
    }

    @Test
    void check_valueRange_duplicateTighterWiderAndDisjoint() {
        Spec spec = spec(num("years", false, "0", "50"));
        RuleParams same = new RuleParams(null, null, null, "years", null, null, null, null, BigDecimal.ZERO, new BigDecimal(50), null, null, null, null);
        RuleParams tighter = new RuleParams(null, null, null, "years", null, null, null, null, BigDecimal.ONE, new BigDecimal(40), null, null, null, null);
        RuleParams wider = new RuleParams(null, null, null, "years", null, null, null, null, BigDecimal.ZERO, new BigDecimal(100), null, null, null, null);
        RuleParams disjoint = new RuleParams(null, null, null, "years", null, null, null, null, new BigDecimal(60), new BigDecimal(70), null, null, null, null);

        assertThat(RuleParamsValidator.check(RuleTemplate.VALUE_RANGE, same, spec).consistency()).isEqualTo(RuleParamsValidator.Consistency.DUPLICATES_SPEC);
        assertThat(RuleParamsValidator.check(RuleTemplate.VALUE_RANGE, tighter, spec).consistency()).isEqualTo(RuleParamsValidator.Consistency.OK);
        assertThat(RuleParamsValidator.check(RuleTemplate.VALUE_RANGE, wider, spec).consistency()).isEqualTo(RuleParamsValidator.Consistency.CONFLICT);
        assertThat(RuleParamsValidator.check(RuleTemplate.VALUE_RANGE, disjoint, spec).consistency()).isEqualTo(RuleParamsValidator.Consistency.CONFLICT);
    }

    @Test
    void check_requiredFieldsMakeRulesDuplicateOrConflict_andMissingFieldMeansStale() {
        Spec spec = spec(text("a", true), text("b", true), text("c", false), num("salaryMin", false, "10", null), num("salaryMax", false, null, "5"));

        RuleParams requires = new RuleParams(null, null, null, "c", null, List.of("a", "b"), null, null, null, null, null, null, null, null);
        assertThat(RuleParamsValidator.check(RuleTemplate.REQUIRES, requires, spec).consistency()).isEqualTo(RuleParamsValidator.Consistency.DUPLICATES_SPEC);
        assertThat(RuleParamsValidator.check(RuleTemplate.ONLY_ONE, group("a", "b"), spec).consistency()).isEqualTo(RuleParamsValidator.Consistency.CONFLICT);
        assertThat(RuleParamsValidator.check(RuleTemplate.OR, group("a", "c"), spec).consistency()).isEqualTo(RuleParamsValidator.Consistency.DUPLICATES_SPEC);
        // salaryMin tối thiểu 10 nhưng salaryMax tối đa 5: luật salaryMin <= salaryMax không bao giờ thoả
        assertThat(RuleParamsValidator.check(RuleTemplate.COMPARE_FIELDS, compare("salaryMin", "LTE", "salaryMax"), spec).consistency())
                .isEqualTo(RuleParamsValidator.Consistency.CONFLICT);

        Spec gone = spec(text("a", true));
        assertThat(RuleParamsValidator.check(RuleTemplate.OR, group("a", "removed"), gone).consistency()).isEqualTo(RuleParamsValidator.Consistency.STALE);
    }
}
