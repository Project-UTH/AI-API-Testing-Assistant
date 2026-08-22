package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.request.LoginRequest;
import com.aiapitesting.backend.dto.request.RegisterRequest;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.AuthResponse;
import com.aiapitesting.backend.dto.response.UserInfoResponse;
import com.aiapitesting.backend.service.AuthService;
import com.aiapitesting.backend.service.CurrentUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CurrentUserService currentUserService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * Cho phép frontend re-check role hiện tại (VD sau khi 1 admin cấp/thu quyền bằng SQL trực
     * tiếp trong lúc người dùng đang có phiên đăng nhập cũ) mà không cần đăng nhập lại - luôn đọc
     * fresh từ DB qua CurrentUserService, không đọc từ claim JWT (JWT chỉ mang email).
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserInfoResponse>> me() {
        return ResponseEntity.ok(ApiResponse.of(UserInfoResponse.from(currentUserService.getCurrentUser())));
    }
}
