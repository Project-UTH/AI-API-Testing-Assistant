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
import java.util.Set;
import java.util.function.Predicate;

import static com.mutantapi.TestApi.jobJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Oracle suite" cua 42 mutant M01-M42 + 15 mutant held-out H01-H15 (do G5): moi mutant co 1 PROBE - kiem tra tu BEN NGOAI (chi goi API, khong cham ma trong)
 * tra ve true khi hanh vi SAI so voi dac ta. Chung minh 3 dieu:
 *  1. Ban chuan: khong probe nao bao loi (probe khong bao nham).
 *  2. Bat dung 1 mutant: probe cua no bao loi (mutant CO THE quan sat duoc tu ben ngoai - khong phai mutant tuong duong).
 *  3. Co lap: bat 1 mutant thi KHONG probe cua mutant khac bao loi (moi mutant la 1 loi doc lap), tru cac cap
 *     chong lan da biet giua bo H va bo M (KNOWN_OVERLAPS).
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
        registerHeldoutProbes();
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

    /**
     * Probe cho bo held-out H01-H15 (docs/eval/heldout-mutants.md). Moi probe = 1-2 request theo cot "Cach quan sat" cua dac ta,
     * tra true khi thay hanh vi LOI.
     */
    private void registerHeldoutProbes() {
        probes.put(MutantFlag.H01, a -> postJob(a, jobJson("title", "\"" + "a".repeat(201) + "\"")).status() == 201);
        probes.put(MutantFlag.H02, a -> putRubric(a, "{\"criteria\":[{\"name\":\"A\",\"weight\":0,\"maxScore\":5},{\"name\":\"B\",\"weight\":100,\"maxScore\":5}]}").status() == 200);
        probes.put(MutantFlag.H03, a -> {
            long job = a.createJob(a.hr1());
            // 1000 ky tu U+1EC7 = 3000 byte UTF-8: hop le theo so ky tu (<= 2000).
            return a.call("POST", "/api/jobs/" + job + "/applications", a.cand1(),
                    "{\"coverLetter\":\"" + "ệ".repeat(1000) + "\",\"consent\":true}").status() != 201;
        });
        probes.put(MutantFlag.H04, a -> a.call("GET", "/api/jobs/abc", a.cand1(), null).status() == 500);
        probes.put(MutantFlag.H05, a -> postJob(a, jobJson("deadline", "\"2027-02-30\"")).status() == 500);
        probes.put(MutantFlag.H06, a -> {
            long job = a.createJob(a.hr1());
            return a.call("POST", "/api/jobs/" + job + "/applications", a.hr2(), "{\"consent\":true}").status() == 201;
        });
        probes.put(MutantFlag.H07, a -> {
            // Doi 1 ky tu o giua phan chu ky (ky tu cuoi cua base64url co 2 bit dem, doi no co the khong doi chu ky that).
            String token = a.hr1();
            int i = token.lastIndexOf('.') + 10;
            String forged = token.substring(0, i) + (token.charAt(i) == 'A' ? 'B' : 'A') + token.substring(i + 1);
            return a.call("POST", "/api/jobs", forged, jobJson()).status() == 201;
        });
        probes.put(MutantFlag.H08, a -> {
            String hr = a.hr1();
            long job = a.createJob(hr);
            return a.call("PUT", "/api/jobs/" + job, hr, jobJson("salaryMin", "5000", "salaryMax", "1000")).status() == 200;
        });
        probes.put(MutantFlag.H09, a -> postJob(a, jobJson("deadline", "\"" + LocalDate.now() + "\"")).status() != 201);
        probes.put(MutantFlag.H10, a -> putRubric(a, "{\"criteria\":[{\"name\":\"A\",\"weight\":40,\"maxScore\":5},{\"name\":\"B\",\"weight\":50,\"maxScore\":5}]}").status() == 200);
        probes.put(MutantFlag.H11, a -> {
            String hr = a.hr1();
            String cand = a.cand1();
            long job = a.createJob(hr);
            long app = a.apply(cand, job);
            a.call("PATCH", "/api/applications/" + app + "/status", hr, "{\"status\":\"REJECTED\"}");
            return a.call("POST", "/api/jobs/" + job + "/applications", cand, "{\"consent\":true}").status() == 201;
        });
        probes.put(MutantFlag.H12, a -> {
            String hr = a.hr1();
            long app = a.apply(a.cand1(), a.createJob(hr));
            a.call("PATCH", "/api/applications/" + app + "/status", hr, "{\"status\":\"REJECTED\"}");
            return a.call("PATCH", "/api/applications/" + app + "/status", hr, "{\"status\":\"INTERVIEW_INVITED\"}").status() == 200;
        });
        probes.put(MutantFlag.H13, a -> {
            long app = a.apply(a.cand1(), a.createJob(a.hr1()));
            return a.call("GET", "/api/applications/" + app, a.cand2(), null).status() == 200;
        });
        probes.put(MutantFlag.H14, a -> a.call("GET", "/api/users/me", a.cand1(), null).bodyHas("passwordHash"));
        probes.put(MutantFlag.H15, a -> {
            String hr = a.hr1();
            long job = a.createJob(hr);
            a.call("DELETE", "/api/jobs/" + job, hr, null);
            return a.call("POST", "/api/jobs/" + job + "/applications", a.cand2(), "{\"consent\":true}").status() == 201;
        });
    }

    /**
     * Chong lan da biet giua bo held-out (thiet ke doc lap, khong xem code) va mutant M cu: bat key thi probe trong value cung bao loi.
     * Day la trung hanh vi that (cung loi), khong phai loi cai dat - ghi ro de khi do G5 khong hieu nham.
     */
    private static final Map<MutantFlag, Set<MutantFlag>> KNOWN_OVERLAPS = Map.ofEntries(
            Map.entry(MutantFlag.H02, Set.of(MutantFlag.M08)),
            Map.entry(MutantFlag.M08, Set.of(MutantFlag.H02)),
            Map.entry(MutantFlag.H06, Set.of(MutantFlag.M38)),
            Map.entry(MutantFlag.M38, Set.of(MutantFlag.H06)),
            Map.entry(MutantFlag.H13, Set.of(MutantFlag.M16)),
            Map.entry(MutantFlag.M16, Set.of(MutantFlag.H13)),
            Map.entry(MutantFlag.H14, Set.of(MutantFlag.M21)),
            Map.entry(MutantFlag.M21, Set.of(MutantFlag.H14)),
            Map.entry(MutantFlag.H10, Set.of(MutantFlag.M25)),
            Map.entry(MutantFlag.M25, Set.of(MutantFlag.H10)),
            // Mutant M "rong hon" bao trum ca loi hep cua H:
            Map.entry(MutantFlag.M03, Set.of(MutantFlag.H01)),   // bo han toi da title -> 201 ky tu cung lot
            Map.entry(MutantFlag.M23, Set.of(MutantFlag.H08)),   // bo salaryMin<=salaryMax o ca POST lan PUT
            Map.entry(MutantFlag.M27, Set.of(MutantFlag.H11)));  // nhan moi don trung -> nop lai sau REJECTED cung lot

    private static boolean expectedFault(MutantFlag enabled, MutantFlag probe) {
        return enabled == probe || KNOWN_OVERLAPS.getOrDefault(enabled, Set.of()).contains(probe);
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
        assertThat(MutantFlag.values()).hasSize(57); // M01-M42 + held-out H01-H15
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
                        .as("bat chi %s -> probe %s phai %s", enabled, probe, expectedFault(enabled, probe) ? "BAO LOI" : "khong bao loi")
                        .isEqualTo(expectedFault(enabled, probe));
            }
        }
    }

    @Test
    void eachHeldoutMutant_turnedOff_restoresBaseline() {
        for (MutantFlag h : MutantFlag.values()) {
            if (!h.heldout()) {
                continue;
            }
            mutants.set(List.of(h));
            store.reset();
            assertThat(probes.get(h).test(api)).as("bat %s -> probe bao loi", h).isTrue();
            mutants.set(List.of());
            store.reset();
            assertThat(probes.get(h).test(api)).as("tat %s -> ve ban chuan", h).isFalse();
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
