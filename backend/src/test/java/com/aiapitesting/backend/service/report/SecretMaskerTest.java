package com.aiapitesting.backend.service.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretMaskerTest {

    @Test
    void masksBearerTokensJwtsAndKeyValueSecrets() {
        String masked = SecretMasker.mask("Authorization: Bearer abc.def-123 và eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig "
                + "password=hunter2 \"api_key\": \"k-9999\" token: xyz", 1000);

        assertThat(masked).doesNotContain("abc.def-123").doesNotContain("eyJhbGciOiJIUzI1NiJ9").doesNotContain("hunter2")
                .doesNotContain("k-9999").doesNotContain("xyz");
    }

    @Test
    void bearerTokenAloneKeepsTheSchemeButHidesTheValue() {
        assertThat(SecretMasker.mask("gửi Bearer abc.def-123 thất bại", 100)).isEqualTo("gửi Bearer *** thất bại");
    }

    @Test
    void truncatesLongTextAndDropsControlCharacters() {
        String masked = SecretMasker.mask("a\u0000b\u0007c" + "x".repeat(500), 50);

        assertThat(masked).hasSize(50).endsWith("…").doesNotContain("\u0000").doesNotContain("\u0007");
    }

    @Test
    void nullStaysNull_andPlainTextUntouched() {
        assertThat(SecretMasker.mask(null, 10)).isNull();
        assertThat(SecretMasker.mask("Mã trả về 500", 100)).isEqualTo("Mã trả về 500");
    }
}
