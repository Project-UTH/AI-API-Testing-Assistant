package com.mutantapi.mutant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Ghi lai "METHOD /duong/dan/{id}" cua request dang xu ly de {@code Input} biet kiem tra thuoc endpoint nao. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RuleContext extends OncePerRequestFilter {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    public static String endpoint() {
        return CURRENT.get();
    }

    public static String keyOf(String method, String uri) {
        return method + " " + uri.replaceAll("/\\d+(?=/|$)", "/{id}");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CURRENT.set(keyOf(request.getMethod(), request.getRequestURI()));
        try {
            chain.doFilter(request, response);
        } finally {
            CURRENT.remove();
        }
    }
}
