package com.mutantapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mutantapi.TestApi.Resp;
import com.mutantapi.mutant.MutantFlag;
import com.mutantapi.mutant.Mutants;
import com.mutantapi.mutant.RuleCatalog;
import com.mutantapi.mutant.RuleCatalog.Rule;
import com.mutantapi.store.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mutantapi.TestApi.jobJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiem chung "cong tac quy tac" (RuleCatalog) bang cach goi API tu ben ngoai:
 *  1. Ban chuan: moi vi pham (thieu truong, null, sai kieu, qua ngan/dai, ngoai enum, khong token...) bi TU CHOI dung ma (400, hoac 401 cho auth).
 *  2. Bat dung 1 cong tac: vi pham cua no KHONG con bi tu choi.
 *  3. Co lap: bat 1 cong tac thi vi pham cua cac quy tac KHAC van bi tu choi.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RuleSwitchesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;
    @Autowired
    Mutants mutants;
    @Autowired
    Store store;

    private TestApi api;
    private String hr;
    private String cand;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc);
        mutants.set(List.of());
        mutants.setRules(List.of());
        store.reset();
        hr = api.hr1();
        cand = api.cand1();
    }

    @Test
    void catalogIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        RuleCatalog.all().forEach(r -> assertThat(ids.add(r.id())).as("trung id %s", r.id()).isTrue());
        assertThat(ids).hasSizeGreaterThan(80);
    }

    @Test
    void baseline_everyViolationIsRejected() {
        for (Rule r : RuleCatalog.all()) {
            assertThat(violate(r).status()).as("ban chuan: %s phai bi tu choi", r.id()).isEqualTo(rejected(r));
        }
    }

    @Test
    void eachSwitch_disablesOnlyItsOwnCheck() {
        for (Rule switched : RuleCatalog.all()) {
            mutants.setRules(List.of(switched.id()));
            for (Rule probe : RuleCatalog.all()) {
                int status = violate(probe).status();
                if (probe == switched) {
                    assertThat(status).as("tat %s -> vi pham cua no khong con bi tu choi", probe.id()).isNotEqualTo(rejected(probe));
                } else {
                    assertThat(status).as("tat %s -> vi pham %s van phai bi tu choi", switched.id(), probe.id()).isEqualTo(rejected(probe));
                }
            }
        }
    }

    @Test
    void handWrittenMutantsAreUnaffectedByTheSwitchesBeingOff() {
        // Tat het cong tac thi mutant viet tay hoat dong nhu cu (vd M01 van cho thieu title).
        mutants.set(List.of(MutantFlag.M01));
        assertThat(api.call("POST", "/api/jobs", hr, jobJson("title", null)).status()).isEqualTo(201);
    }

    private static int rejected(Rule r) {
        return "auth".equals(r.field()) ? 401 : 400;
    }

    // ---------------------------------------------------------------- tao request vi pham

    private Resp violate(Rule r) {
        return "auth".equals(r.field()) ? violateAuth(r) : violateField(r);
    }

    private Resp violateAuth(Rule r) {
        String token = "missing".equals(r.kind()) ? null : "garbage";
        return switch (r.endpoint()) {
            case "GET /api/users/me" -> api.call("GET", "/api/users/me", token, null);
            case "POST /api/jobs" -> api.call("POST", "/api/jobs", token, jobJson());
            case "GET /api/jobs/{id}" -> api.call("GET", "/api/jobs/" + api.createJob(hr), token, null);
            case "PUT /api/jobs/{id}" -> api.call("PUT", "/api/jobs/" + api.createJob(hr), token, jobJson());
            case "DELETE /api/jobs/{id}" -> api.call("DELETE", "/api/jobs/" + api.createJob(hr), token, null);
            case "PUT /api/jobs/{id}/rubric" -> api.call("PUT", "/api/jobs/" + api.createJob(hr) + "/rubric", token,
                    "{\"criteria\":[{\"name\":\"A\",\"weight\":100,\"maxScore\":5}]}");
            case "POST /api/jobs/{id}/applications" -> api.call("POST", "/api/jobs/" + api.createJob(hr) + "/applications", token, "{\"consent\":true}");
            case "GET /api/applications/{id}" -> api.call("GET", "/api/applications/" + api.apply(cand, api.createJob(hr)), token, null);
            case "PATCH /api/applications/{id}/status" -> api.call("PATCH", "/api/applications/" + api.apply(cand, api.createJob(hr)) + "/status",
                    token, "{\"status\":\"INTERVIEW_INVITED\"}");
            default -> throw new IllegalStateException("Endpoint auth chua co trong test: " + r.endpoint());
        };
    }

    private Resp violateField(Rule r) {
        try {
            switch (r.endpoint()) {
                case "POST /auth/login":
                    return api.call("POST", "/auth/login", null, mutate("{\"email\":\"hr1@demo.local\",\"password\":\"Passw0rd!\"}", r));
                case "POST /api/jobs":
                    return api.call("POST", "/api/jobs", hr, mutate(jobJson(), r));
                case "PUT /api/jobs/{id}":
                    return api.call("PUT", "/api/jobs/" + api.createJob(hr), hr, mutate(jobJson(), r));
                case "PUT /api/jobs/{id}/rubric": {
                    long job = api.createJob(hr);
                    String body = r.field().endsWith("weight")
                            ? "{\"criteria\":[{\"name\":\"A\",\"weight\":100,\"maxScore\":5},{\"name\":\"B\",\"weight\":0,\"maxScore\":5}]}"
                            : "{\"criteria\":[{\"name\":\"A\",\"weight\":100,\"maxScore\":0}]}";
                    return api.call("PUT", "/api/jobs/" + job + "/rubric", hr, body);
                }
                case "POST /api/jobs/{id}/applications":
                    return api.call("POST", "/api/jobs/" + api.createJob(hr) + "/applications", cand, mutate("{\"consent\":true}", r));
                case "PATCH /api/applications/{id}/status":
                    return api.call("PATCH", "/api/applications/" + api.apply(cand, api.createJob(hr)) + "/status", hr,
                            mutate("{\"status\":\"INTERVIEW_INVITED\"}", r));
                default:
                    throw new IllegalStateException("Endpoint chua co trong test: " + r.endpoint());
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lay body hop le roi lam hong dung 1 truong theo dang quy tac. */
    private static String mutate(String validJson, Rule r) throws com.fasterxml.jackson.core.JsonProcessingException {
        ObjectNode body = (ObjectNode) JSON.readTree(validJson);
        String f = r.field();
        boolean numericField = f.equals("salaryMin") || f.equals("salaryMax");
        switch (r.kind()) {
            case "required" -> body.remove(f);
            case "notNull" -> body.putNull(f);
            case "type" -> {
                if (numericField) {
                    body.put(f, "abc");
                } else {
                    body.put(f, 123);
                }
            }
            case "notBlank" -> body.put(f, "   ");
            case "maxLength" -> body.put(f, f.equals("salaryCurrency") ? "VNDD" : "a".repeat(f.equals("coverLetter") ? 2001 : 201));
            case "minLength" -> body.put(f, "VN");
            case "min" -> {
                if (f.equals("salaryMax")) {
                    body.remove("salaryMin"); // tranh luat nghiep vu salaryMin <= salaryMax che mat vi pham
                }
                body.put(f, -1);
            }
            case "enum" -> body.put(f, "ZZZ");
            case "format" -> body.put(f, "not-a-date");
            default -> throw new IllegalStateException("Dang quy tac chua co trong test: " + r.kind());
        }
        return body.toString();
    }
}
