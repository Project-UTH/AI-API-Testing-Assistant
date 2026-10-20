package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.service.execution.SensitiveDataScanner.Finding;
import com.aiapitesting.backend.service.execution.SensitiveDataScanner.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Module 17 (17b) - bộ quét dữ liệu nhạy cảm: mỗi mẫu có ca khớp + ca KHÔNG được khớp (false positive). */
class SensitiveDataScannerTest {

    private static List<Finding> scan(String path, String method, boolean auth, String requestBody, String response) {
        return SensitiveDataScanner.scan(path, method, auth, requestBody, response);
    }

    private static List<Finding> get(String response) {
        return scan("/api/users/{id}", "GET", true, null, response);
    }

    @Test
    void passwordAndPasswordHashFields_areHighSeverity() {
        List<Finding> findings = get("{\"id\":1,\"email\":\"a@b.co\",\"passwordHash\":\"$2a$10$abcdefghijklmnopqrstuv\",\"password\":\"hunter2!!\"}");

        assertThat(findings).extracting(Finding::path).contains("passwordHash", "password");
        assertThat(findings).allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.HIGH));
    }

    @Test
    void neverStoresOrReturnsTheRealValue() {
        String secret = "SUPER-SECRET-VALUE-12345";
        List<Finding> findings = get("{\"apiKey\":\"" + secret + "\",\"clientSecret\":\"" + secret + "2\"}");

        assertThat(findings).isNotEmpty();
        assertThat(findings.toString()).doesNotContain("SUPER-SECRET-VALUE");
    }

    @Test
    void valuePatterns_bcryptPemAwsPrefixedKeysAndJwt() {
        String json = "{\"a\":\"$2b$12$" + "x".repeat(53) + "\",\"b\":\"-----BEGIN RSA PRIVATE KEY-----\",\"c\":\"AKIAABCDEFGHIJKLMNOP\","
                + "\"d\":\"sk_live_abcdefghijkl\",\"e\":\"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc\"}";

        List<Finding> findings = get(json);

        assertThat(findings).extracting(Finding::rule).contains("VALUE_BCRYPT", "VALUE_PEM_KEY", "VALUE_AWS_KEY", "VALUE_API_KEY", "VALUE_JWT");
        assertThat(findings).filteredOn(f -> f.rule().equals("VALUE_JWT")).singleElement().satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.MEDIUM));
    }

    @Test
    void lookalikeNamesAndNonStringValues_areNotFlagged() {
        // tokenType/expiresIn/fileHash/etag không phải bí mật; hasPassword là boolean; giá trị che; quá ngắn
        List<Finding> findings = get("{\"tokenType\":\"Bearer\",\"expiresIn\":3600,\"fileHash\":\"d41d8cd98f00b204e9800998ecf8427e\","
                + "\"etag\":\"abcdef123456\",\"hasPassword\":true,\"password\":\"***\",\"secret\":\"abc\",\"passwordLength\":12}");

        assertThat(findings).isEmpty();
    }

    @Test
    void tokenFieldsAreAllowedOnLoginAndPublicPostButFlaggedElsewhere() {
        String body = "{\"accessToken\":\"abcdefghijklmnop\",\"refreshToken\":\"qrstuvwxyz123456\"}";

        assertThat(scan("/api/auth/login", "POST", false, null, body)).isEmpty();
        assertThat(scan("/users/register", "POST", false, null, body)).isEmpty();
        assertThat(scan("/api/profile", "GET", true, null, body)).extracting(Finding::rule).containsOnly("FIELD_TOKEN");
    }

    @Test
    void passwordHashIsFlaggedEvenOnLogin_becauseLoginMustNeverReturnIt() {
        List<Finding> findings = scan("/api/auth/login", "POST", false, null, "{\"user\":{\"passwordHash\":\"$2a$10$abcdefghijklmnopqrstuv\"}}");

        assertThat(findings).extracting(Finding::path).containsExactly("user.passwordHash");
    }

    @Test
    void jwtValueOnLoginIsAllowed_butJwtInAnOrdinaryEndpointIsFlagged() {
        String jwt = "{\"note\":\"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc\"}";

        assertThat(scan("/api/auth/login", "POST", false, null, jwt)).isEmpty();
        assertThat(scan("/api/orders", "GET", true, null, jwt)).hasSize(1);
    }

    @Test
    void valueThatOnlyEchoesTheRequestInput_isIgnored() {
        List<Finding> findings = scan("/api/users", "POST", true, "{\"password\":\"MySecret123\"}", "{\"password\":\"MySecret123\"}");

        assertThat(findings).isEmpty();
    }

    @Test
    void arraysAreCollapsedIntoOneFindingPerPath() {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 50; i++) {
            json.append(i > 0 ? "," : "").append("{\"id\":").append(i).append(",\"password\":\"pw-value-").append(i).append("\"}");
        }
        json.append("]}");

        List<Finding> findings = get(json.toString());

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.path()).isEqualTo("items[].password"));
    }

    @Test
    void limitsAndRobustness_nonJsonHugeDeepAndCapped() {
        assertThat(get("plain text with password=abc")).isEmpty();
        assertThat(get("{ not json")).isEmpty();
        assertThat(get(null)).isEmpty();
        assertThat(get("{\"x\":\"" + "a".repeat(SensitiveDataScanner.MAX_BODY_CHARS) + "\"}")).isEmpty(); // quá lớn: bỏ qua, không treo
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            deep.append("{\"n\":");
        }
        deep.append("{\"password\":\"abcdefgh\"}").append("}".repeat(40));
        assertThat(get(deep.toString())).isEmpty(); // sâu quá độ sâu tối đa
        StringBuilder many = new StringBuilder("{");
        for (int i = 0; i < 60; i++) {
            many.append(i > 0 ? "," : "").append("\"password").append(i).append("\":\"abcdefgh\"");
        }
        many.append("}");
        assertThat(get(many.toString())).hasSize(SensitiveDataScanner.MAX_FINDINGS);
    }

    // ---- che giá trị trong body lưu lại ----

    private static SensitiveDataScanner.Scan mask(String response) {
        return SensitiveDataScanner.scanAndMask("/api/users/{id}", "GET", true, null, response);
    }

    @Test
    void mask_replacesOnlySensitiveValues_keepsValidJsonAndOtherFields() {
        SensitiveDataScanner.Scan scan = mask("{\"id\":1,\"name\":\"An \\\"Binh\\\" Đ\",\"passwordHash\":\"$2a$10$abcdefghijklmnopqrstuv\",\"profile\":{\"ssn\":\"123-45-6789\"}}");

        assertThat(scan.maskedBody()).doesNotContain("$2a$").doesNotContain("123-45-6789")
                .contains("\"id\":1").contains("\"passwordHash\":\"" + SensitiveDataScanner.MASK_MARKER + "\"")
                .contains("Đ"); // unicode + dấu nháy escape giữ nguyên, vẫn là JSON hợp lệ
        assertThat(scan.sensitiveValues()).contains("123-45-6789");
        assertThat(scan.toString()).doesNotContain("123-45");
    }

    @Test
    void mask_coversEveryArrayElement_andMoreThanMaxFindings() {
        SensitiveDataScanner.Scan scan = mask("{\"items\":[{\"password\":\"secret-one\"},{\"password\":\"secret-two\"},{\"password\":\"secret-3333\"}]}");
        assertThat(scan.findings()).hasSize(1); // báo 1 lần cho items[].password
        assertThat(scan.maskedBody()).doesNotContain("secret-");

        StringBuilder many = new StringBuilder("{");
        for (int i = 0; i < 60; i++) {
            many.append(i > 0 ? "," : "").append("\"password").append(i).append("\":\"abcdefgh").append(i).append("\"");
        }
        SensitiveDataScanner.Scan big = mask(many.append("}").toString());
        assertThat(big.findings()).hasSize(SensitiveDataScanner.MAX_FINDINGS);
        assertThat(big.maskedBody()).doesNotContain("abcdefgh"); // giới hạn cảnh báo KHÔNG làm sót giá trị
    }

    @Test
    void mask_nothingToMask_orNotScannable_returnsNullBody() {
        assertThat(mask("{\"id\":1}").maskedBody()).isNull();
        assertThat(mask("plain text password=abc").maskedBody()).isNull();
        assertThat(mask("{broken").maskedBody()).isNull();
        assertThat(mask(null).maskedBody()).isNull();
    }

    @Test
    void mask_authEndpointKeepsLoginToken_stringArraysAndRootArrayAreMasked() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc123";
        assertThat(SensitiveDataScanner.scanAndMask("/api/auth/login", "POST", false, null, "{\"token\":\"" + jwt + "\"}").maskedBody()).isNull();
        SensitiveDataScanner.Scan arr = mask("[\"" + jwt + "\",{\"apiKey\":\"key-1234567\"}]");
        assertThat(arr.maskedBody()).doesNotContain("eyJ").doesNotContain("key-1234567").startsWith("[");
    }
}
