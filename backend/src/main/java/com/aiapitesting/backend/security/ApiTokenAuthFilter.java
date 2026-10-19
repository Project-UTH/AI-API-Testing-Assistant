package com.aiapitesting.backend.security;

import com.aiapitesting.backend.dto.response.ApiErrorResponse;
import com.aiapitesting.backend.service.ci.ApiTokenService;
import com.aiapitesting.backend.service.ci.CiRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Optional;

/**
 * Module 17 (17d) - xác thực token API CI (header {@code X-Api-Token}) cho các route {@code /api/v1/ci/**}. Loại trừ nhau với
 * {@link JwtAuthFilter} theo đường dẫn: JWT không bao giờ xác thực ở /ci/**, token CI không bao giờ xác thực ở route khác. Chỉ nhận
 * token qua HEADER (không query param - tránh lộ vào log/URL). Thất bại → để chuỗi lọc đi tiếp không có Authentication → 401 chung.
 */
@Component
@RequiredArgsConstructor
public class ApiTokenAuthFilter extends OncePerRequestFilter {

    public static final String CI_PATH_PREFIX = "/api/v1/ci/";
    static final String HEADER = "X-Api-Token";
    /** Tối đa số lần xác thực SAI mỗi IP mỗi phút - chống dò token. */
    static final int MAX_FAILED_PER_IP_PER_MINUTE = 20;

    private final ApiTokenService apiTokenService;
    private final CiRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith(CI_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        if (raw == null || raw.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        Optional<CiPrincipal> principal;
        try {
            principal = apiTokenService.authenticate(raw.trim());
        } catch (Exception e) {
            principal = Optional.empty(); // lỗi hạ tầng khi tra token: coi như không xác thực (không lộ chi tiết)
        }
        if (principal.isPresent()) {
            SecurityContextHolder.getContext().setAuthentication(new CiAuthentication(principal.get()));
            filterChain.doFilter(request, response);
            return;
        }
        SecurityContextHolder.clearContext();
        if (!rateLimiter.tryAcquire("ci-auth-fail:" + request.getRemoteAddr(), MAX_FAILED_PER_IP_PER_MINUTE)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setCharacterEncoding("UTF-8");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of("TOO_MANY_REQUESTS", "Quá nhiều lần xác thực sai, thử lại sau 1 phút"));
            return;
        }
        filterChain.doFilter(request, response); // entry point trả 401 chung
    }
}
