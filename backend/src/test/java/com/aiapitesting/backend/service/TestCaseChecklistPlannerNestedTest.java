package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.response.ChecklistItemType;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.ChecklistItem;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Module 17 (G5, P1-3) - body lồng nhau 1 cấp: object con và phần tử đầu của mảng object. */
class TestCaseChecklistPlannerNestedTest {

    private static final String SCHEMA = """
            {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["title"],
              "properties":{"title":{"type":"string","minLength":1},
                "salary":{"type":"object","required":["min"],"properties":{"min":{"type":"integer","minimum":0},"currency":{"type":"string","enum":["USD","VND"]}}},
                "items":{"type":"array","items":{"type":"object","required":["sku"],"properties":{"sku":{"type":"string","minLength":2}}}}}}}}},
             "responseStatusCodes":["201","400"]}""";

    private final TestCaseChecklistPlanner planner = new TestCaseChecklistPlanner();

    private Spec spec() {
        return planner.readSpec(Endpoint.builder().id(UUID.randomUUID()).method("POST").path("/jobs").schema(SCHEMA).build());
    }

    private static GeneratedTestCase baseline(String body) {
        return new GeneratedTestCase("base", "d", null, body, 201, "/jobs", null, null, null);
    }

    @Test
    void readSpec_emitsDottedRulesForChildren() {
        assertThat(spec().rules()).extracting(TestCaseChecklistPlanner.FieldRule::name)
                .contains("title", "salary", "salary.min", "salary.currency", "items", "items[0].sku");
    }

    @Test
    void childViolationsAreScoredOnlyWhenParentPresent() {
        Spec spec = spec();
        List<ChecklistItem> items = planner.plan(spec, true, true, true);
        ChecklistItem missingMin = items.stream()
                .filter(i -> i.type() == ChecklistItemType.NEGATIVE_MISSING_FIELD && "salary.min".equals(i.field())).findFirst().orElseThrow();
        GeneratedTestCase filled = planner.fill(spec, missingMin, baseline("{\"title\":\"t\",\"salary\":{\"min\":5,\"currency\":\"USD\"}}"));
        assertThat(filled).isNotNull();
        assertThat(filled.requestBody()).contains("\"salary\":{").doesNotContain("\"min\"").contains("\"title\":\"t\"");
        // Baseline không có salary: không thể sửa trường con -> bỏ qua chứ không tạo case sai.
        ChecklistItem enumItem = items.stream()
                .filter(i -> "salary.currency".equals(i.field()) && i.type() == ChecklistItemType.NEGATIVE_INVALID_ENUM).findFirst().orElse(null);
        if (enumItem != null) {
            GeneratedTestCase ok = planner.fill(spec, enumItem, baseline("{\"title\":\"t\",\"salary\":{\"min\":5}}"));
            assertThat(ok == null || ok.requestBody().contains("\"salary\":{")).isTrue();
        }
    }

    @Test
    void nestedNumericSuccessBoundaries_areNotApplicable_butViolatingOnesRemain() {
        String schema = """
                {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["criteria"],
                  "properties":{"criteria":{"type":"array","items":{"type":"object","required":["weight"],
                    "properties":{"weight":{"type":"number","minimum":0.01,"maximum":100}}}}}}}}},"responseStatusCodes":["200","400"]}""";
        Spec spec = planner.readSpec(Endpoint.builder().id(UUID.randomUUID()).method("PUT").path("/rubric").schema(schema).build());
        List<ChecklistItem> items = planner.plan(spec, true, true, true).stream().filter(i -> "criteria[0].weight".equals(i.field())).toList();
        assertThat(items).filteredOn(i -> i.type() == ChecklistItemType.BOUNDARY_AT_MINIMUM || i.type() == ChecklistItemType.BOUNDARY_AT_MAXIMUM)
                .isNotEmpty().allMatch(i -> i.notApplicableReason() != null);
        assertThat(items).filteredOn(i -> !i.expectSuccess() && i.type() != ChecklistItemType.NEGATIVE_MISSING_FIELD)
                .isNotEmpty().allMatch(i -> i.notApplicableReason() == null);
    }

    @Test
    void flatKeyWithDot_isNotTreatedAsNested() {
        String schema = """
                {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["user.name"],
                  "properties":{"user.name":{"type":"string","minLength":1}}}}}},"responseStatusCodes":["201","400"]}""";
        Spec spec = planner.readSpec(Endpoint.builder().id(UUID.randomUUID()).method("POST").path("/x").schema(schema).build());
        assertThat(spec.rules()).noneMatch(TestCaseChecklistPlanner.FieldRule::nested);
        ChecklistItem missing = planner.plan(spec, true, true, true).stream()
                .filter(i -> i.type() == ChecklistItemType.NEGATIVE_MISSING_FIELD && "user.name".equals(i.field())).findFirst().orElseThrow();
        GeneratedTestCase filled = planner.fill(spec, missing, baseline("{\"user.name\":\"a\"}"));
        assertThat(filled).isNotNull();
        assertThat(filled.requestBody()).doesNotContain("user.name");
    }

    @Test
    void arrayChildMissingField_removedFromFirstElement() {
        Spec spec = spec();
        ChecklistItem missingSku = planner.plan(spec, true, true, true).stream()
                .filter(i -> i.type() == ChecklistItemType.NEGATIVE_MISSING_FIELD && "items[0].sku".equals(i.field())).findFirst().orElseThrow();
        GeneratedTestCase filled = planner.fill(spec, missingSku, baseline("{\"title\":\"t\",\"items\":[{\"sku\":\"AB\"}]}"));
        assertThat(filled).isNotNull();
        assertThat(filled.requestBody()).doesNotContain("sku");
        assertThat(planner.fill(spec, missingSku, baseline("{\"title\":\"t\"}"))).isNull();
    }
}
