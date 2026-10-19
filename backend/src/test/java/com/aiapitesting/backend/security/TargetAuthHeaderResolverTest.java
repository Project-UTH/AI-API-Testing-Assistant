package com.aiapitesting.backend.security;

import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.security.TargetAuthHeaderResolver.AuthHeader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Hỗ trợ nhiều kiểu xác thực của API đích (không chỉ Bearer): Basic, header tuỳ ý, X-API-Key. */
class TargetAuthHeaderResolverTest {

    private final TargetAuthHeaderResolver resolver = new TargetAuthHeaderResolver();

    @Test
    void bearerApiKeyAndNone_keepWorking() {
        assertThat(resolver.resolve(TargetAuthType.BEARER_TOKEN, "abc")).isEqualTo(new AuthHeader("Authorization", "Bearer abc"));
        assertThat(resolver.resolve(TargetAuthType.API_KEY, "k")).isEqualTo(new AuthHeader("X-API-Key", "k"));
        assertThat(resolver.resolve(TargetAuthType.NONE, "x")).isNull();
        assertThat(resolver.resolve(null, "x")).isNull();
    }

    @Test
    void basicAuth_sendsBase64OfUserAndPassword_includingUnicodeAndColonsInPassword() {
        assertThat(resolver.resolve(TargetAuthType.BASIC_AUTH, "user:pass")).isEqualTo(new AuthHeader("Authorization", "Basic dXNlcjpwYXNz"));
        assertThat(resolver.resolve(TargetAuthType.BASIC_AUTH, "u:p:q").value()).isEqualTo("Basic dTpwOnE=");
    }

    @Test
    void customHeader_usesTheGivenNameAndValue() {
        assertThat(resolver.resolve(TargetAuthType.CUSTOM_HEADER, "X-Auth-Token: abc123")).isEqualTo(new AuthHeader("X-Auth-Token", "abc123"));
        assertThat(resolver.resolve(TargetAuthType.CUSTOM_HEADER, "api-key:  spaced value ")).isEqualTo(new AuthHeader("api-key", "spaced value"));
        assertThat(resolver.resolve(TargetAuthType.CUSTOM_HEADER, "Authorization: Token t0k")).isEqualTo(new AuthHeader("Authorization", "Token t0k"));
    }

    @Test
    void customHeaderValidation_rejectsMissingNameValueOrInjection() {
        assertThat(TargetAuthHeaderResolver.isValidCustomHeaderValue("X-Key: v")).isTrue();
        assertThat(TargetAuthHeaderResolver.isValidCustomHeaderValue("no-colon")).isFalse();
        assertThat(TargetAuthHeaderResolver.isValidCustomHeaderValue(": value")).isFalse();
        assertThat(TargetAuthHeaderResolver.isValidCustomHeaderValue("X-Key:")).isFalse();
        assertThat(TargetAuthHeaderResolver.isValidCustomHeaderValue("Bad Name: v")).isFalse();
        assertThat(TargetAuthHeaderResolver.isValidCustomHeaderValue("X-Key: v\r\nX-Evil: 1")).isFalse();
        assertThat(TargetAuthHeaderResolver.isValidCustomHeaderValue(null)).isFalse();
        assertThat(resolver.resolve(TargetAuthType.CUSTOM_HEADER, "garbage")).isNull();
    }

    @Test
    void invalidCredential_keepsTheSameHeaderNameSoSecurityTestsStayMeaningful() {
        assertThat(resolver.resolveInvalid(TargetAuthType.CUSTOM_HEADER, "X-Auth-Token: real", "bad")).isEqualTo(new AuthHeader("X-Auth-Token", "bad"));
        assertThat(resolver.resolveInvalid(TargetAuthType.BEARER_TOKEN, null, "bad")).isEqualTo(new AuthHeader("Authorization", "Bearer bad"));
        assertThat(resolver.resolveInvalid(TargetAuthType.BASIC_AUTH, null, "bad").value()).startsWith("Basic ");
    }

    @Test
    void queryParamAuth_isSentAsQueryParameter_validatedAndKeepsNameWhenInvalid() {
        AuthHeader header = resolver.resolve(TargetAuthType.QUERY_PARAM, "api_key=k-123");
        assertThat(header.query()).isTrue();
        assertThat(header.name()).isEqualTo("api_key");
        assertThat(header.value()).isEqualTo("k-123");
        assertThat(resolver.resolve(TargetAuthType.QUERY_PARAM, "token=a=b").value()).isEqualTo("a=b"); // dấu = trong giá trị giữ nguyên

        assertThat(TargetAuthHeaderResolver.isValidQueryParamValue("key=v")).isTrue();
        assertThat(TargetAuthHeaderResolver.isValidQueryParamValue("no-equals")).isFalse();
        assertThat(TargetAuthHeaderResolver.isValidQueryParamValue("=v")).isFalse();
        assertThat(TargetAuthHeaderResolver.isValidQueryParamValue("k=")).isFalse();
        assertThat(TargetAuthHeaderResolver.isValidQueryParamValue("bad name=v")).isFalse();
        assertThat(TargetAuthHeaderResolver.isValidQueryParamValue("k=v\r\nX: 1")).isFalse();

        AuthHeader invalid = resolver.resolveInvalid(TargetAuthType.QUERY_PARAM, "api_key=real", "bad");
        assertThat(invalid.query()).isTrue();
        assertThat(invalid.name()).isEqualTo("api_key");
        assertThat(invalid.value()).isEqualTo("bad");
    }
}
