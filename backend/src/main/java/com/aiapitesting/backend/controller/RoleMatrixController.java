package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.request.RoleRulesRequest;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.BulkUpdateResponse;
import com.aiapitesting.backend.dto.response.RoleMatrixResponse;
import com.aiapitesting.backend.dto.response.RoleRulesPreviewResponse;
import com.aiapitesting.backend.service.RoleMatrixService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Module 17 (17a) - ma trận phân quyền (endpoint × vai trò). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/role-matrix")
@RequiredArgsConstructor
public class RoleMatrixController {

    private final RoleMatrixService roleMatrixService;

    @GetMapping
    public ResponseEntity<ApiResponse<RoleMatrixResponse>> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.of(roleMatrixService.get(projectId)));
    }

    /** Module 17 (S5) - xem trước số endpoint/ô mỗi quy tắc ảnh hưởng, KHÔNG ghi gì. */
    @PostMapping("/preview")
    public ResponseEntity<ApiResponse<RoleRulesPreviewResponse>> preview(@PathVariable UUID projectId, @RequestBody RoleRulesRequest request) {
        return ResponseEntity.ok(ApiResponse.of(roleMatrixService.preview(projectId, request)));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<BulkUpdateResponse>> update(@PathVariable UUID projectId, @RequestBody RoleRulesRequest request) {
        return ResponseEntity.ok(ApiResponse.of(roleMatrixService.update(projectId, request)));
    }
}
