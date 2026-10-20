package com.aiapitesting.backend.config;

import com.aiapitesting.backend.security.ApiTokenAuthFilter;
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
    private final ApiTokenAuthFilter apiTokenAuthFilter;
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
                        // Controller trả CompletableFuture khiến Spring MVC dispatch lại request
                        // (DispatcherType.ASYNC) sau khi tác vụ nền xong - JwtAuthFilter không chạy
                        // lại ở lần dispatch này nên phải permit riêng.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // Chỉ các endpoint này thật sự công khai - liệt kê rõ path thay vì wildcard
                        // "/api/v1/auth/**", để không vô tình public luôn /auth/me hay /change-password.
                        // forgot-password/reset-password buộc phải public vì đây LÀ luồng dành cho
                        // người dùng CHƯA đăng nhập được (quên mật khẩu).
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/google",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/reset-password"
                        ).permitAll()
                        // role ADMIN chỉ có được qua cột User.role, không có API nào cấp/đổi quyền này.
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // Module 17 (17d) - route cho CI chỉ nhận token API (ROLE_CI), JWT thường vào đây nhận 403.
                        .requestMatchers("/api/v1/ci/**").hasRole("CI")
                        // Mọi route còn lại chỉ dành cho người dùng đăng nhập (ROLE_USER/ADMIN): token CI (chỉ có ROLE_CI) vào đây nhận 403.
                        .anyRequest().hasAnyRole("USER", "ADMIN"))
                // 2 filter loại trừ nhau theo đường dẫn (/api/v1/ci/** chỉ ApiTokenAuthFilter, còn lại chỉ JwtAuthFilter).
                .addFilterBefore(apiTokenAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        // Content-Disposition không phải "simple header" - fetch() ở frontend không đọc được trừ
        // khi expose rõ ràng, cần cho tính năng xuất file lấy đúng tên server đặt.
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
