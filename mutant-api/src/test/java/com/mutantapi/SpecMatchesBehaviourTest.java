package com.mutantapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mutantapi.mutant.Mutants;
import com.mutantapi.store.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static com.mutantapi.TestApi.jobJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI (sinh tu chu thich o Dtos) phai khop HANH VI THAT cua ban chuan. Neu 2 ben lech, cong cu sinh test se
 * sinh test theo dac ta sai va ta se nham "Fail gia" voi "bat duoc loi". Test nay doc /v3/api-docs roi thu dung
 * cac gia tri BIEN ma chinh dac ta khai bao.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpecMatchesBehaviourTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Mutants mutants;
    @Autowired
    Store store;

    private TestApi api;
    private JsonNode spec;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        mutants.set(List.of());
        store.reset();
        String json = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andReturn().getResponse().getContentAsString();
        spec = new ObjectMapper().readTree(json);
    }

    private JsonNode schema(String name) {
        return spec.path("components").path("schemas").path(name);
    }

    private JsonNode prop(String schema, String field) {
        return schema(schema).path("properties").path(field);
    }

    @Test
    void spec_declaresTenBusinessEndpoints_andHidesControlEndpoints() {
        List<String> operations = new ArrayList<>();
        spec.path("paths").fields().forEachRemaining(p ->
                p.getValue().fieldNames().forEachRemaining(method -> operations.add(method.toUpperCase() + " " + p.getKey())));
        assertThat(operations).containsExactlyInAnyOrder(
                "POST /auth/login", "GET /api/users/me",
                "POST /api/jobs", "GET /api/jobs/{id}", "PUT /api/jobs/{id}", "DELETE /api/jobs/{id}", "PUT /api/jobs/{id}/rubric",
                "POST /api/jobs/{jobId}/applications", "GET /api/applications/{id}", "PATCH /api/applications/{id}/status");
        assertThat(spec.toString()).doesNotContain("__mutants").doesNotContain("__reset");
    }

    @Test
    void spec_declaresConstraintsAndSecurity() {
        assertThat(prop("JobRequest", "title").path("maxLength").asInt()).isEqualTo(200);
        // Truong NotBlank phai khai minLength 1: neu spec ghi 0 ma API tu choi chuoi rong thi cong cu sinh test se tao
        // test "chuoi rong hop le" va bi Fail gia - loi do dac ta, khong phai do cong cu.
        assertThat(prop("JobRequest", "title").path("minLength").asInt()).isEqualTo(1);
        assertThat(prop("JobRequest", "description").path("minLength").asInt()).isEqualTo(1);
        assertThat(prop("LoginRequest", "email").path("minLength").asInt()).isEqualTo(1);
        assertThat(prop("LoginRequest", "password").path("minLength").asInt()).isEqualTo(1);
        assertThat(prop("CriterionRequest", "name").path("minLength").asInt()).isEqualTo(1);
        assertThat(prop("JobRequest", "salaryCurrency").path("minLength").asInt()).isEqualTo(3);
        assertThat(prop("JobRequest", "salaryCurrency").path("maxLength").asInt()).isEqualTo(3);
        assertThat(prop("JobRequest", "salaryMin").path("minimum").asInt()).isZero();
        assertThat(prop("JobRequest", "jobType").path("enum").toString()).contains("FULL_TIME", "PART_TIME", "INTERN");
        assertThat(prop("CriterionRequest", "weight").path("minimum").asDouble()).isEqualTo(0.01);
        assertThat(prop("CriterionRequest", "weight").path("maximum").asInt()).isEqualTo(100);
        assertThat(prop("CriterionRequest", "maxScore").path("minimum").asInt()).isEqualTo(1);
        assertThat(schema("JobRequest").path("required").toString()).contains("title", "description", "jobType", "deadline");
        assertThat(spec.path("components").path("securitySchemes").has("bearerAuth")).isTrue();
        assertThat(spec.path("paths").path("/api/jobs").path("post").path("security").toString()).contains("bearerAuth");
        assertThat(spec.path("paths").path("/auth/login").path("post").has("security")).isFalse();
    }

    @Test
    void behaviour_followsDeclaredBoundaries() {
        String hr = api.hr1();
        TestApi.Resp r;
        // Chuoi rong bi tu choi o moi truong NotBlank (khop minLength 1 trong dac ta).
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("title", "\"\"")).status()).isEqualTo(400);
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("description", "\"\"")).status()).isEqualTo(400);
        assertThat(api.call("POST", "/auth/login", null, "{\"email\":\"\",\"password\":\"x\"}").status()).isEqualTo(400);
        // title: toi da 200 ky tu - dung bien (200) nhan, vuot bien (201) tu choi.
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("title", "\"" + "a".repeat(200) + "\"")).status()).isEqualTo(201);
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("title", "\"" + "a".repeat(201) + "\"")).status()).isEqualTo(400);
        // salaryCurrency: dung 3 ky tu.
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("salaryCurrency", "\"VND\"")).status()).isEqualTo(201);
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("salaryCurrency", "\"VN\"")).status()).isEqualTo(400);
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("salaryCurrency", "\"VNDD\"")).status()).isEqualTo(400);
        // salaryMin >= 0.
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("salaryMin", "0")).status()).isEqualTo(201);
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("salaryMin", "-1")).status()).isEqualTo(400);
        // jobType enum.
        for (String type : List.of("FULL_TIME", "PART_TIME", "INTERN")) {
            assertThat(api.call("POST", "/api/jobs", hr, jobJson("jobType", "\"" + type + "\"")).status()).isEqualTo(201);
        }
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("jobType", "\"CONTRACT\"")).status()).isEqualTo(400);
        // Sai kieu / null / thieu truong bat buoc.
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("salaryMin", "\"abc\"")).status()).isEqualTo(400);
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("title", "null")).status()).isEqualTo(400);
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("title", "12345")).status()).isEqualTo(400);
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("description", null)).status()).isEqualTo(400);
        // Rubric: weight 0.01..100, maxScore >= 1, tong = 100.
        long job = api.createJob(hr);
        String one = "{\"criteria\":[{\"name\":\"A\",\"weight\":100,\"maxScore\":1}]}";
        assertThat(api.call("PUT", "/api/jobs/" + job + "/rubric", hr, one).status()).isEqualTo(200);
        assertThat(api.call("PUT", "/api/jobs/" + job + "/rubric", hr,
                "{\"criteria\":[{\"name\":\"A\",\"weight\":99.99,\"maxScore\":1},{\"name\":\"B\",\"weight\":0.01,\"maxScore\":1}]}").status()).isEqualTo(200);
        assertThat(api.call("PUT", "/api/jobs/" + job + "/rubric", hr, one.replace("\"maxScore\":1", "\"maxScore\":0")).status()).isEqualTo(400);
        assertThat(api.call("PUT", "/api/jobs/" + job + "/rubric", hr, one.replace("100", "101")).status()).isEqualTo(400);
        r = api.call("PUT", "/api/jobs/" + job + "/rubric", hr, one.replace("100", "90"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().get("code").asText()).isEqualTo("RUBRIC_WEIGHT_SUM_INVALID");
    }

    @Test
    void behaviour_authAndErrorShapes() {
        assertThat(api.call("GET", "/api/jobs/1", null, null).status()).isEqualTo(401);
        assertThat(api.call("GET", "/api/jobs/1", "token-sai", null).status()).isEqualTo(401);
        assertThat(api.call("GET", "/api/jobs/9999", api.hr1(), null).json().get("code").asText()).isEqualTo("JOB_NOT_FOUND");
        assertThat(api.call("GET", "/api/jobs/abc", api.hr1(), null).status()).isEqualTo(400);
        assertThat(api.call("POST", "/auth/login", null, "{\"email\":\"hr1@demo.local\",\"password\":\"sai\"}").status()).isEqualTo(401);
        assertThat(api.call("POST", "/auth/login", null, "{}").status()).isEqualTo(400);
        assertThat(api.call("POST", "/api/jobs", api.hr1(), "khong-phai-json").status()).isEqualTo(400);
        assertThat(api.call("GET", "/api/users/me", api.hr1(), null).bodyHas("passwordHash")).isFalse();
        // Control endpoint tat mac dinh (MUTANT_CONTROL khong bat) -> 404.
        assertThat(api.call("GET", "/__mutants", null, null).status()).isEqualTo(404);
    }
}
