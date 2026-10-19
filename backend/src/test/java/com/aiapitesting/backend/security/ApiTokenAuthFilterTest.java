package com.aiapitesting.backend.security;

import com.aiapitesting.backend.service.ci.ApiTokenService;
import com.aiapitesting.backend.service.ci.CiRateLimiter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Hai filter xác thực loại trừ nhau theo đường dẫn và token CI chỉ có ROLE_CI (Module 17 - 17d). */
class ApiTokenAuthFilterTest {

    private final ApiTokenService tokenService = mock(ApiTokenService.class);
    private final CiRateLimiter rateLimiter = mock(CiRateLimiter.class);
    private final ApiTokenAuthFilter filter = new ApiTokenAuthFilter(tokenService, rateLimiter, new ObjectMapper());
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String uri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (token != null) {
            request.addHeader("X-Api-Token", token);
        }
        return request;
    }

    @Test
    void onlyHandlesCiPaths() {
        assertThat(filter.shouldNotFilter(request("/api/v1/projects", null))).isTrue();
        assertThat(filter.shouldNotFilter(request("/api/v1/ci/projects/x/runs", null))).isFalse();
    }

    @Test
    void jwtFilterNeverRunsOnCiPaths() {
        JwtAuthFilter jwtFilter = new JwtAuthFilter(mock(JwtService.class), mock(CustomUserDetailsService.class));

        assertThat(jwtFilter.shouldNotFilter(request("/api/v1/ci/projects/x/runs", null))).isTrue();
        assertThat(jwtFilter.shouldNotFilter(request("/api/v1/projects", null))).isFalse();
    }

    @Test
    void validToken_setsCiAuthenticationWithOnlyRoleCi() throws Exception {
        CiPrincipal principal = new CiPrincipal(UUID.randomUUID(), UUID.randomUUID());
        when(tokenService.authenticate("ci_good")).thenReturn(Optional.of(principal));

        filter.doFilterInternal(request("/api/v1/ci/x", "ci_good"), new MockHttpServletResponse(), chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isInstanceOf(CiAuthentication.class);
        assertThat(auth.getPrincipal()).isEqualTo(principal);
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_CI");
        assertThat(auth.getCredentials()).isNull(); // token thô không nằm trong context
        verify(chain).doFilter(any(), any());
    }

    @Test
    void invalidToken_leavesNoAuthentication_andContinuesSoTheEntryPointAnswers401() throws Exception {
        when(tokenService.authenticate("ci_bad")).thenReturn(Optional.empty());
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request("/api/v1/ci/x", "ci_bad"), response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200); // chưa đặt mã: do entry point trả 401 ở bước sau
    }

    @Test
    void tooManyFailedAttemptsFromOneIp_getsTooManyRequests_withoutReachingTheChain() throws Exception {
        when(tokenService.authenticate("ci_bad")).thenReturn(Optional.empty());
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request("/api/v1/ci/x", "ci_bad"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void missingHeader_justContinues() throws Exception {
        filter.doFilterInternal(request("/api/v1/ci/x", null), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(tokenService, never()).authenticate(any());
        verify(chain).doFilter(any(), any());
    }
}
