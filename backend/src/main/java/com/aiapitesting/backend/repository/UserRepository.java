package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.entity.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    long countByRole(UserRole role);
    Optional<User> findByGoogleId(String googleId);

    // Trang Admin - tìm user theo email (chứa, không phân biệt hoa/thường).
    Page<User> findByEmailContainingIgnoreCase(String email, Pageable pageable);
}
