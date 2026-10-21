package com.mutantapi.mutant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Danh muc "cong tac quy tac": moi quy tac kiem tra dau vao/xac thuc cua API co 1 cong tac de TAT dung kiem tra do.
 * Moi dang test "tu choi du lieu sai" ma he thong sinh (thieu truong, null, sai kieu, qua ngan/dai, ngoai enum, khong token...)
 * deu co dung 1 cong tac tuong ung o endpoint do. Danh muc dua tren QUY TAC CUA API (khong dua tren test do he thong sinh).
 * Nhung quy tac khong the quan sat doc lap tu ben ngoai (bi 1 luat khac che mat: consent, tong weight, "rong" cua truong enum/ngay) KHONG co cong tac - xem README.
 */
public final class RuleCatalog {

    public record Rule(String id, String endpoint, String field, String kind, String description) {
    }

    private static final Map<String, String> KIND_TEXT = Map.ofEntries(
            Map.entry("required", "thieu truong van duoc nhan"),
            Map.entry("notNull", "gia tri null van duoc nhan"),
            Map.entry("type", "sai kieu du lieu van duoc nhan"),
            Map.entry("notBlank", "rong / chi khoang trang van duoc nhan"),
            Map.entry("minLength", "ngan hon toi thieu van duoc nhan"),
            Map.entry("maxLength", "dai hon toi da van duoc nhan"),
            Map.entry("min", "nho hon gia tri toi thieu van duoc nhan"),
            Map.entry("enum", "gia tri ngoai danh sach cho phep van duoc nhan"),
            Map.entry("format", "sai dinh dang van duoc nhan"),
            Map.entry("missing", "khong gui token van vao duoc (bo kiem tra xac thuc)"),
            Map.entry("invalid", "token sai van vao duoc (bo kiem tra xac thuc)"));

    private static final List<Rule> RULES = build();

    private RuleCatalog() {
    }

    public static String id(String endpoint, String field, String kind) {
        return endpoint + "|" + field + "|" + kind;
    }

    public static List<Rule> all() {
        return RULES;
    }

    private static void add(List<Rule> out, String endpoint, String field, String... kinds) {
        for (String kind : kinds) {
            out.add(new Rule(id(endpoint, field, kind), endpoint, field, kind, field + ": " + KIND_TEXT.get(kind)));
        }
    }

    private static List<Rule> build() {
        List<Rule> out = new ArrayList<>();
        // Dang nhap
        add(out, "POST /auth/login", "email", "required", "notNull", "type", "notBlank");
        add(out, "POST /auth/login", "password", "required", "notNull", "type", "notBlank");
        // Tao / sua tin tuyen dung dung chung bo luat
        for (String ep : List.of("POST /api/jobs", "PUT /api/jobs/{id}")) {
            add(out, ep, "title", "required", "notNull", "type", "notBlank", "maxLength");
            add(out, ep, "description", "required", "notNull", "type", "notBlank");
            add(out, ep, "salaryMin", "type", "min");
            add(out, ep, "salaryMax", "type", "min");
            add(out, ep, "salaryCurrency", "type", "minLength", "maxLength");
            add(out, ep, "jobType", "required", "notNull", "type", "enum");
            add(out, ep, "deadline", "required", "notNull", "type", "format");
        }
        // Rubric: chi 2 quy tac quan sat doc lap duoc (cac quy tac con lai bi luat "tong weight = 100" che mat)
        add(out, "PUT /api/jobs/{id}/rubric", "criteria[].weight", "min");
        add(out, "PUT /api/jobs/{id}/rubric", "criteria[].maxScore", "min");
        // Nop don
        add(out, "POST /api/jobs/{id}/applications", "coverLetter", "type", "maxLength");
        // Doi trang thai don
        add(out, "PATCH /api/applications/{id}/status", "status", "required", "notNull", "type", "enum");
        // Xac thuc: moi endpoint /api/**
        for (String ep : List.of("GET /api/users/me", "POST /api/jobs", "GET /api/jobs/{id}", "PUT /api/jobs/{id}",
                "DELETE /api/jobs/{id}", "PUT /api/jobs/{id}/rubric", "POST /api/jobs/{id}/applications",
                "GET /api/applications/{id}", "PATCH /api/applications/{id}/status")) {
            add(out, ep, "auth", "missing", "invalid");
        }
        return List.copyOf(out);
    }
}
