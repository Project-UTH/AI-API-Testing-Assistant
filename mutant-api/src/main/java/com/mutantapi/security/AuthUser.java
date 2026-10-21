package com.mutantapi.security;

/** Nguoi dung da xac thuc cua request hien tai. id = 0 + role ANONYMOUS chi xuat hien khi mutant M18 bat. */
public record AuthUser(long id, String role) {

    public boolean isHr() {
        return "HR".equals(role);
    }

    public boolean isCandidate() {
        return "CANDIDATE".equals(role);
    }
}
