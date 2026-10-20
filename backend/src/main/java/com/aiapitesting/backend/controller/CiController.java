package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.request.CiRunRequest;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.CiExecutionResponse;
import com.aiapitesting.backend.dto.response.CiRunResponse;
import com.aiapitesting.backend.security.CiPrincipal;
import com.aiapitesting.backend.service.ci.CiRunService;
import com.aiapitesting.backend.service.report.ExecutionConclusionCalculator.FailOn;
import com.aiapitesting.backend.service.report.ExecutionReportService;
import com.aiapitesting.backend.service.report.ReportFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module 17 (17d) - API cho CI/CD, xác thực bằng header {@code X-Api-Token} (KHÔNG phải JWT). Chỉ ROLE_CI vào được (SecurityConfig).
 * Luồng điển hình: POST runs → poll GET executions/{id} đến khi conclusion khác PENDING → GET report?format=junit.
 */
@RestController
@RequestMapping("/api/v1/ci")
@RequiredArgsConstructor
public class CiController {

    private final CiRunService ciRunService;

    @PostMapping("/projects/{projectId}/runs")
    public ResponseEntity<ApiResponse<CiRunResponse>> run(
            @AuthenticationPrincipal CiPrincipal principal,
            @PathVariable UUID projectId,
            @RequestBody(required = false) CiRunRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(ciRunService.start(principal, projectId, request)));
    }

    @GetMapping("/projects/{projectId}/executions/{executionId}")
    public ResponseEntity<ApiResponse<CiExecutionResponse>> status(
            @AuthenticationPrincipal CiPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID executionId,
            @RequestParam(required = false) FailOn failOn
    ) {
        return ResponseEntity.ok(ApiResponse.of(ciRunService.status(principal, projectId, executionId, failOn)));
    }

    @GetMapping("/projects/{projectId}/executions/{executionId}/report")
    public ResponseEntity<byte[]> report(
            @AuthenticationPrincipal CiPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID executionId,
            @RequestParam(defaultValue = "junit") String format,
            @RequestParam(required = false) FailOn failOn
    ) {
        ExecutionReportService.ReportFile file = ciRunService.report(principal, projectId, executionId, ReportFormat.parse(format), failOn);
        return ResponseEntity.ok()
                .header("Content-Type", file.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + file.filename() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(file.content());
    }
}
