package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.AdminDashboardSummaryResponse;
import com.aiapitesting.backend.dto.response.AiUsageResponse;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.service.AdminDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<AdminDashboardSummaryResponse>> summary() {
        return ResponseEntity.ok(ApiResponse.of(adminDashboardService.getSystemSummary()));
    }

    /** Usage token AI TOÀN HỆ THỐNG (mọi user gộp lại) - khác /admin/users/{userId}/ai-usage (1 user). */
    @GetMapping("/ai-usage")
    public ResponseEntity<ApiResponse<AiUsageResponse>> aiUsage() {
        return ResponseEntity.ok(ApiResponse.of(adminDashboardService.getSystemAiUsage()));
    }
}
