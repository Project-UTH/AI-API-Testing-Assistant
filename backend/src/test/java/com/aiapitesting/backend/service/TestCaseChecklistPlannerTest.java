package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.response.ChecklistItemType;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.ChecklistItem;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.Spec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Module 15h - checklist EP/BVA tất định, chấm theo nội dung và tự bù (0 token, không gọi AI). */
class TestCaseChecklistPlannerTest {

    /** email (required), age (required, 18..60), name (2..5 ký tự), role (enum) trong body + query minPrice (minimum 0). */
    private static final String SCHEMA = """
            {"summary":"Dang ky",
             "parameters":[{"name":"minPrice","in":"query","schema":{"type":"integer","minimum":0}}],
             "requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["email","age"],
               "properties":{"email":{"type":"string"},
                             "age":{"type":"integer","minimum":18,"maximum":60},
                             "name":{"type":"string","minLength":2,"maxLength":5},
                             "role":{"type":"string","enum":["USER","ADMIN"]}}}}}},
             "responseStatusCodes":["201","400"]}""";

    private static final String BASE_BODY = "{\"email\":\"an@gmail.com\",\"age\":25,\"name\":\"abc\",\"role\":\"USER\"}";

    private final TestCaseChecklistPlanner planner = new TestCaseChecklistPlanner();
    private Spec spec;

    @BeforeEach
    void setUp() {
        spec = planner.readSpec(endpoint(SCHEMA));
    }

    private Endpoint endpoint(String schema) {
        return Endpoint.builder().id(UUID.randomUUID()).method("POST").path("/users").schema(schema).build();
    }

    private GeneratedTestCase tc(String body, int status) {
        return tc(body, status, "/users?minPrice={{minPrice}}", Map.of("minPrice", "10"));
    }

    private GeneratedTestCase tc(String body, int status, String resolvedPath, Map<String, String> fallbacks) {
        return new GeneratedTestCase("n", "d", Map.of(), body, status, resolvedPath, fallbacks, null, null);
    }

    private ChecklistItem item(List<ChecklistItem> items, ChecklistItemType type, String field) {
        return items.stream().filter(i -> i.type() == type && field.equals(i.field())).findFirst().orElseThrow();
    }

    private List<ChecklistItem> all() {
        return planner.plan(spec, true, true, true);
    }

    @Test
    void plan_standardChecklistPerCategory() {
        List<ChecklistItem> items = all();

        // Positive=3: dữ liệu hợp lệ + 2 giá trị enum của role (USER, ADMIN - mỗi giá trị enum 1 lớp tương đương);
        // Negative=7: thiếu (email, age) + null (email, age) + sai kiểu (chỉ age, minPrice - số; KHÔNG có trường chuỗi vì
        // Jackson ép số thành chuỗi, Module 17 1.2) + ngoài enum (role); Boundary=11: age min/max x2, name minLength/maxLength x2, minPrice min x2
        // + chuỗi rỗng cho email (string không khai minLength/format - biên ngầm 15i).
        assertThat(items).hasSize(21);
        assertThat(items).filteredOn(i -> i.type() == ChecklistItemType.POSITIVE_ENUM_VALUE)
                .extracting(ChecklistItem::value).containsExactly("USER", "ADMIN");
        assertThat(items.get(0).type()).isEqualTo(ChecklistItemType.POSITIVE_BASELINE);
        assertThat(items).filteredOn(i -> i.type() == ChecklistItemType.NEGATIVE_MISSING_FIELD)
                .extracting(ChecklistItem::field).containsExactly("email", "age");
        assertThat(items).filteredOn(i -> i.type() == ChecklistItemType.NEGATIVE_NULL_FIELD)
                .extracting(ChecklistItem::field).containsExactly("email", "age");
        assertThat(items).filteredOn(i -> i.type() == ChecklistItemType.NEGATIVE_WRONG_TYPE)
                .extracting(ChecklistItem::field).containsExactlyInAnyOrder("age", "minPrice");
        assertThat(item(items, ChecklistItemType.BOUNDARY_BELOW_MINIMUM, "age").value()).isEqualTo("17");
        assertThat(item(items, ChecklistItemType.BOUNDARY_ABOVE_MAXIMUM, "age").value()).isEqualTo("61");
        assertThat(item(items, ChecklistItemType.BOUNDARY_ABOVE_MAX_LENGTH, "name").value()).isEqualTo("aaaaaa");
        assertThat(item(items, ChecklistItemType.BOUNDARY_BELOW_MINIMUM, "minPrice").location())
                .isEqualTo(TestCaseChecklistPlanner.ParamLocation.QUERY);
        assertThat(item(items, ChecklistItemType.NEGATIVE_INVALID_ENUM, "role").value())
                .isEqualTo(TestCaseChecklistPlanner.INVALID_ENUM_VALUE);
        assertThat(item(items, ChecklistItemType.BOUNDARY_EMPTY_STRING, "email").expectSuccess()).isTrue();
        // Field có ràng buộc khai rõ thì không sinh thêm biên ngầm cho cùng chiều đó.
        assertThat(items).noneMatch(i -> i.type() == ChecklistItemType.BOUNDARY_ZERO);
        assertThat(items).noneMatch(i -> i.type() == ChecklistItemType.BOUNDARY_EMPTY_STRING && !"email".equals(i.field()));
        // Mục nào cũng ghi rõ kỳ vọng (2xx, 400 hoặc lỗi 4xx cho id không tồn tại).
        assertThat(items).allMatch(i -> i.description().contains("2xx") || i.description().contains("400")
                || i.description().contains("4xx"));
    }

    @Test
    void plan_onlyTickedGroups() {
        assertThat(planner.plan(spec, false, true, false)).hasSize(7)
                .allMatch(i -> i.type().name().startsWith("NEGATIVE_"));
        assertThat(planner.plan(spec, true, false, false)).hasSize(3); // dữ liệu hợp lệ + 2 giá trị enum
    }

    @Test
    void plan_minLengthZero_hasNoBelowItem() {
        Spec s = planner.readSpec(endpoint("""
                {"requestBody":{"content":{"application/json":{"schema":{"type":"object",
                  "properties":{"note":{"type":"string","minLength":0}}}}}}}"""));

        assertThat(planner.plan(s, false, false, true)).extracting(ChecklistItem::type)
                .containsExactly(ChecklistItemType.BOUNDARY_AT_MIN_LENGTH);
    }

    @Test
    void plan_nonJsonBody_bodyItemsNotApplicable_paramItemsStillApplicable() {
        Spec s = planner.readSpec(endpoint("""
                {"parameters":[{"name":"page","in":"query","schema":{"type":"integer","minimum":1}}],
                 "requestBody":{"content":{"multipart/form-data":{"schema":{"type":"object","required":["file"],
                   "properties":{"file":{"type":"string"}}}}}}}"""));

        List<ChecklistItem> items = planner.plan(s, false, true, true);
        assertThat(item(items, ChecklistItemType.NEGATIVE_MISSING_FIELD, "file").notApplicableReason()).isNotNull();
        assertThat(item(items, ChecklistItemType.BOUNDARY_AT_MINIMUM, "page").notApplicableReason()).isNull();
    }

    @Test
    void satisfies_missingFieldWithOtherFieldsValid_counts() {
        ChecklistItem missingEmail = item(all(), ChecklistItemType.NEGATIVE_MISSING_FIELD, "email");

        assertThat(planner.satisfies(spec, missingEmail, tc("{\"age\":25}", 400))).isTrue();
    }

    @Test
    void satisfies_nameSaysMissingButBodyStillHasField_doesNotCount() {
        ChecklistItem missingEmail = item(all(), ChecklistItemType.NEGATIVE_MISSING_FIELD, "email");
        GeneratedTestCase lying = new GeneratedTestCase("Negative - Thieu email", "d", Map.of(), BASE_BODY, 400,
                "/users?minPrice={{minPrice}}", Map.of("minPrice", "10"), null, null);

        assertThat(planner.satisfies(spec, missingEmail, lying)).isFalse();
    }

    @Test
    void satisfies_twoFaultsAtOnce_doesNotCount() {
        ChecklistItem missingEmail = item(all(), ChecklistItemType.NEGATIVE_MISSING_FIELD, "email");

        // Thiếu email VÀ age=5 dưới minimum - API trả 400 không chứng minh được nó kiểm email.
        assertThat(planner.satisfies(spec, missingEmail, tc("{\"age\":5}", 400))).isFalse();
    }

    @Test
    void satisfies_boundaryValues_atAndBeyondBound() {
        List<ChecklistItem> items = all();

        assertThat(planner.satisfies(spec, item(items, ChecklistItemType.BOUNDARY_AT_MINIMUM, "age"),
                tc("{\"email\":\"a@b.com\",\"age\":18}", 201))).isTrue();
        assertThat(planner.satisfies(spec, item(items, ChecklistItemType.BOUNDARY_BELOW_MINIMUM, "age"),
                tc("{\"email\":\"a@b.com\",\"age\":17}", 400))).isTrue();
        // Đúng giá trị nhưng kỳ vọng sai chiều (17 mà kỳ vọng 2xx) - không tính.
        assertThat(planner.satisfies(spec, item(items, ChecklistItemType.BOUNDARY_BELOW_MINIMUM, "age"),
                tc("{\"email\":\"a@b.com\",\"age\":17}", 201))).isFalse();
        // Giá trị giữa khoảng không phải biên.
        assertThat(planner.satisfies(spec, item(items, ChecklistItemType.BOUNDARY_AT_MAXIMUM, "age"),
                tc("{\"email\":\"a@b.com\",\"age\":40}", 201))).isFalse();
    }

    @Test
    void satisfies_queryBoundaryWrittenLiterallyInUrl_counts() {
        ChecklistItem below = item(all(), ChecklistItemType.BOUNDARY_BELOW_MINIMUM, "minPrice");

        assertThat(planner.satisfies(spec, below,
                tc("{\"email\":\"a@b.com\",\"age\":25}", 400, "/users?minPrice=-1", Map.of()))).isTrue();
    }

    @Test
    void satisfies_invalidEnum_counts() {
        ChecklistItem invalidEnum = item(all(), ChecklistItemType.NEGATIVE_INVALID_ENUM, "role");

        assertThat(planner.satisfies(spec, invalidEnum, tc("{\"email\":\"a@b.com\",\"age\":25,\"role\":\"GUEST\"}", 400))).isTrue();
    }

    @Test
    void isValidBaseline_requiresSuccessAndNoViolation() {
        assertThat(planner.isValidBaseline(spec, tc(BASE_BODY, 201))).isTrue();
        assertThat(planner.isValidBaseline(spec, tc("{\"email\":\"a@b.com\"}", 201))).isFalse(); // thiếu age
        assertThat(planner.isValidBaseline(spec, tc(BASE_BODY, 400))).isFalse();
    }

    @Test
    void fill_everyApplicableItem_producesCaseThatSatisfiesItAndChangesExactlyOnePlace() {
        GeneratedTestCase baseline = tc(BASE_BODY, 201);

        for (ChecklistItem item : all()) {
            if (item.type() == ChecklistItemType.POSITIVE_BASELINE) {
                continue; // case gốc chính là Positive, không cần bù
            }
            GeneratedTestCase filled = planner.fill(spec, item, baseline);
            assertThat(filled).as(item.description()).isNotNull();
            assertThat(planner.satisfies(spec, item, filled)).as(item.description()).isTrue();
            assertThat(filled.expectedStatus()).isEqualTo(item.expectSuccess() ? 201 : 400);
            assertThat(filled.rationale()).isEqualTo(item.rationale());
        }
    }

    @Test
    void fill_queryItem_writesLiteralIntoUrlAndDropsFallback() {
        ChecklistItem below = item(all(), ChecklistItemType.BOUNDARY_BELOW_MINIMUM, "minPrice");

        GeneratedTestCase filled = planner.fill(spec, below, tc(BASE_BODY, 201));

        assertThat(filled.resolvedPath()).isEqualTo("/users?minPrice=-1");
        assertThat(filled.pathParamFallbacks()).doesNotContainKey("minPrice");
    }

    @Test
    void fill_pathItem_replacesSegmentAtTemplatePosition() {
        Endpoint ep = Endpoint.builder().id(UUID.randomUUID()).method("PATCH").path("/pet/{petId}/stock").schema("""
                {"parameters":[{"name":"petId","in":"path","required":true,"schema":{"type":"integer","minimum":1}}]}""").build();
        Spec s = planner.readSpec(ep);
        ChecklistItem below = item(planner.plan(s, false, false, true), ChecklistItemType.BOUNDARY_BELOW_MINIMUM, "petId");

        GeneratedTestCase filled = planner.fill(s, below, tc(null, 200, "/pet/{{petId}}/stock", Map.of("petId", "5")));

        assertThat(filled.resolvedPath()).isEqualTo("/pet/0/stock");
        assertThat(planner.satisfies(s, below, filled)).isTrue();
    }

    @Test
    void satisfies_nullOnRequiredField_isNotCountedAsMissingField() {
        // "email": null là vi phạm null (field không nullable), KHÁC "thiếu hẳn email" - không được tính đạt mục thiếu field.
        ChecklistItem missingEmail = item(all(), ChecklistItemType.NEGATIVE_MISSING_FIELD, "email");

        assertThat(planner.satisfies(spec, missingEmail, tc("{\"email\":null,\"age\":25}", 400))).isFalse();
        assertThat(planner.isValidBaseline(spec, tc("{\"email\":null,\"age\":25}", 201))).isFalse();
    }

    /**
     * Đồng bộ với Tier 1: với case kỳ vọng thành công, "hợp lệ làm gốc" của planner phải trùng đúng
     * kết luận RELIABLE của TestCaseReliabilityValidator - 2 nơi dùng chung SpecValueRules nên không
     * được có case nào 1 bên nói đúng, bên kia nói "Cần xem lại".
     */
    @Test
    void isValidBaseline_agreesWithTier1ForSuccessCases() {
        TestCaseReliabilityValidator tier1 = new TestCaseReliabilityValidator();
        Endpoint ep = endpoint(SCHEMA);
        List<String> bodies = List.of(
                BASE_BODY,
                "{\"email\":\"an@gmail.com\"}",                          // thiếu age
                "{\"email\":null,\"age\":25}",                           // null không nullable
                "{\"email\":\"an@gmail.com\",\"age\":\"25\"}",           // số gửi dạng chuỗi
                "{\"email\":\"an@gmail.com\",\"age\":25.5}",             // integer có phần thập phân
                "{\"email\":\"an@gmail.com\",\"age\":17}",               // dưới minimum
                "{\"email\":\"an@gmail.com\",\"age\":25,\"name\":\"abcdef\"}", // quá maxLength
                "{\"email\":\"an@gmail.com\",\"age\":25,\"role\":\"GUEST\"}",  // ngoài enum
                "{\"email\":\"an@gmail.com\",\"age\":25,\"name\":123}"); // string nhận số
        for (String body : bodies) {
            GeneratedTestCase candidate = tc(body, 201);
            boolean tier1Reliable = tier1.validate(ep, candidate, com.aiapitesting.backend.entity.TestCaseSource.AI_GENERATED)
                    .status() == com.aiapitesting.backend.entity.TestCaseReliability.RELIABLE;
            assertThat(planner.isValidBaseline(spec, candidate)).as(body).isEqualTo(tier1Reliable);
        }
    }

    // ---- Module 15i - biên ngầm theo kiểu dữ liệu + checklist Security ----

    /** Đúng spec POST /api/products của shop-api-target: không field nào khai minimum/maximum/minLength. */
    private static final String PRODUCTS_SCHEMA = """
            {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["name","price","stock"],
              "properties":{"name":{"type":"string"},"price":{"type":"number"},
                            "stock":{"type":"integer","format":"int32"}}}}}}}""";

    private static final String PRODUCTS_BODY = "{\"name\":\"Tao\",\"price\":99000,\"stock\":10}";

    @Test
    void plan_specWithoutDeclaredBounds_boundaryChecklistUsesImplicitBounds() {
        Spec s = planner.readSpec(endpoint(PRODUCTS_SCHEMA));

        List<ChecklistItem> items = planner.plan(s, false, false, true);

        // Trước 15i: 0 mục (AI tự bịa 4 case biên rơi xuống "đề xuất thêm"). Giờ: stock 6 (lớn nhất/nhỏ nhất int32 +
        // vượt 2 đầu + 0 + -1) + price 2 + name 1.
        assertThat(items).hasSize(9);
        assertThat(item(items, ChecklistItemType.BOUNDARY_AT_TYPE_MINIMUM, "stock").value()).isEqualTo("-2147483648");
        assertThat(item(items, ChecklistItemType.BOUNDARY_BELOW_TYPE_MINIMUM, "stock").value()).isEqualTo("-2147483649");
        assertThat(item(items, ChecklistItemType.BOUNDARY_BELOW_TYPE_MINIMUM, "stock").expectSuccess()).isFalse();
        assertThat(item(items, ChecklistItemType.BOUNDARY_AT_TYPE_MAXIMUM, "stock").value()).isEqualTo("2147483647");
        assertThat(item(items, ChecklistItemType.BOUNDARY_ABOVE_TYPE_MAXIMUM, "stock").value()).isEqualTo("2147483648");
        assertThat(item(items, ChecklistItemType.BOUNDARY_ABOVE_TYPE_MAXIMUM, "stock").expectSuccess()).isFalse();
        assertThat(item(items, ChecklistItemType.BOUNDARY_ZERO, "price").expectSuccess()).isTrue();
        assertThat(item(items, ChecklistItemType.BOUNDARY_NEGATIVE_NUMBER, "stock").value()).isEqualTo("-1");
        assertThat(item(items, ChecklistItemType.BOUNDARY_EMPTY_STRING, "name").value()).isEmpty();
        // price là number không có format -> không có giới hạn kiểu.
        assertThat(items).noneMatch(i -> "price".equals(i.field()) && i.type() == ChecklistItemType.BOUNDARY_AT_TYPE_MAXIMUM);
    }

    @Test
    void plan_pathParamInt64_overflowBothEndsAndNotFoundBoundaries() {
        Endpoint ep = Endpoint.builder().id(UUID.randomUUID()).method("DELETE").path("/api/products/{id}").schema("""
                {"parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer","format":"int64"}}]}""").build();

        List<ChecklistItem> items = planner.plan(planner.readSpec(ep), false, false, true);

        // Trước đây path chỉ có mục "vượt int64" - BVA chuẩn vẫn phải thử 0, số âm, lớn nhất của kiểu; vì id định
        // danh tài nguyên thật nên các giá trị này kỳ vọng lỗi 4xx (404), không phải 2xx.
        assertThat(items).extracting(ChecklistItem::type).containsExactlyInAnyOrder(
                ChecklistItemType.BOUNDARY_AT_TYPE_MAXIMUM, ChecklistItemType.BOUNDARY_ABOVE_TYPE_MAXIMUM,
                ChecklistItemType.BOUNDARY_BELOW_TYPE_MINIMUM, ChecklistItemType.BOUNDARY_ZERO,
                ChecklistItemType.BOUNDARY_NEGATIVE_NUMBER);
        assertThat(items).allMatch(i -> !i.expectSuccess());
        assertThat(item(items, ChecklistItemType.BOUNDARY_ABOVE_TYPE_MAXIMUM, "id").value()).isEqualTo("9223372036854775808");
        assertThat(items).filteredOn(ChecklistItem::notFoundExpected)
                .extracting(ChecklistItem::value).containsExactlyInAnyOrder("0", "-1", "9223372036854775807");
        // Tier 1 không thấy lý do lỗi của id không tồn tại - các mục này được tin theo bộ chấm checklist.
        assertThat(items).filteredOn(ChecklistItem::tier1CannotSee).hasSize(3);

        // Bù: id không tồn tại kỳ vọng 404, case đạt đúng mục của nó.
        Spec s = planner.readSpec(ep);
        GeneratedTestCase baseline = tc(null, 200, "/api/products/{{id}}", Map.of("id", "5"));
        for (ChecklistItem item : items) {
            GeneratedTestCase filled = planner.fill(s, item, baseline);
            assertThat(filled).as(item.description()).isNotNull();
            assertThat(planner.satisfies(s, item, filled)).as(item.description()).isTrue();
            assertThat(filled.expectedStatus()).isEqualTo(item.notFoundExpected() ? 404 : 400);
        }
    }

    @Test
    void plan_exclusiveMinimum_atBoundIsInvalidAndNextValueIsFirstValid() {
        Spec s = planner.readSpec(endpoint("""
                {"requestBody":{"content":{"application/json":{"schema":{"type":"object",
                  "properties":{"score":{"type":"integer","minimum":0,"exclusiveMinimum":true,"maximum":10,"exclusiveMaximum":true}}}}}}}"""));

        List<ChecklistItem> items = planner.plan(s, false, false, true);

        // minimum 0 exclusive: 0 KHÔNG hợp lệ, 1 là giá trị hợp lệ nhỏ nhất; maximum 10 exclusive: 10 không hợp lệ, 9 hợp lệ.
        assertThat(item(items, ChecklistItemType.BOUNDARY_AT_MINIMUM, "score").value()).isEqualTo("1");
        assertThat(item(items, ChecklistItemType.BOUNDARY_BELOW_MINIMUM, "score").value()).isEqualTo("0");
        assertThat(item(items, ChecklistItemType.BOUNDARY_AT_MAXIMUM, "score").value()).isEqualTo("9");
        assertThat(item(items, ChecklistItemType.BOUNDARY_ABOVE_MAXIMUM, "score").value()).isEqualTo("10");
        // Đã khai minimum nên không sinh thêm biên ngầm 0/-1 cho field này.
        assertThat(items).noneMatch(i -> i.type() == ChecklistItemType.BOUNDARY_ZERO);

        GeneratedTestCase atZero = tc("{\"score\":0}", 400, "/users", Map.of());
        assertThat(planner.satisfies(s, item(items, ChecklistItemType.BOUNDARY_BELOW_MINIMUM, "score"), atZero)).isTrue();
        // Giá trị đúng bằng biên exclusive không được coi là dữ liệu hợp lệ làm gốc.
        assertThat(planner.isValidBaseline(s, tc("{\"score\":0}", 201, "/users", Map.of()))).isFalse();
        assertThat(planner.isValidBaseline(s, tc("{\"score\":1}", 201, "/users", Map.of()))).isTrue();
    }

    @Test
    void exclusiveBounds_agreeWithTier1() {
        String schema = """
                {"requestBody":{"content":{"application/json":{"schema":{"type":"object",
                  "properties":{"score":{"type":"integer","minimum":0,"exclusiveMinimum":true}}}}}}}""";
        TestCaseReliabilityValidator tier1 = new TestCaseReliabilityValidator();
        Endpoint ep = endpoint(schema);
        Spec s = planner.readSpec(ep);
        for (String score : List.of("-1", "0", "1")) {
            GeneratedTestCase candidate = tc("{\"score\":" + score + "}", 201, "/users", Map.of());
            boolean tier1Reliable = tier1.validate(ep, candidate, com.aiapitesting.backend.entity.TestCaseSource.AI_GENERATED)
                    .status() == com.aiapitesting.backend.entity.TestCaseReliability.RELIABLE;
            assertThat(planner.isValidBaseline(s, candidate)).as(score).isEqualTo(tier1Reliable);
        }
    }

    @Test
    void plan_requiredQueryAndHeader_missingItemsAndFill() {
        Endpoint ep = Endpoint.builder().id(UUID.randomUUID()).method("PATCH").path("/api/products/{id}/stock").schema("""
                {"parameters":[
                  {"name":"id","in":"path","required":true,"schema":{"type":"integer","format":"int64"}},
                  {"name":"quantity","in":"query","required":true,"schema":{"type":"integer","minimum":1}},
                  {"name":"operation","in":"query","required":true,"schema":{"type":"string","enum":["increase","decrease"]}},
                  {"name":"X-Trace","in":"header","required":true,"schema":{"type":"string"}},
                  {"name":"note","in":"query","required":false,"schema":{"type":"string"}}]}""").build();
        Spec s = planner.readSpec(ep);

        List<ChecklistItem> items = planner.plan(s, false, true, false);

        // Path không bao giờ "thiếu"; tham số tuỳ chọn (note) bỏ qua; query + header bắt buộc mỗi cái 1 mục.
        assertThat(items).filteredOn(i -> i.type() == ChecklistItemType.NEGATIVE_MISSING_FIELD)
                .extracting(ChecklistItem::field).containsExactlyInAnyOrder("quantity", "operation", "X-Trace");
        assertThat(items).filteredOn(i -> i.type() == ChecklistItemType.NEGATIVE_MISSING_FIELD)
                .allMatch(ChecklistItem::tier1CannotSee);

        GeneratedTestCase baseline = new GeneratedTestCase("Base", "d", Map.of("X-Trace", "abc"), null, 200,
                "/api/products/{{id}}/stock?quantity={{quantity}}&operation={{operation}}",
                Map.of("id", "1", "quantity", "5", "operation", "increase"), null, null);
        assertThat(planner.isValidBaseline(s, baseline)).isTrue();

        ChecklistItem missingQuantity = item(items, ChecklistItemType.NEGATIVE_MISSING_FIELD, "quantity");
        GeneratedTestCase filled = planner.fill(s, missingQuantity, baseline);
        assertThat(filled.resolvedPath()).isEqualTo("/api/products/{{id}}/stock?operation={{operation}}");
        assertThat(filled.pathParamFallbacks()).doesNotContainKey("quantity");
        assertThat(planner.satisfies(s, missingQuantity, filled)).isTrue();

        ChecklistItem missingHeader = item(items, ChecklistItemType.NEGATIVE_MISSING_FIELD, "X-Trace");
        GeneratedTestCase noHeader = planner.fill(s, missingHeader, baseline);
        assertThat(noHeader.requestHeaders()).doesNotContainKey("X-Trace");
        assertThat(planner.satisfies(s, missingHeader, noHeader)).isTrue();

        // Case gốc thiếu tham số bắt buộc không còn được coi là dữ liệu hợp lệ làm gốc.
        assertThat(planner.isValidBaseline(s, new GeneratedTestCase("B", "d", Map.of("X-Trace", "abc"), null, 200,
                "/api/products/{{id}}/stock?operation={{operation}}", Map.of("id", "1", "operation", "increase"), null, null)))
                .isFalse();
    }

    @Test
    void plan_enumValues_eachValueIsOwnPositiveClass_andFillWritesRealNumberForNumericEnum() {
        Spec s = planner.readSpec(endpoint("""
                {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["level"],
                  "properties":{"level":{"type":"integer","enum":[1,2,3]}}}}}}}"""));

        List<ChecklistItem> items = planner.plan(s, true, false, false);

        assertThat(items).filteredOn(i -> i.type() == ChecklistItemType.POSITIVE_ENUM_VALUE)
                .extracting(ChecklistItem::value).containsExactly("1", "2", "3");
        ChecklistItem three = item(items, ChecklistItemType.POSITIVE_ENUM_VALUE, "level");
        GeneratedTestCase filled = planner.fill(s, items.get(items.size() - 1), tc("{\"level\":1}", 201, "/users", Map.of()));
        assertThat(filled.requestBody()).isEqualTo("{\"level\":3}"); // số thật, không phải chuỗi "3"
        assertThat(planner.satisfies(s, items.get(items.size() - 1), filled)).isTrue();
        assertThat(planner.satisfies(s, three, tc("{\"level\":2}", 201, "/users", Map.of()))).isFalse();
    }

    @Test
    void satisfies_implicitBounds_andOverflowIsSingleViolation() {
        Spec s = planner.readSpec(endpoint(PRODUCTS_SCHEMA));
        List<ChecklistItem> items = planner.plan(s, false, false, true);

        assertThat(planner.satisfies(s, item(items, ChecklistItemType.BOUNDARY_ZERO, "price"),
                tc("{\"name\":\"Tao\",\"price\":0,\"stock\":10}", 201, "/users", Map.of()))).isTrue();
        assertThat(planner.satisfies(s, item(items, ChecklistItemType.BOUNDARY_EMPTY_STRING, "name"),
                tc("{\"name\":\"\",\"price\":1,\"stock\":10}", 201, "/users", Map.of()))).isTrue();
        assertThat(planner.satisfies(s, item(items, ChecklistItemType.BOUNDARY_ABOVE_TYPE_MAXIMUM, "stock"),
                tc("{\"name\":\"Tao\",\"price\":1,\"stock\":2147483648}", 400, "/users", Map.of()))).isTrue();
        // Tràn int32 không được coi là dữ liệu hợp lệ làm gốc.
        assertThat(planner.isValidBaseline(s, tc("{\"name\":\"Tao\",\"price\":1,\"stock\":2147483648}", 201, "/users", Map.of())))
                .isFalse();
    }

    @Test
    void overflow_agreesWithTier1() {
        TestCaseReliabilityValidator tier1 = new TestCaseReliabilityValidator();
        Endpoint ep = endpoint(PRODUCTS_SCHEMA);
        Spec s = planner.readSpec(ep);
        for (String stock : List.of("2147483647", "2147483648", "-2147483648", "-2147483649")) {
            GeneratedTestCase candidate = tc("{\"name\":\"Tao\",\"price\":1,\"stock\":" + stock + "}", 201, "/users", Map.of());
            boolean tier1Reliable = tier1.validate(ep, candidate, com.aiapitesting.backend.entity.TestCaseSource.AI_GENERATED)
                    .status() == com.aiapitesting.backend.entity.TestCaseReliability.RELIABLE;
            assertThat(planner.isValidBaseline(s, candidate)).as(stock).isEqualTo(tier1Reliable);
        }
    }

    @Test
    void fill_implicitAndNewNegativeItems_eachSatisfiesItsItem() {
        Spec s = planner.readSpec(endpoint(PRODUCTS_SCHEMA));
        GeneratedTestCase baseline = tc(PRODUCTS_BODY, 201, "/users", Map.of());

        for (ChecklistItem item : planner.plan(s, false, true, true)) {
            GeneratedTestCase filled = planner.fill(s, item, baseline);
            assertThat(filled).as(item.description()).isNotNull();
            assertThat(planner.satisfies(s, item, filled)).as(item.description()).isTrue();
        }
    }

    @Test
    void planSecurity_requiresAuth_twoItemsGradedByAuthOverride() {
        List<ChecklistItem> items = planner.planSecurity(true);
        ChecklistItem missing = item2(items, ChecklistItemType.SECURITY_MISSING_TOKEN);
        ChecklistItem invalid = item2(items, ChecklistItemType.SECURITY_INVALID_TOKEN);
        GeneratedTestCase noToken = new GeneratedTestCase("Security - Thieu token", "d", Map.of(), null, 401, "/users",
                Map.of(), com.aiapitesting.backend.entity.TestCaseAuthOverride.NONE, null);

        assertThat(items).hasSize(2).allMatch(i -> i.notApplicableReason() == null);
        assertThat(planner.satisfies(spec, missing, noToken)).isTrue();
        assertThat(planner.satisfies(spec, invalid, noToken)).isFalse();

        // Bù: copy case gốc, đổi authOverride + 401, bỏ header Authorization AI lỡ tự thêm.
        GeneratedTestCase base = new GeneratedTestCase("Đề xuất thêm - Injection - x", "d", Map.of("Authorization", "Bearer x"),
                BASE_BODY, 400, "/users", Map.of(), com.aiapitesting.backend.entity.TestCaseAuthOverride.DEFAULT, null);
        GeneratedTestCase filled = planner.fill(spec, invalid, base);
        assertThat(filled.authOverride()).isEqualTo(com.aiapitesting.backend.entity.TestCaseAuthOverride.INVALID);
        assertThat(filled.expectedStatus()).isEqualTo(401);
        assertThat(filled.requestHeaders()).doesNotContainKey("Authorization");
    }

    @Test
    void planSecurity_endpointWithoutAuth_itemsNotApplicable() {
        assertThat(planner.planSecurity(false)).hasSize(2).allMatch(i -> i.notApplicableReason() != null);
    }

    private ChecklistItem item2(List<ChecklistItem> items, ChecklistItemType type) {
        return items.stream().filter(i -> i.type() == type).findFirst().orElseThrow();
    }

    @Test
    void readSpec_unreadableSchema_noRulesOnlyPositive() {
        Spec s = planner.readSpec(endpoint("not json"));

        assertThat(planner.plan(s, true, true, true)).extracting(ChecklistItem::type)
                .containsExactly(ChecklistItemType.POSITIVE_BASELINE);
    }

    // ---- Module 15j - Coverage Gap Analysis ----

    @Test
    void planAll_isBasicChecklistPlusSecurity() {
        assertThat(planner.planAll(spec, true)).hasSize(all().size() + 2)
                .last().satisfies(i -> assertThat(i.type()).isEqualTo(ChecklistItemType.SECURITY_INVALID_TOKEN));
    }

    @Test
    void key_isStableAndUnique_longValuesWrittenAsLength() {
        List<ChecklistItem> items = planner.planAll(spec, true);

        assertThat(items.stream().map(TestCaseChecklistPlanner::key).distinct().count()).isEqualTo(items.size());
        assertThat(TestCaseChecklistPlanner.key(item(items, ChecklistItemType.BOUNDARY_BELOW_MINIMUM, "age")))
                .isEqualTo("BOUNDARY_BELOW_MINIMUM|BODY|age|17");
        // Lập lại từ cùng spec -> cùng khoá (frontend gửi khoá cũ vẫn khớp đúng mục).
        assertThat(planner.planAll(planner.readSpec(endpoint(SCHEMA)), true).stream().map(TestCaseChecklistPlanner::key).toList())
                .isEqualTo(items.stream().map(TestCaseChecklistPlanner::key).toList());

        Spec longText = planner.readSpec(endpoint("""
                {"requestBody":{"content":{"application/json":{"schema":{"type":"object",
                  "properties":{"bio":{"type":"string","maxLength":500}}}}}}}"""));
        ChecklistItem above = item(planner.plan(longText, false, false, true), ChecklistItemType.BOUNDARY_ABOVE_MAX_LENGTH, "bio");
        assertThat(TestCaseChecklistPlanner.key(above)).isEqualTo("BOUNDARY_ABOVE_MAX_LENGTH|BODY|bio|len:501");
    }

    /**
     * Lỗi thật gặp khi import spec của 1 API Spring bên thứ ba: {@code @Size(min = 8)} không khai max được springdoc
     * ghi thành {@code maxLength: 2147483647} → trước đây {@code "a".repeat(2147483647)} gây OutOfMemoryError (và
     * {@code n + 1} tràn số), làm sập cả trang Coverage của project. Giờ ghi mục không áp dụng kèm lý do.
     */
    @Test
    void plan_frameworkDefaultMaxLengthIntMax_noOutOfMemory_markedNotApplicable() {
        Spec huge = planner.readSpec(endpoint("""
                {"requestBody":{"content":{"application/json":{"schema":{"type":"object",
                  "properties":{"password":{"type":"string","minLength":8,"maxLength":2147483647}}}}}}}"""));

        List<ChecklistItem> items = planner.plan(huge, false, false, true);

        ChecklistItem max = item(items, ChecklistItemType.BOUNDARY_AT_MAX_LENGTH, "password");
        assertThat(max.notApplicableReason()).contains("2147483647").contains("không giới hạn");
        assertThat(items).noneMatch(i -> i.type() == ChecklistItemType.BOUNDARY_ABOVE_MAX_LENGTH);
        // minLength = 8 hợp lệ vẫn được kiểm bình thường.
        assertThat(item(items, ChecklistItemType.BOUNDARY_AT_MIN_LENGTH, "password").notApplicableReason()).isNull();
    }

    @Test
    void explainNearMiss_extraViolation_reportedWithFieldNames() {
        ChecklistItem missingEmail = item(all(), ChecklistItemType.NEGATIVE_MISSING_FIELD, "email");
        // Thiếu cả email lẫn age - API trả 400 không chứng minh được nó kiểm 'email'.
        GeneratedTestCase missingBoth = tc("{\"name\":\"abc\"}", 400);

        assertThat(planner.satisfies(spec, missingEmail, missingBoth)).isFalse();
        assertThat(planner.explainNearMiss(spec, missingEmail, missingBoth, false))
                .contains("thiếu 'age'").contains("1 lỗi");
    }

    @Test
    void explainNearMiss_rightValueWrongExpectation_reportedAsExpectationMismatch() {
        ChecklistItem atMinimum = item(all(), ChecklistItemType.BOUNDARY_AT_MINIMUM, "age");
        // age = 18 đúng biên hợp lệ nhưng case kỳ vọng lỗi - đúng giá trị, sai kỳ vọng.
        GeneratedTestCase expectsError = tc("{\"email\":\"an@gmail.com\",\"age\":18}", 400);

        assertThat(planner.explainNearMiss(spec, atMinimum, expectsError, false))
                .contains("kỳ vọng 400").contains("thành công (2xx)");
    }

    @Test
    void explainNearMiss_unrelatedOrSatisfyingCase_returnsNull() {
        ChecklistItem missingEmail = item(all(), ChecklistItemType.NEGATIVE_MISSING_FIELD, "email");

        assertThat(planner.explainNearMiss(spec, missingEmail, tc(BASE_BODY, 201), false)).isNull();
        assertThat(planner.explainNearMiss(spec, missingEmail, tc("{\"age\":25}", 400), false)).isNull();
    }

    /** Case Negative copy từ case gốc vẫn mang role=USER - đang thử chuyện khác, không phải bằng chứng cho mục enum. */
    @Test
    void explainNearMiss_caseTestingSomethingElseThatCarriesTheValue_isNotANearMiss() {
        ChecklistItem roleUser = all().stream()
                .filter(i -> i.type() == ChecklistItemType.POSITIVE_ENUM_VALUE && "USER".equals(i.value()))
                .findFirst().orElseThrow();
        GeneratedTestCase missingEmailWithRoleUser = tc("{\"age\":25,\"name\":\"abc\",\"role\":\"USER\"}", 400);

        assertThat(planner.explainNearMiss(spec, roleUser, missingEmailWithRoleUser, false)).isNull();
        // Cùng giá trị, kỳ vọng thành công nhưng thiếu email -> đúng là "gần đạt" (case Positive làm hỏng dữ liệu).
        assertThat(planner.explainNearMiss(spec, roleUser, tc("{\"age\":25,\"role\":\"USER\"}", 201), false))
                .contains("thiếu 'email'");
    }

    @Test
    void explainNearMiss_securityCaseExpectingWrongStatus() {
        ChecklistItem missingToken = item2(planner.planSecurity(true), ChecklistItemType.SECURITY_MISSING_TOKEN);
        GeneratedTestCase noTokenExpects403 = new GeneratedTestCase("n", "d", Map.of(), BASE_BODY, 403,
                "/users", Map.of(), com.aiapitesting.backend.entity.TestCaseAuthOverride.NONE, null);

        assertThat(planner.explainNearMiss(spec, missingToken, noTokenExpects403, true)).contains("403").contains("401");
    }

    @Test
    void covers_caseWithoutTokenOnAuthEndpoint_countsOnlyForSecurityItems() {
        ChecklistItem missingEmail = item(all(), ChecklistItemType.NEGATIVE_MISSING_FIELD, "email");
        GeneratedTestCase noTokenMissingEmail = new GeneratedTestCase("n", "d", Map.of(), "{\"age\":25}", 401,
                "/users?minPrice={{minPrice}}", Map.of("minPrice", "10"), com.aiapitesting.backend.entity.TestCaseAuthOverride.NONE, null);

        // Endpoint yêu cầu xác thực: 401 do thiếu token, không phải do thiếu email.
        assertThat(planner.covers(spec, missingEmail, noTokenMissingEmail, true)).isFalse();
        assertThat(planner.explainNearMiss(spec, missingEmail, noTokenMissingEmail, true)).isNull();
        // Endpoint không yêu cầu xác thực: thiếu token không phải lỗi, chấm như satisfies().
        assertThat(planner.covers(spec, missingEmail, noTokenMissingEmail, false))
                .isEqualTo(planner.satisfies(spec, missingEmail, noTokenMissingEmail));
    }

    @Test
    void toCandidate_readsStoredJsonMaps_brokenJsonBecomesEmpty() {
        com.aiapitesting.backend.entity.TestCase stored = com.aiapitesting.backend.entity.TestCase.builder()
                .name("n").expectedStatus(201).requestBody(BASE_BODY).resolvedPath("/users?minPrice={{minPrice}}")
                .pathParamFallbacks("{\"minPrice\":\"10\"}").requestHeaders("khong phai json").build();

        GeneratedTestCase candidate = planner.toCandidate(stored);

        assertThat(candidate.pathParamFallbacks()).containsEntry("minPrice", "10");
        assertThat(candidate.requestHeaders()).isEmpty();
        assertThat(planner.satisfies(spec, item2(all(), ChecklistItemType.POSITIVE_BASELINE), candidate)).isTrue();
    }

    // ---- Module 15j - thử bù (attemptFill): có case gốc, hoặc dựng thẳng từ đặc tả ----

    private static final String ID_ONLY_SCHEMA = """
            {"parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer","format":"int64"}}],
             "responseStatusCodes":["204","404"]}""";

    private Spec specAt(String path, String schema) {
        return planner.readSpec(Endpoint.builder().id(UUID.randomUUID()).method("DELETE").path(path).schema(schema).build());
    }

    @Test
    void attemptFill_noBaselineButEndpointHasOnlyTheTestedInput_buildsErrorCaseFromSpec() {
        Spec idOnly = specAt("/products/{id}", ID_ONLY_SCHEMA);
        ChecklistItem idZero = item(planner.plan(idOnly, false, false, true), ChecklistItemType.BOUNDARY_ZERO, "id");

        TestCaseChecklistPlanner.FillAttempt attempt = planner.attemptFill(idOnly, idZero, null);

        assertThat(attempt.blockedReason()).isNull();
        assertThat(attempt.fromSpecOnly()).isTrue();
        assertThat(attempt.filled().resolvedPath()).isEqualTo("/products/0");
        assertThat(attempt.filled().expectedStatus()).isEqualTo(404);
        assertThat(attempt.filled().requestBody()).isNull();
    }

    @Test
    void attemptFill_noBaselineAndEndpointHasOtherRequiredInput_isBlockedNotInvented() {
        // email VÀ age đều bắt buộc: bỏ riêng email thì case trống còn thiếu cả age (lỗi thứ 2) - không bịa giá trị age.
        ChecklistItem missingEmail = item(all(), ChecklistItemType.NEGATIVE_MISSING_FIELD, "email");

        TestCaseChecklistPlanner.FillAttempt attempt = planner.attemptFill(spec, missingEmail, null);

        assertThat(attempt.filled()).isNull();
        assertThat(attempt.blockedReason()).isEqualTo(TestCaseChecklistPlanner.NO_BASELINE_REASON);
    }

    @Test
    void attemptFill_noBaselineAndOnlyRequiredField_buildsNegativeCasesFromSpec() {
        Spec single = specAt("/users", """
                {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["email"],
                  "properties":{"email":{"type":"string","format":"email"}}}}}}}""");
        List<ChecklistItem> items = planner.plan(single, false, true, false);

        assertThat(planner.attemptFill(single, item(items, ChecklistItemType.NEGATIVE_MISSING_FIELD, "email"), null).filled().requestBody())
                .isEqualTo("{}");
        assertThat(planner.attemptFill(single, item(items, ChecklistItemType.NEGATIVE_NULL_FIELD, "email"), null).filled().requestBody())
                .isEqualTo("{\"email\":null}");
    }

    @Test
    void attemptFill_noBaseline_neverBuildsSuccessExpectedItemsOrPositive() {
        Spec idOnly = specAt("/products/{id}", ID_ONLY_SCHEMA);
        ChecklistItem positive = item2(planner.plan(idOnly, true, false, false), ChecklistItemType.POSITIVE_BASELINE);
        // Spec không cho biết API trả mã nào khi thành công và dữ liệu thật nào được chấp nhận.
        Spec withZero = specAt("/products", """
                {"parameters":[{"name":"qty","in":"query","schema":{"type":"integer"}}]}""");
        ChecklistItem qtyZero = item(planner.plan(withZero, false, false, true), ChecklistItemType.BOUNDARY_ZERO, "qty");

        assertThat(planner.attemptFill(idOnly, positive, null).blockedReason()).isEqualTo(TestCaseChecklistPlanner.NO_BASELINE_REASON);
        assertThat(qtyZero.expectSuccess()).isTrue();
        assertThat(planner.attemptFill(withZero, qtyZero, null).filled()).isNull();
    }

    @Test
    void attemptFill_securityItemFromSpec_onlyWhenPathHasNoUnresolvedParam() {
        ChecklistItem missingToken = item2(planner.planSecurity(true), ChecklistItemType.SECURITY_MISSING_TOKEN);
        Spec noParams = specAt("/orders", "{\"responseStatusCodes\":[\"200\"]}");
        Spec withPathParam = specAt("/orders/{id}", ID_ONLY_SCHEMA);

        TestCaseChecklistPlanner.FillAttempt ok = planner.attemptFill(noParams, missingToken, null);
        assertThat(ok.fromSpecOnly()).isTrue();
        assertThat(ok.filled().expectedStatus()).isEqualTo(401);
        assertThat(ok.filled().authOverride()).isEqualTo(com.aiapitesting.backend.entity.TestCaseAuthOverride.NONE);
        // {id} chưa có giá trị thật - gọi "/orders/{id}" nguyên văn không phải 1 case chạy được.
        assertThat(planner.attemptFill(withPathParam, missingToken, null).blockedReason())
                .isEqualTo(TestCaseChecklistPlanner.NO_BASELINE_REASON);
    }

    @Test
    void attemptFill_withBaseline_copiesFromBaselineAndIsNotFromSpecOnly() {
        ChecklistItem missingEmail = item(all(), ChecklistItemType.NEGATIVE_MISSING_FIELD, "email");

        TestCaseChecklistPlanner.FillAttempt attempt = planner.attemptFill(spec, missingEmail, tc(BASE_BODY, 201));

        assertThat(attempt.fromSpecOnly()).isFalse();
        assertThat(attempt.filled().requestBody()).doesNotContain("email").contains("age");
    }

    @Test
    void attemptFill_baselineExistsButNothingWorks_reportsCannotBuildNotMissingBaseline() {
        Spec s = specAt("/users/{id}", """
                {"parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer","format":"int64"}}],
                 "requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["name"],
                   "properties":{"name":{"type":"string","format":"text"}}}}}}}""");
        ChecklistItem idZero = item(planner.plan(s, false, false, true), ChecklistItemType.BOUNDARY_ZERO, "id");
        // Case gốc có đường dẫn khác cấu trúc endpoint (số segment lệch) - không thay được vị trí {id}.
        GeneratedTestCase oddBaseline = new GeneratedTestCase("n", "d", Map.of(), "{\"name\":\"a\"}", 200, "/other", Map.of(), null, null);

        TestCaseChecklistPlanner.FillAttempt attempt = planner.attemptFill(s, idZero, oddBaseline);

        assertThat(attempt.filled()).isNull();
        assertThat(attempt.blockedReason()).isEqualTo(TestCaseChecklistPlanner.CANNOT_BUILD_REASON);
    }

    @Test
    void isUsableBaseline_excludesCasesThatDropTheTokenOnAuthEndpoints() {
        GeneratedTestCase noToken = new GeneratedTestCase("n", "d", Map.of(), BASE_BODY, 201,
                "/users?minPrice={{minPrice}}", Map.of("minPrice", "10"), com.aiapitesting.backend.entity.TestCaseAuthOverride.NONE, null);

        assertThat(planner.isUsableBaseline(spec, tc(BASE_BODY, 201), true)).isTrue();
        assertThat(planner.isUsableBaseline(spec, noToken, true)).isFalse();
        assertThat(planner.isUsableBaseline(spec, noToken, false)).isTrue();
        assertThat(planner.isUsableBaseline(spec, tc(BASE_BODY, 400), true)).isFalse();
    }
}
