package com.mutantapi.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Service
public class TokenService {

    private final SecretKey key;
    private final long expirationMs;

    public TokenService(@Value("${app.jwt.secret}") String secret, @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String issue(long userId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key)
                .compact();
    }

    public Date expiryOf(String token) {
        return parse(token).map(Claims::getExpiration).orElse(null);
    }

    public Optional<AuthUser> verify(String token) {
        return parse(token).map(c -> new AuthUser(Long.parseLong(c.getSubject()), c.get("role", String.class)));
    }

    /**
     * CHI dung cho mutant H07 (held-out): decode payload JWT (sub, role, exp) KHONG xac minh chu ky.
     * Ban chuan luon dung verify().
     */
    public Optional<AuthUser> decodeUnverified(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            com.fasterxml.jackson.databind.JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(java.util.Base64.getUrlDecoder().decode(parts[1]));
            if (!payload.hasNonNull("sub") || !payload.hasNonNull("role") || !payload.hasNonNull("exp")
                    || payload.get("exp").asLong() * 1000 < System.currentTimeMillis()) {
                return Optional.empty();
            }
            return Optional.of(new AuthUser(Long.parseLong(payload.get("sub").asText()), payload.get("role").asText()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
