package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.AdminAuditEventResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.entity.AdminAuditEvent;
import com.aiapitesting.backend.repository.AdminAuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** Đọc nhật ký hành động nhạy cảm của admin (Module 11) - ghi ở AdminUserService.setEnabled(). */
@Service
@RequiredArgsConstructor
public class AdminAuditLogService {

    private final AdminAuditEventRepository adminAuditEventRepository;

    public PageResponse<AdminAuditEventResponse> list(Pageable pageable) {
        Page<AdminAuditEvent> page = adminAuditEventRepository.findAllByOrderByCreatedAtDesc(pageable);
        return PageResponse.from(page, AdminAuditEventResponse::from);
    }
}
