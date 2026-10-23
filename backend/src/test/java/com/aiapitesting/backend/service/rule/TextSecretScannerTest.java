package com.aiapitesting.backend.service.rule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextSecretScannerTest {

    @Test
    void detectsCommonSecretShapesWithLineNumbers_andNeverEchoesTheValue() {
        String text = String.join("\n",
                "Tài liệu nghiệp vụ",
                "api_key: sk-abcdefghijklmnop1234",
                "kết nối postgres://admin:hunter2pass@db.example.com/app",
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnopqr",
                "-----BEGIN RSA PRIVATE KEY-----",
                "AKIAABCDEFGHIJKLMNOP");

        var findings = TextSecretScanner.scan(text);

        assertThat(findings).extracting(TextSecretScanner.Finding::rule)
                .contains("PREFIXED_API_KEY", "URL_CREDENTIALS", "JWT", "PRIVATE_KEY", "AWS_ACCESS_KEY");
        assertThat(findings).extracting(TextSecretScanner.Finding::line).contains(2, 3, 4, 5, 6);
        assertThat(findings.toString()).doesNotContain("hunter2pass").doesNotContain("sk-abcdefghijklmnop1234");
    }

    @Test
    void mask_replacesSecretsButKeepsBusinessText() {
        String masked = TextSecretScanner.mask("Lương tối thiểu không được lớn hơn tối đa.\npassword=SuperSecret123\nxong");

        assertThat(masked).contains("Lương tối thiểu không được lớn hơn tối đa.").contains("xong").contains(TextSecretScanner.MASK);
        assertThat(masked).doesNotContain("SuperSecret123");
    }

    @Test
    void ordinaryBusinessProseHasNoFindings() {
        String prose = "Ngày hạn nộp hồ sơ không được ở quá khứ. Tổng trọng số các tiêu chí phải bằng 100.\n"
                + "Mật khẩu tối thiểu 8 ký tự. Token đăng nhập hết hạn sau 24 giờ.";

        assertThat(TextSecretScanner.scan(prose)).isEmpty();
        assertThat(TextSecretScanner.mask(prose)).isEqualTo(prose);
        assertThat(TextSecretScanner.scan(null)).isEmpty();
    }
}
