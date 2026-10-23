package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.Spec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.aiapitesting.backend.service.rule.RuleParamsValidatorTest.array;
import static com.aiapitesting.backend.service.rule.RuleParamsValidatorTest.num;
import static com.aiapitesting.backend.service.rule.RuleParamsValidatorTest.spec;
import static com.aiapitesting.backend.service.rule.RuleParamsValidatorTest.text;
import static org.assertj.core.api.Assertions.assertThat;

class RuleTestGeneratorTest {

    private final RuleTestGenerator generator = new RuleTestGenerator(new TestCaseChecklistPlanner());

    private GeneratedTestCase baseline(String body) {
        return new GeneratedTestCase("Positive", "gốc", Map.of(), body, 201, "/jobs", Map.of(), TestCaseAuthOverride.DEFAULT, "r");
    }

    private RuleTestGenerator.Result run(RuleTemplate template, RuleParams params, Spec spec, String body) {
        return generator.generate(template, params, spec, baseline(body), RuleSentence.of(template, params));
    }

    private static RuleParams p(String left, String op, String right, String field, List<String> fields, Integer offset, String unit,
                                BigDecimal min, BigDecimal max, String array, String item, String fn, BigDecimal value) {
        return new RuleParams(left, op, right, field, null, fields, offset, unit, min, max, array, item, fn, value);
    }

    @Test
    void compareFields_numeric_generatesViolationAndBoundary() {
        Spec spec = spec(num("salaryMin", true, null, null), num("salaryMax", true, null, null));
        RuleParams rule = p("salaryMin", "LTE", "salaryMax", null, null, null, null, null, null, null, null, null, null);

        var result = run(RuleTemplate.COMPARE_FIELDS, rule, spec, "{\"salaryMin\":10,\"salaryMax\":20}");

        assertThat(result.reason()).isNull();
        assertThat(result.drafts()).hasSize(2);
        var violation = result.drafts().stream().filter(RuleTestGenerator.Draft::violation).findFirst().orElseThrow();
        assertThat(violation.testCase().requestBody()).contains("\"salaryMin\":21").contains("\"salaryMax\":20");
        assertThat(violation.testCase().expectedStatus()).isEqualTo(400);
        var boundary = result.drafts().stream().filter(d -> !d.violation()).findFirst().orElseThrow();
        assertThat(boundary.testCase().requestBody()).contains("\"salaryMin\":20");
        assertThat(boundary.testCase().expectedStatus()).isEqualTo(201);
    }

    @Test
    void compareFields_baselineAlreadyViolating_isSkippedWithReason() {
        Spec spec = spec(num("a", true, null, null), num("b", true, null, null));
        var result = run(RuleTemplate.COMPARE_FIELDS, p("a", "LTE", "b", null, null, null, null, null, null, null, null, null, null), spec,
                "{\"a\":30,\"b\":20}");
        assertThat(result.drafts()).isEmpty();
        assertThat(result.reason()).contains("đã vi phạm");
    }

    @Test
    void compareFields_draftThatBreaksSpecBoundIsDropped() {
        // a có maximum 20: a = b + 1 = 21 vi phạm spec nên không dùng làm bằng chứng cho luật
        Spec spec = spec(num("a", true, null, "20"), num("b", true, null, null));
        var result = run(RuleTemplate.COMPARE_FIELDS, p("a", "LTE", "b", null, null, null, null, null, null, null, null, null, null), spec,
                "{\"a\":10,\"b\":20}");
        assertThat(result.drafts()).extracting(RuleTestGenerator.Draft::violation).containsExactly(false);
    }

    @Test
    void groups_requires_allOrNone_onlyOne_or() {
        Spec spec = spec(text("a", false), text("b", false), text("c", false));

        var requires = run(RuleTemplate.REQUIRES, new RuleParams(null, null, null, "a", null, List.of("b"), null, null, null, null, null, null, null, null),
                spec, "{\"a\":\"x\",\"b\":\"y\"}");
        assertThat(requires.drafts()).singleElement().satisfies(d -> assertThat(d.testCase().requestBody()).doesNotContain("\"b\""));

        var allOrNone = run(RuleTemplate.ALL_OR_NONE, p(null, null, null, null, List.of("a", "b"), null, null, null, null, null, null, null, null),
                spec, "{\"a\":\"x\",\"b\":\"y\"}");
        assertThat(allOrNone.drafts()).singleElement().satisfies(d -> assertThat(d.testCase().requestBody()).contains("\"a\"").doesNotContain("\"b\""));

        var onlyOne = run(RuleTemplate.ONLY_ONE, p(null, null, null, null, List.of("a", "b"), null, null, null, null, null, null, null, null),
                spec, "{\"a\":\"x\"}");
        assertThat(onlyOne.drafts()).hasSize(2); // thêm field thứ 2 + bỏ cả nhóm
        assertThat(onlyOne.drafts().get(0).testCase().requestBody()).contains("\"a\"").contains("\"b\"");

        var zeroOrOne = run(RuleTemplate.ZERO_OR_ONE, p(null, null, null, null, List.of("a", "b"), null, null, null, null, null, null, null, null),
                spec, "{\"a\":\"x\"}");
        assertThat(zeroOrOne.drafts()).hasSize(1);

        var or = run(RuleTemplate.OR, p(null, null, null, null, List.of("a", "b"), null, null, null, null, null, null, null, null),
                spec, "{\"a\":\"x\",\"c\":\"keep\"}");
        assertThat(or.drafts()).singleElement().satisfies(d -> assertThat(d.testCase().requestBody()).doesNotContain("\"a\"").contains("\"c\""));
    }

    @Test
    void dateRelative_usesPlaceholdersNotFixedDates() {
        Spec spec = spec(text("deadline", true));
        var notPast = run(RuleTemplate.DATE_RELATIVE, p(null, "GTE", null, "deadline", null, 0, "DAY", null, null, null, null, null, null), spec,
                "{\"deadline\":\"{{today+30d}}\"}");
        assertThat(notPast.drafts()).hasSize(2);
        assertThat(notPast.drafts().get(0).testCase().requestBody()).contains("{{today-1d}}");
        assertThat(notPast.drafts().get(1).testCase().requestBody()).contains("{{today}}");

        var hours = run(RuleTemplate.DATE_RELATIVE, p(null, "GTE", null, "deadline", null, 2, "HOUR", null, null, null, null, null, null), spec,
                "{\"deadline\":\"{{now+3h}}\"}");
        assertThat(hours.drafts()).singleElement().satisfies(d -> assertThat(d.testCase().requestBody()).contains("{{now+115m}}"));
    }

    @Test
    void valueRange_generatesJustOutsideAndOnTheEdges() {
        Spec spec = spec(num("years", false, null, null));
        var result = run(RuleTemplate.VALUE_RANGE, p(null, null, null, "years", null, null, null, BigDecimal.ONE, new BigDecimal(40), null, null, null, null),
                spec, "{\"years\":5}");
        assertThat(result.drafts()).extracting(d -> d.testCase().requestBody())
                .containsExactly("{\"years\":0}", "{\"years\":1}", "{\"years\":41}", "{\"years\":40}");
    }

    @Test
    void arrayAggregate_sumCountDistinct() {
        Spec spec = spec(array("criteria"), num("criteria[0].weight", false, null, null));
        String body = "{\"criteria\":[{\"weight\":60},{\"weight\":40}]}";

        var sum = run(RuleTemplate.ARRAY_AGGREGATE, p(null, "EQ", null, null, null, null, null, null, null, "criteria", "weight", "SUM", new BigDecimal(100)),
                spec, body);
        assertThat(sum.drafts()).singleElement().satisfies(d -> assertThat(d.testCase().requestBody()).contains("\"weight\":61"));

        var count = run(RuleTemplate.ARRAY_AGGREGATE, p(null, "LTE", null, null, null, null, null, null, null, "criteria", null, "COUNT", new BigDecimal(2)),
                spec, body);
        assertThat(count.drafts()).singleElement().satisfies(d -> assertThat(d.testCase().requestBody()).contains("\"weight\":60").contains("\"weight\":40"));

        var distinct = run(RuleTemplate.ARRAY_AGGREGATE, p(null, null, null, null, null, null, null, null, null, "criteria", "weight", "DISTINCT", null),
                spec, body);
        assertThat(distinct.drafts()).singleElement().satisfies(d -> assertThat(d.testCase().requestBody().split("\"weight\":60", -1)).hasSize(3));
    }

    @Test
    void unique_sendsTwice_andNoBaselineOrNoBodyExplainsWhy() {
        Spec spec = spec(text("name", true));
        var unique = run(RuleTemplate.UNIQUE, p(null, null, null, null, List.of("name"), null, null, null, null, null, null, null, null), spec,
                "{\"name\":\"a\"}");
        assertThat(unique.drafts()).singleElement().satisfies(d -> {
            assertThat(d.sendTwice()).isTrue();
            assertThat(d.violation()).isTrue();
        });

        var noBaseline = generator.generate(RuleTemplate.UNIQUE, p(null, null, null, null, List.of("name"), null, null, null, null, null, null, null, null),
                spec, null, "x");
        assertThat(noBaseline.reason()).contains("test gốc");
        var noBody = generator.generate(RuleTemplate.OR, p(null, null, null, null, List.of("a", "b"), null, null, null, null, null, null, null, null),
                new Spec("/x", false, false, List.of()), baseline(null), "x");
        assertThat(noBody.reason()).contains("body");
    }

    @Test
    void shiftDate_literalAndPlaceholder() {
        assertThat(RuleTestGenerator.shiftDate("2026-12-31", 1)).contains("2027-01-01");
        assertThat(RuleTestGenerator.shiftDate("{{today+30d}}", 1)).contains("{{today+31d}}");
        assertThat(RuleTestGenerator.shiftDate("{{today}}", -1)).contains("{{today-1d}}");
        assertThat(RuleTestGenerator.shiftDate("không phải ngày", 1)).isEmpty();
    }
}
