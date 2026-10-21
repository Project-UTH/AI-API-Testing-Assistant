package com.mutantapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mutantapi.mutant.MutantFlag;
import com.mutantapi.mutant.Mutants;
import com.mutantapi.mutant.RuleContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Moi /api/** can Bearer token hop le. Khong dung Spring Security - toan bo xac thuc o day de mutant M18 rẽ nhanh duoc. */
@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "auth";
    private static final Pattern JOB_BY_ID = Pattern.compile("^/api/jobs/\\d+$");

    private final TokenService tokens;
    private final Mutants mutants;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthFilter(TokenService tokens, Mutants mutants) {
        this.tokens = tokens;
        this.mutants = mutants;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<AuthUser> user = Optional.empty();
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            user = tokens.verify(header.substring(7).trim());
        }
        if (user.isEmpty() && mutants.on(MutantFlag.M18) && "GET".equals(request.getMethod())
                && JOB_BY_ID.matcher(request.getRequestURI()).matches()) {
            user = Optional.of(new AuthUser(0, "ANONYMOUS"));
        }
        // M31: /api/users/me bo kiem tra xac thuc (khong token van duoc vao, coi nhu khach).
        if (user.isEmpty() && mutants.on(MutantFlag.M31) && "GET".equals(request.getMethod())
                && "/api/users/me".equals(request.getRequestURI())) {
            user = Optional.of(new AuthUser(0, "ANONYMOUS"));
        }
        // Cong tac quy tac "auth": bo kiem tra xac thuc o endpoint nay (khong header = missing, token sai = invalid) -> coi nhu khach.
        if (user.isEmpty()) {
            String endpoint = RuleContext.keyOf(request.getMethod(), request.getRequestURI());
            if (Mutants.ruleOff(endpoint, "auth", header == null ? "missing" : "invalid")) {
                user = Optional.of(new AuthUser(0, "ANONYMOUS"));
            }
        }
        if (user.isEmpty()) {
            response.setStatus(401);
            response.setContentType("application/json");
            objectMapper.writeValue(response.getOutputStream(),
                    Map.of("code", "UNAUTHORIZED", "message", "Thieu hoac sai token"));
            return;
        }
        request.setAttribute(ATTRIBUTE, user.get());
        chain.doFilter(request, response);
    }
}
