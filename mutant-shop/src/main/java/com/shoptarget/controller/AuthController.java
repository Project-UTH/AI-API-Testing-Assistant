package com.shoptarget.controller;

import com.shoptarget.dto.LoginRequest;
import com.shoptarget.dto.LoginResponse;
import com.shoptarget.entity.User;
import com.shoptarget.exception.InvalidCredentialsException;
import com.shoptarget.repository.UserRepository;
import com.shoptarget.security.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new InvalidCredentialsException("Sai username hoac password"));
        if (!com.shoptarget.mutant.ShopMutants.on("S13") && !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Sai username hoac password");
        }
        String token = jwtService.generateToken(user.getUsername());
        return ResponseEntity.ok(new LoginResponse(token, jwtService.extractExpiration(token)));
    }
}
