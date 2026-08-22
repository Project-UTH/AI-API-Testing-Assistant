package com.aiapitesting.backend.config;

import com.aiapitesting.backend.security.JwtAccessDeniedHandler;
import com.aiapitesting.backend.security.JwtAuthEntryPoint;
import com.aiapitesting.backend.security.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UserDetailsService userDetailsService;
    private final JwtAuthEntryPoint jwtAuthEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(jwtAuthEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // Controller trả CompletableFuture (vd. sinh test case AI @Async) khiến Spring MVC
                        // dispatch lại request 1 lần nữa (DispatcherType.ASYNC) để hoàn tất response sau khi
                        // tác vụ nền xong. JwtAuthFilter (OncePerRequestFilter) không chạy lại ở lần dispatch
                        // này nên không có gì xác thực - phải permit riêng dispatch loại ASYNC, request gốc
                        // (DispatcherType.REQUEST) vẫn đã được xác thực đầy đủ như bình thường.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // Chỉ 2 endpoint này thật sự công khai - "/api/v1/auth/**" trước đây permitAll
                        // NGUYÊN CỤM khiến /auth/me (thêm ở Module 11) cũng bị coi là public, nhưng
                        // CurrentUserService.getCurrentUser() bên trong lại giả định luôn có người
                        // đăng nhập -> gọi không kèm token bị crash 500 (IllegalStateException lọt
                        // xuống handler Exception.class) thay vì 401 sạch qua JwtAuthEntryPoint như
                        // mọi endpoint yêu cầu auth khác. Liệt kê rõ 2 path thay vì dùng wildcard.
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        // role ROLE_ADMIN chỉ có được qua cột User.role - không có API nào trong hệ
                        // thống cấp/đổi quyền này (xem UserRole), phải tự set bằng SQL trực tiếp.
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        // Content-Disposition KHÔNG phải "simple header" - fetch() ở frontend không đọc được
        // response.headers.get("Content-Disposition") (bị ẩn mặc định dù cùng response 200 OK) trừ
        // khi expose rõ ràng ở đây. Cần cho tính năng xuất file (.xlsx Bug Report) lấy đúng tên file
        // server đặt thay vì rơi về tên mặc định ở frontend.
        configuration.setExposedHeaders(List.of("Content-Disposition"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
