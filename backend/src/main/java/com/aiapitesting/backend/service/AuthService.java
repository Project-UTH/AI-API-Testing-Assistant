package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.LoginRequest;
import com.aiapitesting.backend.dto.request.RegisterRequest;
import com.aiapitesting.backend.dto.response.AuthResponse;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.EmailAlreadyExistsException;
import com.aiapitesting.backend.exception.InvalidCredentialsException;
import com.aiapitesting.backend.repository.UserRepository;
import com.aiapitesting.backend.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException("Email đã được đăng ký");
        }

        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .build();
        userRepository.save(user);

        String token = jwtService.generateToken(user.getEmail());
        return new AuthResponse(token, user.getEmail());
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidCredentialsException("Sai email hoặc mật khẩu"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new InvalidCredentialsException("Sai email hoặc mật khẩu");
        }

        String token = jwtService.generateToken(user.getEmail());
        return new AuthResponse(token, user.getEmail());
    }
}
