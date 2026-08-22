package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.AiUsageResponse;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.DashboardSummaryResponse;
import com.aiapitesting.backend.service.AiUsageService;
import com.aiapitesting.backend.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final AiUsageService aiUsageService;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<DashboardSummaryResponse>> summary() {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.getSummary()));
    }

    /** Usage token AI CỦA CHÍNH user đang đăng nhập, 90 ngày gần nhất, bucket sẵn theo ngày (Module 11). */
    @GetMapping("/ai-usage")
    public ResponseEntity<ApiResponse<AiUsageResponse>> aiUsage() {
        return ResponseEntity.ok(ApiResponse.of(aiUsageService.getMyUsage()));
    }
}
