package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.request.AdminUserAiQuotaRequest;
import com.aiapitesting.backend.dto.request.AdminUserStatusRequest;
import com.aiapitesting.backend.dto.response.AdminUserResponse;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Bảo vệ bởi SecurityConfig ("/api/v1/admin/**" -> hasRole("ADMIN")), không cần check role thủ
 * công trong từng method. Cố ý KHÔNG có endpoint đổi role - xem AdminUserService.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public PageResponse<AdminUserResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) String search
    ) {
        return adminUserService.listUsers(pageable, search);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AdminUserResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.of(adminUserService.getUserDetail(id)));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<AdminUserResponse>> setStatus(
            @PathVariable UUID id,
            @RequestBody AdminUserStatusRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(adminUserService.setEnabled(id, request.enabled())));
    }

    @PutMapping("/{id}/ai-quota")
    public ResponseEntity<ApiResponse<AdminUserResponse>> setAiQuota(
            @PathVariable UUID id,
            @RequestBody AdminUserAiQuotaRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(adminUserService.setAiQuota(id, request.dailyTokenLimit())));
    }
}
