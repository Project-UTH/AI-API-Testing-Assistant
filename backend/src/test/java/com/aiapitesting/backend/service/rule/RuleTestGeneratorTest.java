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
    void arrayAggregate_sumEqualsTriesBothSidesOfTheLimit_soAnOneSidedCheckLikeGreaterThanIsCaught() {
        Spec spec = spec(array("criteria"), num("criteria[0].weight", false, null, null));
        String body = "{\"criteria\":[{\"weight\":60},{\"weight\":40}]}";

        var sum = run(RuleTemplate.ARRAY_AGGREGATE, p(null, "EQ", null, null, null, null, null, null, null, "criteria", "weight", "SUM", new BigDecimal(100)),
                spec, body);

        // tổng 101 và tổng 99 đều phải bị từ chối (lỗi H10 'total > 100' chỉ bị lộ ở tổng 99); tổng 100 chính là test gốc nên không lặp lại
        assertThat(sum.drafts()).hasSize(2).allSatisfy(d -> assertThat(d.violation()).isTrue());
        assertThat(sum.drafts()).anySatisfy(d -> assertThat(d.testCase().requestBody()).contains("\"weight\":61"));
        assertThat(sum.drafts()).anySatisfy(d -> assertThat(d.testCase().requestBody()).contains("\"weight\":59"));
    }

    @Test
    void arrayAggregate_atMostAndAtLeast_haveAViolationAndAnAcceptedEdge() {
        Spec spec = spec(array("criteria"), num("criteria[0].weight", false, null, null));
        String body = "{\"criteria\":[{\"weight\":10},{\"weight\":20}]}";

        var lte = run(RuleTemplate.ARRAY_AGGREGATE, p(null, "LTE", null, null, null, null, null, null, null, "criteria", "weight", "SUM", new BigDecimal(50)),
                spec, body);
        var overLimit = lte.drafts().stream().filter(RuleTestGenerator.Draft::violation).findFirst().orElseThrow();
        assertThat(overLimit.testCase().requestBody()).contains("\"weight\":31");
        assertThat(overLimit.testCase().expectedStatus()).isEqualTo(400);
        var atLimit = lte.drafts().stream().filter(d -> !d.violation()).findFirst().orElseThrow();
        assertThat(atLimit.testCase().requestBody()).contains("\"weight\":30"); // tổng 50 đúng bằng giới hạn phải được chấp nhận
        assertThat(atLimit.testCase().expectedStatus()).isEqualTo(201);

        var gte = run(RuleTemplate.ARRAY_AGGREGATE, p(null, "GTE", null, null, null, null, null, null, null, "criteria", "weight", "SUM", new BigDecimal(30)),
                spec, "{\"criteria\":[{\"weight\":10},{\"weight\":25}]}");
        assertThat(gte.drafts().stream().filter(RuleTestGenerator.Draft::violation)).singleElement()
                .satisfies(d -> assertThat(d.testCase().requestBody()).contains("\"weight\":4")); // tổng 29
        assertThat(gte.drafts().stream().filter(d -> !d.violation())).singleElement()
                .satisfies(d -> assertThat(d.testCase().requestBody()).contains("\"weight\":5")); // tổng 30
    }

    @Test
    void arrayAggregate_count_sumCountDistinct() {
        Spec spec = spec(array("criteria"), num("criteria[0].weight", false, null, null));
        String body = "{\"criteria\":[{\"weight\":60},{\"weight\":40}]}";

        // đếm <= 2 trên mảng đang có đúng 2 phần tử: test gốc đã ở giới hạn nên chỉ còn phía vi phạm (3 phần tử)
        var count = run(RuleTemplate.ARRAY_AGGREGATE, p(null, "LTE", null, null, null, null, null, null, null, "criteria", null, "COUNT", new BigDecimal(2)),
                spec, body);
        assertThat(count.drafts()).singleElement().satisfies(d -> assertThat(d.testCase().requestBody()).contains("\"weight\":60").contains("\"weight\":40"));

        // đếm >= 1: vi phạm là 0 phần tử (không thao tác được nên có thể bị bỏ), biên hợp lệ là đúng 1 phần tử
        var atLeast = run(RuleTemplate.ARRAY_AGGREGATE, p(null, "GTE", null, null, null, null, null, null, null, "criteria", null, "COUNT", new BigDecimal(1)),
                spec, body);
        assertThat(atLeast.drafts().stream().filter(d -> !d.violation())).singleElement()
                .satisfies(d -> assertThat(d.testCase().requestBody()).contains("\"weight\":60").doesNotContain("\"weight\":40"));

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
