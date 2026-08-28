package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.PasswordResetOtp;
import com.aiapitesting.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface PasswordResetOtpRepository extends JpaRepository<PasswordResetOtp, UUID> {
    Optional<PasswordResetOtp> findTopByUserAndUsedFalseOrderByCreatedAtDesc(User user);

    // @Modifying bulk delete - tránh gotcha thứ tự flush Hibernate (INSERT luôn chạy trước DELETE
    // derived method mặc định) đã gặp ở TestCaseDependencyRepository (Module 6).
    @Modifying
    @Query("DELETE FROM PasswordResetOtp o WHERE o.user = :user AND o.used = false")
    void deleteAllUnusedByUser(User user);
}
