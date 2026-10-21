package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.service.ai.BaselineVerificationService.Decision;
import com.aiapitesting.backend.service.ai.BaselineVerificationService.RunOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Module 17 (G2) - 401 ở endpoint đăng nhập là sai tài khoản, không phải "token hết hạn". */
class BaselineCredentialsDiagnosisTest {

    private static final RunOutcome UNAUTHORIZED = new RunOutcome(TestResultStatus.FAILED, 401, "x", null, false);
    private static final String LOGIN_BODY = "{\"email\":\"a@b.c\",\"password\":\"x\"}";

    @Test
    void credentialEndpoint_401_needsHumanWithCredentialsReason_notAuth() {
        BaselineVerificationService.Diagnosis d = BaselineVerificationService.diagnose(UNAUTHORIZED, false, true);

        assertThat(d.decision()).isEqualTo(Decision.HUMAN);
        assertThat(d.reason()).isEqualTo("CREDENTIALS");
        assertThat(d.message()).contains("tài khoản thật").contains("Yêu cầu xác thực");
    }

    @Test
    void normalEndpoint_401_stillAuth() {
        assertThat(BaselineVerificationService.diagnose(UNAUTHORIZED, false, false).reason()).isEqualTo("AUTH");
        assertThat(BaselineVerificationService.diagnose(UNAUTHORIZED, false).reason()).isEqualTo("AUTH");
    }

    @Test
    void credentialEndpoint_403_stillAuth() {
        RunOutcome forbidden = new RunOutcome(TestResultStatus.FAILED, 403, "x", null, false);
        assertThat(BaselineVerificationService.diagnose(forbidden, false, true).reason()).isEqualTo("AUTH");
    }

    @Test
    void isCredentialEndpoint_onlyForRealLoginWithPasswordAndNoTokenRequired() {
        assertThat(BaselineVerificationService.isCredentialEndpoint(false, "POST", "/auth/login", null, LOGIN_BODY)).isTrue();
        assertThat(BaselineVerificationService.isCredentialEndpoint(false, "POST", "/users", "Đăng nhập", LOGIN_BODY)).isTrue();
        // Cần token -> 401 là thiếu/sai token, không phải sai mật khẩu.
        assertThat(BaselineVerificationService.isCredentialEndpoint(true, "POST", "/auth/login", null, LOGIN_BODY)).isFalse();
        // Đăng ký / đổi mật khẩu có trường password nhưng không phải đăng nhập.
        assertThat(BaselineVerificationService.isCredentialEndpoint(false, "POST", "/auth/register", null, LOGIN_BODY)).isFalse();
        assertThat(BaselineVerificationService.isCredentialEndpoint(false, "POST", "/users/change-password", null, LOGIN_BODY)).isFalse();
        assertThat(BaselineVerificationService.isCredentialEndpoint(false, "PUT", "/auth/login", null, LOGIN_BODY)).isFalse();
        assertThat(BaselineVerificationService.isCredentialEndpoint(false, "POST", "/auth/login", null, "{\"title\":\"x\"}")).isFalse();
        assertThat(BaselineVerificationService.isCredentialEndpoint(false, "POST", "/auth/login", null, null)).isFalse();
    }
}
