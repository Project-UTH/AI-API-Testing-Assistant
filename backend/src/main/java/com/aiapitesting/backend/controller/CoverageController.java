package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.request.CoverageFillRequest;
import com.aiapitesting.backend.dto.request.UpdateCoverageScopeRequest;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.CoverageFillResponse;
import com.aiapitesting.backend.dto.response.EndpointCoverageResponse;
import com.aiapitesting.backend.dto.response.ProjectCoverageResponse;
import com.aiapitesting.backend.service.CoverageGapFillService;
import com.aiapitesting.backend.service.EndpointCoverageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module 15c - Coverage Matrix + Risk Engine, tính tất định (không gọi AI). Module 15j - thêm bù khoảng
 * trống coverage (tất định, 0 token).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@RequiredArgsConstructor
public class CoverageController {

    private final EndpointCoverageService endpointCoverageService;
    private final CoverageGapFillService coverageGapFillService;

    @GetMapping("/coverage")
    public ResponseEntity<ApiResponse<ProjectCoverageResponse>> getProjectCoverage(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.of(endpointCoverageService.getProjectCoverage(projectId)));
    }

    @GetMapping("/endpoints/{endpointId}/coverage")
    public ResponseEntity<ApiResponse<EndpointCoverageResponse>> getEndpointCoverage(
            @PathVariable UUID projectId, @PathVariable UUID endpointId
    ) {
        return ResponseEntity.ok(ApiResponse.of(endpointCoverageService.getEndpointCoverage(projectId, endpointId)));
    }

    /** Chỉnh loại test case được đo coverage của endpoint (phải bật ít nhất 1 loại). */
    @PutMapping("/endpoints/{endpointId}/coverage/scope")
    public ResponseEntity<ApiResponse<EndpointCoverageResponse>> updateCoverageScope(
            @PathVariable UUID projectId, @PathVariable UUID endpointId,
            @RequestBody UpdateCoverageScopeRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(endpointCoverageService.updateScope(projectId, endpointId, request)));
    }

    /** itemKeys null/rỗng = bù tất cả mục còn thiếu, có truyền = chỉ bù đúng các mục đó. */
    @PostMapping("/endpoints/{endpointId}/coverage/fill")
    public ResponseEntity<ApiResponse<CoverageFillResponse>> fillCoverageGaps(
            @PathVariable UUID projectId, @PathVariable UUID endpointId,
            @RequestBody(required = false) CoverageFillRequest request
    ) {
        CoverageFillResponse response = coverageGapFillService.fill(projectId, endpointId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
