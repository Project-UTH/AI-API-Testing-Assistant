package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.ChangePasswordRequest;
import com.aiapitesting.backend.dto.request.ForgotPasswordRequest;
import com.aiapitesting.backend.dto.request.LoginRequest;
import com.aiapitesting.backend.dto.request.ResetPasswordRequest;
import com.aiapitesting.backend.dto.response.AuthResponse;
import com.aiapitesting.backend.entity.AuthProvider;
import com.aiapitesting.backend.entity.PasswordResetOtp;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.entity.UserRole;
import com.aiapitesting.backend.exception.AccountDisabledException;
import com.aiapitesting.backend.exception.GoogleAuthFailedException;
import com.aiapitesting.backend.exception.InvalidCredentialsException;
import com.aiapitesting.backend.exception.InvalidCurrentPasswordException;
import com.aiapitesting.backend.exception.InvalidResetCodeException;
import com.aiapitesting.backend.repository.PasswordResetOtpRepository;
import com.aiapitesting.backend.repository.UserRepository;
import com.aiapitesting.backend.security.GoogleTokenVerifierService;
import com.aiapitesting.backend.security.JwtService;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordResetOtpRepository passwordResetOtpRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private EmailService emailService;

    @Mock
    private GoogleTokenVerifierService googleTokenVerifierService;

    @InjectMocks
    private AuthService authService;

    @Test
    void login_disabledAccount_throwsAccountDisabledBeforeIssuingToken() {
        User user = User.builder().id(UUID.randomUUID()).email("locked@test.com")
                .password("hashed").role(UserRole.USER).enabled(false).build();
        when(userRepository.findByEmail("locked@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "hashed")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("locked@test.com", "password")))
                .isInstanceOf(AccountDisabledException.class);
    }

    @Test
    void login_validEnabledAccount_returnsTokenWithCurrentRole() {
        User user = User.builder().id(UUID.randomUUID()).email("admin@test.com")
                .password("hashed").role(UserRole.ADMIN).enabled(true).build();
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "hashed")).thenReturn(true);
        when(jwtService.generateToken("admin@test.com")).thenReturn("jwt-token");

        AuthResponse response = authService.login(new LoginRequest("admin@test.com", "password"));

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials_notLeakingWhichFieldWasWrong() {
        User user = User.builder().id(UUID.randomUUID()).email("user@test.com")
                .password("hashed").role(UserRole.USER).enabled(true).build();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@test.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void changePassword_correctCurrentPassword_encodesAndSavesNewPassword() {
        User user = User.builder().id(UUID.randomUUID()).email("user@test.com")
                .password("old-hashed").role(UserRole.USER).enabled(true).build();
        when(passwordEncoder.matches("oldPass123", "old-hashed")).thenReturn(true);
        when(passwordEncoder.encode("newPass123")).thenReturn("new-hashed");

        authService.changePassword(user, new ChangePasswordRequest("oldPass123", "newPass123"));

        assertThat(user.getPassword()).isEqualTo("new-hashed");
        verify(userRepository).save(user);
    }

    @Test
    void changePassword_wrongCurrentPassword_throwsInvalidCurrentPassword_doesNotSave() {
        User user = User.builder().id(UUID.randomUUID()).email("user@test.com")
                .password("old-hashed").role(UserRole.USER).enabled(true).build();
        when(passwordEncoder.matches("wrongPass", "old-hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(user, new ChangePasswordRequest("wrongPass", "newPass123")))
                .isInstanceOf(InvalidCurrentPasswordException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_accountWithoutRealPassword_skipsCurrentPasswordCheck_marksPasswordSet() {
        User user = User.builder().id(UUID.randomUUID()).email("googleuser@test.com")
                .password("random-uuid-hashed").role(UserRole.USER).enabled(true)
                .authProvider(AuthProvider.GOOGLE).passwordSet(false).build();
        when(passwordEncoder.encode("newPass123")).thenReturn("new-hashed");

        authService.changePassword(user, new ChangePasswordRequest(null, "newPass123"));

        assertThat(user.getPassword()).isEqualTo("new-hashed");
        assertThat(user.isPasswordSet()).isTrue();
        verify(userRepository).save(user);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void loginWithGoogle_newEmail_createsGoogleOnlyUserWithoutRealPassword() {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("google-sub-1");
        payload.setEmail("newgoogle@test.com");
        when(googleTokenVerifierService.verify("valid-token")).thenReturn(payload);
        when(userRepository.findByGoogleId("google-sub-1")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("newgoogle@test.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("random-hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateToken("newgoogle@test.com")).thenReturn("jwt-token");

        AuthResponse response = authService.loginWithGoogle("valid-token");

        assertThat(response.token()).isEqualTo("jwt-token");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("newgoogle@test.com");
        assertThat(saved.getGoogleId()).isEqualTo("google-sub-1");
        assertThat(saved.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(saved.isPasswordSet()).isFalse();
    }

    @Test
    void loginWithGoogle_existingGoogleId_reusesAccountWithoutReSaving() {
        User user = User.builder().id(UUID.randomUUID()).email("googleuser@test.com")
                .password("hashed").role(UserRole.USER).enabled(true)
                .authProvider(AuthProvider.GOOGLE).googleId("google-sub-1").passwordSet(false).build();
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("google-sub-1");
        payload.setEmail("googleuser@test.com");
        when(googleTokenVerifierService.verify("valid-token")).thenReturn(payload);
        when(userRepository.findByGoogleId("google-sub-1")).thenReturn(Optional.of(user));
        when(jwtService.generateToken("googleuser@test.com")).thenReturn("jwt-token");

        AuthResponse response = authService.loginWithGoogle("valid-token");

        assertThat(response.token()).isEqualTo("jwt-token");
        verify(userRepository, never()).save(any());
    }

    @Test
    void loginWithGoogle_matchingLocalEmail_autoLinksGoogleIdWithoutExtraVerification() {
        User localUser = User.builder().id(UUID.randomUUID()).email("local@test.com")
                .password("local-hashed").role(UserRole.USER).enabled(true)
                .authProvider(AuthProvider.LOCAL).passwordSet(true).build();
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("google-sub-2");
        payload.setEmail("local@test.com");
        when(googleTokenVerifierService.verify("valid-token")).thenReturn(payload);
        when(userRepository.findByGoogleId("google-sub-2")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("local@test.com")).thenReturn(Optional.of(localUser));
        when(jwtService.generateToken("local@test.com")).thenReturn("jwt-token");

        authService.loginWithGoogle("valid-token");

        assertThat(localUser.getGoogleId()).isEqualTo("google-sub-2");
        assertThat(localUser.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(localUser.isPasswordSet()).isTrue();
        verify(userRepository).save(localUser);
    }

    @Test
    void loginWithGoogle_disabledAccount_throwsAccountDisabledBeforeIssuingToken() {
        User user = User.builder().id(UUID.randomUUID()).email("locked@test.com")
                .password("hashed").role(UserRole.USER).enabled(false)
                .authProvider(AuthProvider.GOOGLE).googleId("google-sub-3").passwordSet(false).build();
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("google-sub-3");
        payload.setEmail("locked@test.com");
        when(googleTokenVerifierService.verify("valid-token")).thenReturn(payload);
        when(userRepository.findByGoogleId("google-sub-3")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.loginWithGoogle("valid-token"))
                .isInstanceOf(AccountDisabledException.class);

        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void loginWithGoogle_invalidToken_propagatesWithoutTouchingRepository() {
        when(googleTokenVerifierService.verify("bad-token"))
                .thenThrow(new GoogleAuthFailedException("Token Google không hợp lệ hoặc đã hết hạn"));

        assertThatThrownBy(() -> authService.loginWithGoogle("bad-token"))
                .isInstanceOf(GoogleAuthFailedException.class);

        verify(userRepository, never()).findByGoogleId(any());
        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void forgotPassword_existingEmail_invalidatesOldOtpAndSendsNewOne() {
        User user = User.builder().id(UUID.randomUUID()).email("user@test.com")
                .password("hashed").role(UserRole.USER).enabled(true).build();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(any())).thenReturn("otp-hashed");

        authService.forgotPassword(new ForgotPasswordRequest("user@test.com"));

        verify(passwordResetOtpRepository).deleteAllUnusedByUser(user);
        verify(passwordResetOtpRepository).save(any(PasswordResetOtp.class));
        verify(emailService).sendPasswordResetOtp(org.mockito.ArgumentMatchers.eq("user@test.com"), any());
    }

    @Test
    void forgotPassword_unknownEmail_doesNothing_doesNotLeakWhichEmailsExist() {
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        authService.forgotPassword(new ForgotPasswordRequest("ghost@test.com"));

        verify(passwordResetOtpRepository, never()).deleteAllUnusedByUser(any());
        verify(passwordResetOtpRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetOtp(any(), any());
    }

    @Test
    void resetPassword_correctOtp_updatesPasswordAndMarksOtpUsed() {
        User user = User.builder().id(UUID.randomUUID()).email("user@test.com")
                .password("old-hashed").role(UserRole.USER).enabled(true).build();
        PasswordResetOtp otp = PasswordResetOtp.builder().id(UUID.randomUUID()).user(user)
                .otpHash("otp-hashed").expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .attemptCount(0).used(false).build();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordResetOtpRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user))
                .thenReturn(Optional.of(otp));
        when(passwordEncoder.matches("123456", "otp-hashed")).thenReturn(true);
        when(passwordEncoder.encode("newPass123")).thenReturn("new-hashed");

        authService.resetPassword(new ResetPasswordRequest("user@test.com", "123456", "newPass123"));

        assertThat(user.getPassword()).isEqualTo("new-hashed");
        assertThat(otp.isUsed()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    void resetPassword_wrongOtp_incrementsAttemptCount_throwsGenericError_doesNotChangePassword() {
        User user = User.builder().id(UUID.randomUUID()).email("user@test.com")
                .password("old-hashed").role(UserRole.USER).enabled(true).build();
        PasswordResetOtp otp = PasswordResetOtp.builder().id(UUID.randomUUID()).user(user)
                .otpHash("otp-hashed").expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .attemptCount(0).used(false).build();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordResetOtpRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user))
                .thenReturn(Optional.of(otp));
        when(passwordEncoder.matches("wrongOtp", "otp-hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest("user@test.com", "wrongOtp", "newPass123")))
                .isInstanceOf(InvalidResetCodeException.class);

        assertThat(otp.getAttemptCount()).isEqualTo(1);
        assertThat(user.getPassword()).isEqualTo("old-hashed");
        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_expiredOtp_throwsGenericError() {
        User user = User.builder().id(UUID.randomUUID()).email("user@test.com")
                .password("old-hashed").role(UserRole.USER).enabled(true).build();
        PasswordResetOtp otp = PasswordResetOtp.builder().id(UUID.randomUUID()).user(user)
                .otpHash("otp-hashed").expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .attemptCount(0).used(false).build();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordResetOtpRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user))
                .thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest("user@test.com", "123456", "newPass123")))
                .isInstanceOf(InvalidResetCodeException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_tooManyAttempts_throwsGenericError_evenIfOtpStillCorrect() {
        User user = User.builder().id(UUID.randomUUID()).email("user@test.com")
                .password("old-hashed").role(UserRole.USER).enabled(true).build();
        PasswordResetOtp otp = PasswordResetOtp.builder().id(UUID.randomUUID()).user(user)
                .otpHash("otp-hashed").expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .attemptCount(5).used(false).build();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordResetOtpRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user))
                .thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest("user@test.com", "123456", "newPass123")))
                .isInstanceOf(InvalidResetCodeException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_unknownEmail_throwsSameGenericError_notLeakingEmailExistence() {
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest("ghost@test.com", "123456", "newPass123")))
                .isInstanceOf(InvalidResetCodeException.class)
                .hasMessage("Mã xác nhận không đúng hoặc đã hết hạn");
    }
}
