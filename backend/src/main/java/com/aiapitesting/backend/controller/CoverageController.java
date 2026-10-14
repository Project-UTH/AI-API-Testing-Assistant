package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.EndpointCoverageResponse;
import com.aiapitesting.backend.dto.response.ProjectCoverageResponse;
import com.aiapitesting.backend.service.EndpointCoverageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Module 15c - Coverage Matrix + Risk Engine, chỉ đọc, tính tất định (không gọi AI). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@RequiredArgsConstructor
public class CoverageController {

    private final EndpointCoverageService endpointCoverageService;

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
}
