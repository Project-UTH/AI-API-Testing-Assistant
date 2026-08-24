package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.AdminAuditEventResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.service.AdminAuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit-log")
@RequiredArgsConstructor
public class AdminAuditLogController {

    private final AdminAuditLogService adminAuditLogService;

    @GetMapping
    public PageResponse<AdminAuditEventResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return adminAuditLogService.list(pageable);
    }
}
