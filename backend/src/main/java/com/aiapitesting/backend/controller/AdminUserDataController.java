package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.EndpointResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.dto.response.ProjectResponse;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.service.AdminUserDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Bảo vệ bởi SecurityConfig ("/api/v1/admin/**" -> hasRole("ADMIN")). CHỈ ĐỌC - xem
 * AdminUserDataService để biết lý do không tái dùng ProjectService/TestCaseService thường.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}")
@RequiredArgsConstructor
public class AdminUserDataController {

    private final AdminUserDataService adminUserDataService;

    @GetMapping("/projects")
    public PageResponse<ProjectResponse> listProjects(
            @PathVariable UUID userId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return adminUserDataService.listProjects(userId, pageable);
    }

    @GetMapping("/projects/{projectId}")
    public ResponseEntity<ApiResponse<ProjectResponse>> getProject(
            @PathVariable UUID userId, @PathVariable UUID projectId
    ) {
        return ResponseEntity.ok(ApiResponse.of(adminUserDataService.getProject(userId, projectId)));
    }

    @GetMapping("/projects/{projectId}/endpoints")
    public PageResponse<EndpointResponse> listEndpoints(
            @PathVariable UUID userId,
            @PathVariable UUID projectId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return adminUserDataService.listEndpoints(userId, projectId, pageable);
    }

    @GetMapping("/projects/{projectId}/test-cases")
    public ResponseEntity<ApiResponse<List<TestCaseResponse>>> listTestCases(
            @PathVariable UUID userId, @PathVariable UUID projectId
    ) {
        return ResponseEntity.ok(ApiResponse.of(adminUserDataService.listTestCases(userId, projectId)));
    }
}
