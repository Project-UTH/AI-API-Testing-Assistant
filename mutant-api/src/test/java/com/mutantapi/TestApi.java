package com.mutantapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.LocalDate;

/** Client nho goi API qua MockMvc nhu 1 ben ngoai (black-box): khong cham vao ma ben trong. */
public class TestApi {

    public record Resp(int status, String body) {
        public JsonNode json() {
            try {
                return new ObjectMapper().readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("Khong phai JSON: " + body, e);
            }
        }

        public boolean bodyHas(String field) {
            return body != null && !body.isBlank() && json().has(field);
        }
    }

    private final MockMvc mvc;

    public TestApi(MockMvc mvc) {
        this.mvc = mvc;
    }

    public Resp call(String method, String path, String token, String json) {
        try {
            MockHttpServletRequestBuilder req = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path);
            if (token != null) {
                req.header("Authorization", "Bearer " + token);
            }
            if (json != null) {
                req.contentType(MediaType.APPLICATION_JSON).content(json);
            }
            var result = mvc.perform(req).andReturn().getResponse();
            return new Resp(result.getStatus(), result.getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String login(String email) {
        Resp r = call("POST", "/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}");
        if (r.status() != 200) {
            throw new IllegalStateException("Dang nhap that bai: " + r.status() + " " + r.body());
        }
        return r.json().get("token").asText();
    }

    public String hr1() {
        return login("hr1@demo.local");
    }

    public String hr2() {
        return login("hr2@demo.local");
    }

    public String cand1() {
        return login("cand1@demo.local");
    }

    public String cand2() {
        return login("cand2@demo.local");
    }

    /** Body job hop le (deadline = hom nay + 30 ngay), cho phep ghi de tung truong. */
    public static String jobJson(String... overrides) {
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("title", "\"Backend Developer\"");
        fields.put("description", "\"Phat trien API\"");
        fields.put("salaryMin", "1000");
        fields.put("salaryMax", "2000");
        fields.put("salaryCurrency", "\"USD\"");
        fields.put("jobType", "\"FULL_TIME\"");
        fields.put("deadline", "\"" + LocalDate.now().plusDays(30) + "\"");
        for (int i = 0; i + 1 < overrides.length; i += 2) {
            if (overrides[i + 1] == null) {
                fields.remove(overrides[i]);
            } else {
                fields.put(overrides[i], overrides[i + 1]);
            }
        }
        StringBuilder sb = new StringBuilder("{");
        fields.forEach((k, v) -> sb.append(sb.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v));
        return sb.append('}').toString();
    }

    /** Tao 1 job hop le bang token HR, tra ve id. */
    public long createJob(String hrToken) {
        Resp r = call("POST", "/api/jobs", hrToken, jobJson());
        if (r.status() != 201) {
            throw new IllegalStateException("Tao job that bai: " + r.status() + " " + r.body());
        }
        return r.json().get("id").asLong();
    }

    /** Ung vien nop don vao job, tra ve id don. */
    public long apply(String candToken, long jobId) {
        Resp r = call("POST", "/api/jobs/" + jobId + "/applications", candToken, "{\"consent\":true}");
        if (r.status() != 201) {
            throw new IllegalStateException("Nop don that bai: " + r.status() + " " + r.body());
        }
        return r.json().get("id").asLong();
    }
}
