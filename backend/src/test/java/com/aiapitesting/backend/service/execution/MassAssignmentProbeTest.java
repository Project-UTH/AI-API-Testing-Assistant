package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.service.execution.MassAssignmentProbe.Field;
import com.aiapitesting.backend.service.execution.MassAssignmentProbe.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Module 17 (17b-2) - chọn field tiêm, tiêm vào body và chấm phản hồi. Thuần tuý, không mạng. */
class MassAssignmentProbeTest {

    private static final UUID ENDPOINT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static List<Field> fields(String... declared) {
        return MassAssignmentProbe.chooseFields(Set.of(declared), ENDPOINT);
    }

    private static Field named(List<Field> list, String name) {
        return list.stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void chooseFields_skipsDeclaredNamesIgnoringCaseAndSeparators_andCapsTheCount() {
        List<Field> fields = fields("role", "Is_Admin", "title");

        assertThat(fields).extracting(Field::name).doesNotContain("role", "isAdmin", "is_admin");
        assertThat(fields.size()).isLessThanOrEqualTo(MassAssignmentProbe.MAX_FIELDS);
        assertThat(fields).extracting(Field::name).contains("permissions", "status", "price");
    }

    @Test
    void chooseFields_followsTheSnakeCaseStyleOfTheDeclaredFields() {
        List<Field> fields = fields("full_name", "email");

        assertThat(fields).extracting(Field::name).contains("is_admin", "owner_id", "user_id").doesNotContain("isAdmin", "ownerId");
    }

    @Test
    void sentinels_areDeterministic_andLookLikeNothingSecret() {
        assertThat(fields("a")).isEqualTo(fields("a"));
        assertThat(named(fields("a"), "role").sentinel()).startsWith("zz_probe_").hasSize("zz_probe_".length() + 8);
        assertThat(named(fields("a"), "role").sentinel()).isNotEqualTo(named(MassAssignmentProbe.chooseFields(Set.of("a"), UUID.randomUUID()), "role").sentinel());
        // không mẫu nào bị bộ quét dữ liệu nhạy cảm coi là bí mật
        for (Field f : fields("a")) {
            String body = "{\"" + f.name() + "\":\"" + f.sentinel() + "\"}";
            assertThat(SensitiveDataScanner.scan("/x", "POST", true, null, body)).as(f.name()).isEmpty();
        }
    }

    @Test
    void inject_addsFieldsAtTheRootOfAnObject_orFirstArrayElement_andRefusesOtherBodies() {
        List<Field> fields = fields("title");
        String injected = MassAssignmentProbe.inject("{\"title\":\"x\"}", fields);
        assertThat(injected).contains("\"title\":\"x\"").contains("\"role\":\"" + named(fields, "role").sentinel() + "\"")
                .contains("\"isAdmin\":true").contains("\"price\":" + named(fields, "price").sentinel());
        assertThat(MassAssignmentProbe.inject("[{\"title\":\"x\"},{\"title\":\"y\"}]", fields)).startsWith("[{").contains("\"role\"");
        assertThat(MassAssignmentProbe.inject("plain text", fields)).isNull();
        assertThat(MassAssignmentProbe.inject("[1,2]", fields)).isNull();
        assertThat(MassAssignmentProbe.inject(null, fields)).isNull();
    }

    @Test
    void evaluate_echoedSentinel_failsEvenNestedAndCaseOrSnakeVariants() {
        List<Field> fields = fields("title");
        String role = named(fields, "role").sentinel();
        String admin = "true";

        assertThat(MassAssignmentProbe.evaluate(201, "{\"id\":1,\"role\":\"" + role + "\"}", fields).verdict()).isEqualTo(Verdict.FAIL);
        assertThat(MassAssignmentProbe.evaluate(200, "{\"data\":{\"user\":{\"Role\":\"" + role + "\"}}}", fields).reflected()).extracting(Field::name).contains("role");
        assertThat(MassAssignmentProbe.evaluate(200, "{\"items\":[{\"is_admin\":" + admin + "}]}", fields).reflected()).extracting(Field::name).contains("isAdmin");
        assertThat(MassAssignmentProbe.evaluate(200, "{\"permissions\":[\"" + named(fields, "permissions").sentinel() + "\"]}", fields).verdict()).isEqualTo(Verdict.FAIL);
        assertThat(MassAssignmentProbe.evaluate(200, "{\"price\":" + named(fields, "price").sentinel() + "}", fields).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void evaluate_ignoredOrRejected_passes_serverErrorFails_otherCodesAreInconclusive() {
        List<Field> fields = fields("title");
        assertThat(MassAssignmentProbe.evaluate(201, "{\"id\":1,\"title\":\"x\",\"role\":\"member\"}", fields).verdict()).isEqualTo(Verdict.PASS); // role có nhưng không phải sentinel
        assertThat(MassAssignmentProbe.evaluate(200, "", fields).verdict()).isEqualTo(Verdict.PASS);
        assertThat(MassAssignmentProbe.evaluate(400, "{\"error\":\"unknown field\"}", fields).verdict()).isEqualTo(Verdict.PASS);
        assertThat(MassAssignmentProbe.evaluate(422, null, fields).verdict()).isEqualTo(Verdict.PASS);
        assertThat(MassAssignmentProbe.evaluate(500, "boom", fields).verdict()).isEqualTo(Verdict.FAIL);
        for (int code : new int[]{401, 403, 404, 409}) {
            assertThat(MassAssignmentProbe.evaluate(code, "{}", fields).verdict()).as("status " + code).isEqualTo(Verdict.INCONCLUSIVE);
        }
        assertThat(MassAssignmentProbe.evaluate(null, null, fields).verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void evaluate_nonJsonResponse_matchesSentinelAsText() {
        List<Field> fields = fields("title");
        assertThat(MassAssignmentProbe.evaluate(200, "ok role=" + named(fields, "role").sentinel(), fields).verdict()).isEqualTo(Verdict.FAIL);
        assertThat(MassAssignmentProbe.evaluate(200, "ok", fields).verdict()).isEqualTo(Verdict.PASS);
    }
}
