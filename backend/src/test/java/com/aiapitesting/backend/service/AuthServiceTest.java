package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.LoginRequest;
import com.aiapitesting.backend.dto.response.AuthResponse;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.entity.UserRole;
import com.aiapitesting.backend.exception.AccountDisabledException;
import com.aiapitesting.backend.exception.InvalidCredentialsException;
import com.aiapitesting.backend.repository.UserRepository;
import com.aiapitesting.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

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
}
