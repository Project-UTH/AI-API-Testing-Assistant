package com.aiapitesting.backend.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/** Authentication của token API CI: CHỈ có quyền ROLE_CI (không phải ROLE_USER/ADMIN) nên không vào được route nào ngoài /api/v1/ci/**. */
public class CiAuthentication extends AbstractAuthenticationToken {

    private final CiPrincipal principal;

    public CiAuthentication(CiPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_CI")));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null; // không giữ token thô trong context
    }

    @Override
    public CiPrincipal getPrincipal() {
        return principal;
    }
}
