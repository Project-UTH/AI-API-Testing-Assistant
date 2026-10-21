package com.mutantapi;

import com.mutantapi.TestApi.Resp;
import com.mutantapi.mutant.MutantFlag;
import com.mutantapi.mutant.Mutants;
import com.mutantapi.store.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static com.mutantapi.TestApi.jobJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Oracle suite" cua 42 mutant: moi mutant co 1 PROBE - kiem tra tu BEN NGOAI (chi goi API, khong cham ma trong)
 * tra ve true khi hanh vi SAI so voi dac ta. Chung minh 3 dieu:
 *  1. Ban chuan: khong probe nao bao loi (probe khong bao nham).
 *  2. Bat dung 1 mutant: probe cua no bao loi (mutant CO THE quan sat duoc tu ben ngoai - khong phai mutant tuong duong).
 *  3. Co lap: bat 1 mutant thi KHONG probe cua mutant khac bao loi (moi mutant la 1 loi doc lap).
 * Day cung la tran ly thuyet cua phep do: bo test ly tuong bat 42/42.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MutantProbesTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Mutants mutants;
    @Autowired
    Store store;

    private TestApi api;
    private final Map<MutantFlag, Predicate<TestApi>> probes = new EnumMap<>(MutantFlag.class);

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc);
        mutants.set(List.of());
        mutants.setRules(List.of());
        store.reset();
        registerProbes();
    }

    private void registerProbes() {
        // ---- Bo validate: du lieu SAI ma van duoc nhan (201) ----
        probes.put(MutantFlag.M01, a -> postJob(a, jobJson("title", null)).status() == 201);
        probes.put(MutantFlag.M02, a -> postJob(a, jobJson("title", "\"   \"")).status() == 201);
        probes.put(MutantFlag.M03, a -> postJob(a, jobJson("title", "\"" + "a".repeat(5000) + "\"")).status() == 201);
        probes.put(MutantFlag.M04, a -> postJob(a, jobJson("salaryCurrency", "\"VNDD\"")).status() == 201);
        probes.put(MutantFlag.M05, a -> postJob(a, jobJson("jobType", "\"ZZZ\"")).status() == 201);

        // ---- Sai bien (off-by-one) ----
        probes.put(MutantFlag.M06, a -> postJob(a, jobJson("salaryMin", "-1")).status() == 201);
        // M07 la loi "tu choi nham" gia tri HOP LE dung bien (200 ky tu) - nguoc huong voi M03/M01.
        probes.put(MutantFlag.M07, a -> postJob(a, jobJson("title", "\"" + "a".repeat(200) + "\"")).status() != 201);
        probes.put(MutantFlag.M08, a -> putRubric(a, "{\"criteria\":[{\"name\":\"A\",\"weight\":100,\"maxScore\":5},{\"name\":\"B\",\"weight\":0,\"maxScore\":5}]}").status() == 200);
        probes.put(MutantFlag.M09, a -> putRubric(a, "{\"criteria\":[{\"name\":\"A\",\"weight\":100,\"maxScore\":0}]}").status() == 200);
        probes.put(MutantFlag.M10, a -> postJob(a, jobJson("salaryCurrency", "\"VN\"")).status() == 201);

        // ---- Sai ma trang thai ----
        probes.put(MutantFlag.M11, a -> postJob(a, jobJson("description", null)).status() == 200);
        probes.put(MutantFlag.M12, a -> a.call("GET", "/api/jobs/9999", a.hr1(), null).status() == 500);
        probes.put(MutantFlag.M13, a -> putRubric(a, "{}").status() == 401);

        // ---- Thieu phan quyen ----
        probes.put(MutantFlag.M14, a -> a.call("POST", "/api/jobs", a.cand1(), jobJson()).status() == 201);
        probes.put(MutantFlag.M15, a -> {
            long job = a.createJob(a.hr1());
            return a.call("PUT", "/api/jobs/" + job, a.hr2(), jobJson()).status() == 200;
        });
        probes.put(MutantFlag.M16, a -> {
            long app = a.apply(a.cand1(), a.createJob(a.hr1()));
            return a.call("GET", "/api/applications/" + app, a.cand2(), null).status() == 200;
        });
        probes.put(MutantFlag.M17, a -> {
            long app = a.apply(a.cand1(), a.createJob(a.hr1()));
            return a.call("PATCH", "/api/applications/" + app + "/status", a.hr2(), "{\"status\":\"REJECTED\"}").status() == 200;
        });
        probes.put(MutantFlag.M18, a -> {
            long job = a.createJob(a.hr1());
            return a.call("GET", "/api/jobs/" + job, null, null).status() == 200;
        });

        // ---- Mass-assignment ----
        probes.put(MutantFlag.M19, a -> {
            Resp r = postJob(a, jobJson("ownerId", "2"));
            return r.status() == 201 && r.json().get("ownerId").asLong() == 2;
        });
        probes.put(MutantFlag.M20, a -> {
            String token = a.hr1();
            long job = a.createJob(token);
            Resp r = a.call("PUT", "/api/jobs/" + job, token, jobJson("status", "\"CLOSED\""));
            return r.status() == 200 && "CLOSED".equals(r.json().get("status").asText());
        });

        // ---- Lo du lieu nhay cam ----
        probes.put(MutantFlag.M21, a -> a.call("GET", "/api/users/me", a.hr1(), null).bodyHas("passwordHash"));
        probes.put(MutantFlag.M22, a -> {
            Resp r = postJob(a, jobJson("description", null));
            return r.status() == 400 && r.bodyHas("debug");
        });

        // ---- Vi pham luat nghiep vu ----
        probes.put(MutantFlag.M23, a -> postJob(a, jobJson("salaryMin", "5000", "salaryMax", "1000")).status() == 201);
        probes.put(MutantFlag.M24, a -> postJob(a, jobJson("deadline", "\"" + LocalDate.now().minusDays(1) + "\"")).status() == 201);
        probes.put(MutantFlag.M25, a -> putRubric(a, "{\"criteria\":[{\"name\":\"A\",\"weight\":60,\"maxScore\":5},{\"name\":\"B\",\"weight\":30,\"maxScore\":5}]}").status() == 200);
        probes.put(MutantFlag.M26, a -> {
            String hr = a.hr1();
            long app = a.apply(a.cand1(), a.createJob(hr));
            return a.call("PATCH", "/api/applications/" + app + "/status", hr, "{\"status\":\"HIRED\"}").status() == 200;
        });
        probes.put(MutantFlag.M27, a -> {
            String cand = a.cand1();
            long job = a.createJob(a.hr1());
            a.apply(cand, job);
            return a.call("POST", "/api/jobs/" + job + "/applications", cand, "{\"consent\":true}").status() == 201;
        });
        // ---- BO SUNG M28-M42 (test tay) ----
        probes.put(MutantFlag.M28, a -> a.call("POST", "/auth/login", null, "{\"email\":\"hr1@demo.local\",\"password\":\"SaiMatKhau1\"}").status() == 200);
        probes.put(MutantFlag.M29, a -> a.call("POST", "/auth/login", null, "{\"email\":\"khongco@demo.local\",\"password\":\"Passw0rd!\"}").status() == 500);
        probes.put(MutantFlag.M30, a -> a.call("POST", "/auth/login", null, "{\"email\":\"hr1@demo.local\",\"password\":\"Passw0rd!\"}").bodyHas("passwordHash"));
        probes.put(MutantFlag.M31, a -> a.call("GET", "/api/users/me", null, null).status() == 200);
        probes.put(MutantFlag.M32, a -> a.call("GET", "/api/users/me", a.hr1(), null).bodyHas("allUsers"));
        probes.put(MutantFlag.M33, a -> {
            String hr = a.hr1();
            long job = a.createJob(hr);
            a.call("DELETE", "/api/jobs/" + job, hr, null);
            return a.call("GET", "/api/jobs/" + job, hr, null).status() == 200;
        });
        probes.put(MutantFlag.M34, a -> {
            String hr = a.hr1();
            long job = a.createJob(hr);
            a.call("DELETE", "/api/jobs/" + job, hr, null);
            return a.call("PUT", "/api/jobs/" + job, hr, jobJson()).status() == 200;
        });
        probes.put(MutantFlag.M35, a -> {
            long job = a.createJob(a.hr1());
            return a.call("DELETE", "/api/jobs/" + job, a.hr2(), null).status() == 204;
        });
        probes.put(MutantFlag.M36, a -> {
            String hr = a.hr1();
            return a.call("DELETE", "/api/jobs/" + a.createJob(hr), hr, null).status() == 200;
        });
        probes.put(MutantFlag.M37, a -> {
            String hr = a.hr1();
            long job = a.createJob(hr);
            a.call("DELETE", "/api/jobs/" + job, hr, null);
            return a.call("DELETE", "/api/jobs/" + job, hr, null).status() == 204;
        });
        probes.put(MutantFlag.M38, a -> {
            long job = a.createJob(a.hr1());
            return a.call("POST", "/api/jobs/" + job + "/applications", a.hr2(), "{\"consent\":true}").status() == 201;
        });
        probes.put(MutantFlag.M39, a -> {
            long job = a.createJob(a.hr1());
            return a.call("POST", "/api/jobs/" + job + "/applications", a.cand1(), "{\"consent\":false}").status() == 201;
        });
        probes.put(MutantFlag.M40, a -> {
            long app = a.apply(a.cand1(), a.createJob(a.hr1()));
            return a.call("GET", "/api/applications/" + app, a.hr2(), null).status() == 200;
        });
        probes.put(MutantFlag.M41, a -> a.call("GET", "/api/applications/9999", a.hr1(), null).status() == 500);
        probes.put(MutantFlag.M42, a -> {
            String hr = a.hr1();
            long app = a.apply(a.cand1(), a.createJob(hr));
            return a.call("PATCH", "/api/applications/" + app + "/status", hr, "{\"status\":\"XYZ\"}").status() == 200;
        });
    }

    private Resp postJob(TestApi a, String json) {
        return a.call("POST", "/api/jobs", a.hr1(), json);
    }

    private Resp putRubric(TestApi a, String json) {
        String token = a.hr1();
        long job = a.createJob(token);
        return a.call("PUT", "/api/jobs/" + job + "/rubric", token, json);
    }

    @Test
    void everyMutantHasExactlyOneProbe() {
        assertThat(probes.keySet()).containsExactlyInAnyOrder(MutantFlag.values());
        assertThat(MutantFlag.values()).hasSize(42);
    }

    @Test
    void baseline_noProbeReportsAFault() {
        for (MutantFlag flag : MutantFlag.values()) {
            store.reset();
            assertThat(probes.get(flag).test(api)).as("ban chuan: probe %s khong duoc bao loi", flag).isFalse();
        }
    }

    @Test
    void eachMutant_isObservableFromOutside_andIsolatedFromTheOthers() {
        for (MutantFlag enabled : MutantFlag.values()) {
            mutants.set(List.of(enabled));
            for (MutantFlag probe : MutantFlag.values()) {
                store.reset();
                boolean faulty = probes.get(probe).test(api);
                assertThat(faulty)
                        .as("bat chi %s -> probe %s phai %s", enabled, probe, enabled == probe ? "BAO LOI" : "khong bao loi")
                        .isEqualTo(enabled == probe);
            }
        }
    }

    @Test
    void turningMutantOff_restoresBaseline() {
        mutants.set(List.of(MutantFlag.M26));
        store.reset();
        assertThat(probes.get(MutantFlag.M26).test(api)).isTrue();
        mutants.set(List.of());
        store.reset();
        assertThat(probes.get(MutantFlag.M26).test(api)).isFalse();
    }
}
