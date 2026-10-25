package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.TestCase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExpectedStatusTest {

    private TestCase exact(int expected) {
        return TestCase.builder().expectedStatus(expected).build();
    }

    private TestCase rejected() {
        return TestCase.builder().expectedStatus(400).expectedStatusClass(ExpectedStatus.REJECTED).build();
    }

    @Test
    void exact_matchesOnlyTheSameCode() {
        assertThat(ExpectedStatus.matches(exact(201), 201)).isTrue();
        assertThat(ExpectedStatus.matches(exact(201), 200)).isFalse();
        assertThat(ExpectedStatus.matches(exact(400), null)).isFalse();
    }

    @Test
    void rejected_matchesInputRejectionCodes_butNotCallingErrors() {
        for (int ok : new int[]{400, 409, 410, 412, 413, 422, 423, 428}) {
            assertThat(ExpectedStatus.matches(rejected(), ok)).as("mã %d", ok).isTrue();
        }
        for (int notRule : new int[]{401, 403, 404, 405, 415, 429}) {
            assertThat(ExpectedStatus.matches(rejected(), notRule)).as("mã %d", notRule).isFalse();
        }
    }

    private TestCase security(int expected, com.aiapitesting.backend.entity.TestCaseAuthOverride override) {
        return TestCase.builder().expectedStatus(expected).authOverride(override).build();
    }

    @Test
    void apiThatAnswers403WhenTokenIsMissingOrWrong_isAcceptedOnlyForSecurityTestsExpecting401() {
        var none = com.aiapitesting.backend.entity.TestCaseAuthOverride.NONE;
        var invalid = com.aiapitesting.backend.entity.TestCaseAuthOverride.INVALID;
        // mặc định (401 là chuẩn): 403 vẫn là Fail
        assertThat(ExpectedStatus.matches(security(401, none), 403)).isFalse();
        assertThat(ExpectedStatus.matches(security(401, none), 403, null)).isFalse();
        assertThat(ExpectedStatus.matches(security(401, none), 403, 401)).isFalse();
        // đã khai quy ước 403: chỉ test cố ý bỏ/làm sai token kỳ vọng 401 được nhận 403
        assertThat(ExpectedStatus.matches(security(401, none), 403, 403)).isTrue();
        assertThat(ExpectedStatus.matches(security(401, invalid), 403, 403)).isTrue();
        // nhưng không nới sang test khác: sai mật khẩu đăng nhập (token mặc định), kỳ vọng khác 401, hoặc mã khác
        assertThat(ExpectedStatus.matches(security(401, com.aiapitesting.backend.entity.TestCaseAuthOverride.DEFAULT), 403, 403)).isFalse();
        assertThat(ExpectedStatus.matches(security(400, none), 403, 403)).isFalse();
        assertThat(ExpectedStatus.matches(security(401, none), 404, 403)).isFalse();
        assertThat(ExpectedStatus.matches(security(401, none), 200, 403)).isFalse(); // API không chặn vẫn là Fail
        assertThat(ExpectedStatus.matches(security(401, none), 401, 403)).isTrue();
    }

    @Test
    void rejected_doesNotMatchSuccessOrServerErrors() {
        for (int code : new int[]{200, 201, 204, 302, 500, 502, 503}) {
            assertThat(ExpectedStatus.matches(rejected(), code)).as("mã %d", code).isFalse();
        }
    }

    @Test
    void fingerprint_changesOnlyWhenClassOrSendTwiceIsSet() {
        TestCase plain = TestCase.builder().expectedStatus(400).resolvedPath("/a").build();
        String base = TestCaseFingerprint.of(plain, java.util.List.of(), java.util.List.of());
        assertThat(TestCaseFingerprint.of(plain, java.util.List.of(), java.util.List.of())).isEqualTo(base);

        TestCase withClass = TestCase.builder().expectedStatus(400).resolvedPath("/a").expectedStatusClass(ExpectedStatus.REJECTED).build();
        TestCase twice = TestCase.builder().expectedStatus(400).resolvedPath("/a").sendTwice(true).build();
        assertThat(TestCaseFingerprint.of(withClass, java.util.List.of(), java.util.List.of())).isNotEqualTo(base);
        assertThat(TestCaseFingerprint.of(twice, java.util.List.of(), java.util.List.of())).isNotEqualTo(base);
    }
}
