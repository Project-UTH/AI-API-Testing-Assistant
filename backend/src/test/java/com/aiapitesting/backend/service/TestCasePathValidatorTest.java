package com.aiapitesting.backend.service;

import com.aiapitesting.backend.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class TestCasePathValidatorTest {

    private final TestCasePathValidator validator = new TestCasePathValidator();

    @Test
    void validate_pathWithoutParams_passes() {
        assertThatNoException().isThrownBy(() ->
                validator.validate("/pets", null, InvalidRequestException::new));
    }

    @Test
    void validate_doubleBraceTokenWithFallback_passes() {
        assertThatNoException().isThrownBy(() ->
                validator.validate("/pet/{{petId}}", "{\"petId\":\"1\"}", InvalidRequestException::new));
    }

    @Test
    void validate_doubleBraceTokenWithoutFallback_throws() {
        assertThatThrownBy(() -> validator.validate("/pet/{{petId}}", null, InvalidRequestException::new))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void validate_leftoverSingleBraceToken_throws() {
        assertThatThrownBy(() -> validator.validate("/pet/{petId}", null, InvalidRequestException::new))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void validate_concreteInvalidValueInsteadOfToken_passes() {
        // Negative/Boundary test case về chính tham số path - dùng giá trị cụ thể, không cần fallback
        assertThatNoException().isThrownBy(() ->
                validator.validate("/pet/999999999", null, InvalidRequestException::new));
    }

    @Test
    void validate_invalidFallbackJson_throws() {
        assertThatThrownBy(() -> validator.validate("/pet/{{petId}}", "not json", InvalidRequestException::new))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void extractPlaceholders_returnsAllDistinctParamNames() {
        assertThat(validator.extractPlaceholders("/owner/{{ownerId}}/pet/{{petId}}"))
                .containsExactly("ownerId", "petId");
    }

    @Test
    void extractPlaceholders_noTokens_returnsEmpty() {
        assertThat(validator.extractPlaceholders("/pets")).isEmpty();
    }

    @Test
    void extractPlaceholders_nullText_returnsEmpty() {
        assertThat(validator.extractPlaceholders(null)).isEmpty();
    }
}
