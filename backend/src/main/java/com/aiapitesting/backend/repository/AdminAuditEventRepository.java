package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.AdminAuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AdminAuditEventRepository extends JpaRepository<AdminAuditEvent, UUID> {
    Page<AdminAuditEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
