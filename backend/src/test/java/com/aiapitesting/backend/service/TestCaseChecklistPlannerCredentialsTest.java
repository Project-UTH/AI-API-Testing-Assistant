package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.response.ChecklistItemType;
import com.aiapitesting.backend.dto.response.CoverageScope;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.ChecklistItem;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module 17 (G2) - mục "sai mật khẩu" CHỈ cho endpoint đăng nhập thật, và gỡ ca biên kỳ vọng thành công dễ gây Fail giả
 * (phát hiện khi đo 17o) nhưng không gỡ oan ca có giá trị (biên mật khẩu của đăng ký, id from/to).
 */
class TestCaseChecklistPlannerCredentialsTest {

    private static final String LOGIN_SCHEMA = """
            {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["email","password"],
              "properties":{"email":{"type":"string","minLength":1},"password":{"type":"string","minLength":1}}}}}},
             "responseStatusCodes":["200","400","401"]}""";

    /** Đăng ký: cùng email + mật khẩu (minLength 8) nhưng KHÔNG khai 401. */
    private static final String REGISTER_SCHEMA = """
            {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["email","password"],
              "properties":{"email":{"type":"string","minLength":1},"password":{"type":"string","minLength":8,"maxLength":72}}}}}},
             "responseStatusCodes":["201","400","409"]}""";

    private static final String JOB_SCHEMA = """
            {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["title"],
              "properties":{"title":{"type":"string","minLength":1,"maxLength":200},
                            "salaryMin":{"type":"number"},"salaryMax":{"type":"number"}}}}}},
             "responseStatusCodes":["201","400"]}""";

    private static final String TRANSFER_SCHEMA = """
            {"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["fromAccountId","toAccountId"],
              "properties":{"fromAccountId":{"type":"integer"},"toAccountId":{"type":"integer"},
                            "total":{"type":"integer"},"token":{"type":"integer"},"lowStock":{"type":"integer"}}}}}},
             "responseStatusCodes":["200","400"]}""";

    private final TestCaseChecklistPlanner planner = new TestCaseChecklistPlanner();

    private Endpoint endpoint(String method, String path, String schema) {
        return Endpoint.builder().id(UUID.randomUUID()).method(method).path(path).schema(schema).build();
    }

    private List<ChecklistItem> scoped(Endpoint endpoint) {
        return planner.planScoped(planner.readSpec(endpoint), false, CoverageScope.FULL, planner.hintsOf(endpoint));
    }

    private static ChecklistItem item(List<ChecklistItem> items, ChecklistItemType type, String field) {
        return items.stream().filter(i -> i.type() == type && field.equals(i.field())).findFirst().orElseThrow();
    }

    private static boolean hasWrongCredentials(List<ChecklistItem> items) {
        return items.stream().anyMatch(i -> i.type() == ChecklistItemType.SECURITY_WRONG_CREDENTIALS);
    }

    @Test
    void loginEndpoint_getsWrongCredentialsItem_onlyWhenNoTokenRequired() {
        Endpoint login = endpoint("POST", "/auth/login", LOGIN_SCHEMA);

        assertThat(hasWrongCredentials(scoped(login))).isTrue();
        assertThat(hasWrongCredentials(planner.planScoped(planner.readSpec(login), true, CoverageScope.FULL, planner.hintsOf(login)))).isFalse();
        // Không có hints (đường gọi cũ) thì không sinh mục này.
        assertThat(hasWrongCredentials(planner.planScoped(planner.readSpec(login), false, CoverageScope.FULL))).isFalse();
        assertThat(hasWrongCredentials(scoped(endpoint("POST", "/api/jobs", JOB_SCHEMA)))).isFalse();
    }

    @Test
    void registerResetAndNonPostEndpoints_neverGetWrongCredentialsItem() {
        // Đăng ký/đặt lại mật khẩu có email + mật khẩu nhưng KHÔNG được kỳ vọng 401 khi sai mật khẩu.
        assertThat(hasWrongCredentials(scoped(endpoint("POST", "/auth/register", LOGIN_SCHEMA)))).isFalse();
        assertThat(hasWrongCredentials(scoped(endpoint("POST", "/auth/signup", LOGIN_SCHEMA)))).isFalse();
        assertThat(hasWrongCredentials(scoped(endpoint("POST", "/auth/reset-password", LOGIN_SCHEMA)))).isFalse();
        assertThat(hasWrongCredentials(scoped(endpoint("POST", "/auth/forgot-password", LOGIN_SCHEMA)))).isFalse();
        // Tên giống login nhưng không phải POST.
        assertThat(hasWrongCredentials(scoped(endpoint("PUT", "/auth/login", LOGIN_SCHEMA)))).isFalse();
        // Đường dẫn không giống đăng nhập.
        assertThat(hasWrongCredentials(scoped(endpoint("POST", "/users", LOGIN_SCHEMA)))).isFalse();
        // Giống đăng nhập nhưng không có tín hiệu xác nhận (không khai 401, response không có token) -> bỏ sót còn hơn sinh sai.
        String noSignal = LOGIN_SCHEMA.replace("\"200\",\"400\",\"401\"", "\"200\",\"400\"");
        assertThat(hasWrongCredentials(scoped(endpoint("POST", "/auth/login", noSignal)))).isFalse();
    }

    @Test
    void loginDetectedByResponseToken_evenWhen401NotDeclared() {
        String noSignal = LOGIN_SCHEMA.replace("\"200\",\"400\",\"401\"", "\"200\",\"400\"");
        Endpoint login = endpoint("POST", "/auth/login", noSignal);
        login.setResponseSchemas("{\"200\":{\"type\":\"object\",\"properties\":{\"accessToken\":{\"type\":\"string\"}}}}");

        assertThat(hasWrongCredentials(scoped(login))).isTrue();
    }

    @Test
    void wrongCredentials_fill_changesOnlyPassword_expects401_andIsCovered() {
        Endpoint endpoint = endpoint("POST", "/auth/login", LOGIN_SCHEMA);
        Spec login = planner.readSpec(endpoint);
        ChecklistItem wrong = item(scoped(endpoint), ChecklistItemType.SECURITY_WRONG_CREDENTIALS, "password");
        GeneratedTestCase baseline = new GeneratedTestCase("ok", "d", Map.of(), "{\"email\":\"hr1@demo.local\",\"password\":\"Passw0rd!\"}",
                200, "/auth/login", Map.of(), null, null);

        GeneratedTestCase filled = planner.fill(login, wrong, baseline);

        assertThat(filled).isNotNull();
        assertThat(filled.expectedStatus()).isEqualTo(401);
        assertThat(filled.requestBody()).contains("hr1@demo.local").doesNotContain("\"password\":\"Passw0rd!\"");
        assertThat(planner.satisfies(login, wrong, filled)).isTrue();
        assertThat(planner.satisfies(login, wrong, baseline)).isFalse();
    }

    /** Lỗi thật phát hiện khi đo: test gốc của đăng nhập có authOverride=NONE (không gửi token) nhưng vẫn phải bù được. */
    @Test
    void wrongCredentials_isFilledEvenWhenBaselineHasAuthOverrideNone() {
        Endpoint endpoint = endpoint("POST", "/auth/login", LOGIN_SCHEMA);
        Spec login = planner.readSpec(endpoint);
        ChecklistItem wrong = item(scoped(endpoint), ChecklistItemType.SECURITY_WRONG_CREDENTIALS, "password");
        GeneratedTestCase baseline = new GeneratedTestCase("L1 Đăng nhập hợp lệ", "d", Map.of(),
                "{\"email\": \"hr1@demo.local\", \"password\": \"Passw0rd!\"}", 200, "/auth/login", Map.of(),
                TestCaseAuthOverride.NONE, null);

        TestCaseChecklistPlanner.FillAttempt attempt = planner.attemptFill(login, wrong, baseline);

        assertThat(attempt.filled()).isNotNull();
        assertThat(attempt.filled().expectedStatus()).isEqualTo(401);
        assertThat(attempt.filled().requestBody()).contains("WrongPassw0rd!");
        // Case 401 + NONE do người dùng tự viết cũng được tính là đã phủ mục.
        GeneratedTestCase handWritten = new GeneratedTestCase("tay", "d", Map.of(),
                "{\"email\":\"hr1@demo.local\",\"password\":\"abc\"}", 401, "/auth/login", Map.of(), TestCaseAuthOverride.NONE, null);
        assertThat(planner.covers(login, wrong, handWritten, false)).isTrue();
    }

    @Test
    void wrongCredentials_passwordRespectsDeclaredMaxLength() {
        String schema = LOGIN_SCHEMA.replace("\"password\":{\"type\":\"string\",\"minLength\":1}",
                "\"password\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":6}");
        Endpoint endpoint = endpoint("POST", "/auth/login", schema);
        Spec login = planner.readSpec(endpoint);
        ChecklistItem wrong = item(scoped(endpoint), ChecklistItemType.SECURITY_WRONG_CREDENTIALS, "password");
        GeneratedTestCase baseline = new GeneratedTestCase("ok", "d", Map.of(), "{\"email\":\"a@b.c\",\"password\":\"Pass1!\"}",
                200, "/auth/login", Map.of(), null, null);

        GeneratedTestCase filled = planner.fill(login, wrong, baseline);

        assertThat(filled).as("mật khẩu sai phải vừa maxLength = 6 mới là case hợp lệ theo spec").isNotNull();
        assertThat(filled.requestBody()).contains("\"password\":\"Wrong").doesNotContain("WrongPassw0rd!");
    }

    @Test
    void nearMiss_explainsLoginCaseThatDoesNotExpect401() {
        Endpoint endpoint = endpoint("POST", "/auth/login", LOGIN_SCHEMA);
        Spec login = planner.readSpec(endpoint);
        ChecklistItem wrong = item(scoped(endpoint), ChecklistItemType.SECURITY_WRONG_CREDENTIALS, "password");
        GeneratedTestCase ok = new GeneratedTestCase("ok", "d", Map.of(), "{\"email\":\"a@b.c\",\"password\":\"x\"}", 200, "/auth/login",
                Map.of(), null, null);

        assertThat(planner.explainNearMiss(login, wrong, ok, false)).contains("401");
    }

    @Test
    void loginEndpoint_lengthBoundariesExpectingSuccess_areNotApplicable_butFailureCasesStay() {
        List<ChecklistItem> items = scoped(endpoint("POST", "/auth/login", LOGIN_SCHEMA));

        assertThat(item(items, ChecklistItemType.BOUNDARY_AT_MIN_LENGTH, "email").notApplicableReason()).contains("đăng nhập");
        assertThat(item(items, ChecklistItemType.BOUNDARY_AT_MIN_LENGTH, "password").notApplicableReason()).isNotNull();
        assertThat(item(items, ChecklistItemType.BOUNDARY_BELOW_MIN_LENGTH, "email").notApplicableReason()).isNull();
        assertThat(item(items, ChecklistItemType.NEGATIVE_MISSING_FIELD, "password").notApplicableReason()).isNull();
    }

    @Test
    void registerEndpoint_keepsPasswordLengthBoundaries_whichAreHighValue() {
        List<ChecklistItem> items = scoped(endpoint("POST", "/auth/register", REGISTER_SCHEMA));

        assertThat(item(items, ChecklistItemType.BOUNDARY_AT_MIN_LENGTH, "password").notApplicableReason()).isNull();
        assertThat(item(items, ChecklistItemType.BOUNDARY_AT_MAX_LENGTH, "password").notApplicableReason()).isNull();
    }

    @Test
    void minMaxPair_lowBoundaryOnMaxSide_isNotApplicable_butOtherSideStays() {
        List<ChecklistItem> items = planner.plan(planner.readSpec(endpoint("POST", "/jobs", JOB_SCHEMA)), true, true, true);

        assertThat(item(items, ChecklistItemType.BOUNDARY_ZERO, "salaryMax").notApplicableReason()).contains("min/max");
        assertThat(item(items, ChecklistItemType.BOUNDARY_NEGATIVE_NUMBER, "salaryMax").notApplicableReason()).isNotNull();
        assertThat(item(items, ChecklistItemType.BOUNDARY_ZERO, "salaryMin").notApplicableReason()).isNull();
        assertThat(item(items, ChecklistItemType.BOUNDARY_AT_MAX_LENGTH, "title").notApplicableReason()).isNull();
    }

    @Test
    void idLikeFromToPair_andUnpairedLookAlikes_keepTheirBoundaries() {
        List<ChecklistItem> items = planner.plan(planner.readSpec(endpoint("POST", "/transfers", TRANSFER_SCHEMA)), true, true, true);

        // fromAccountId / toAccountId là 2 id độc lập - id = 0 hay âm vẫn là ca có giá trị.
        assertThat(item(items, ChecklistItemType.BOUNDARY_ZERO, "toAccountId").notApplicableReason()).isNull();
        assertThat(item(items, ChecklistItemType.BOUNDARY_NEGATIVE_NUMBER, "toAccountId").notApplicableReason()).isNull();
        // total/token/lowStock không có "anh em" nên không bị gỡ.
        for (String field : List.of("total", "token", "lowStock")) {
            assertThat(item(items, ChecklistItemType.BOUNDARY_ZERO, field).notApplicableReason()).as(field).isNull();
        }
    }
}
