package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.request.CreateApiTokenRequest;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.ApiTokenResponse;
import com.aiapitesting.backend.dto.response.CreatedApiTokenResponse;
import com.aiapitesting.backend.service.ci.ApiTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Module 17 (17d) - quản lý token API CI của project (dành cho chủ project, JWT). Token thô chỉ có trong phản hồi tạo. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-tokens")
@RequiredArgsConstructor
public class ApiTokenController {

    private final ApiTokenService apiTokenService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ApiTokenResponse>>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.of(apiTokenService.list(projectId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreatedApiTokenResponse>> create(
            @PathVariable UUID projectId, @Valid @RequestBody CreateApiTokenRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(apiTokenService.create(projectId, request)));
    }

    /** Thu hồi (mềm, idempotent). */
    @DeleteMapping("/{tokenId}")
    public ResponseEntity<ApiResponse<ApiTokenResponse>> revoke(@PathVariable UUID projectId, @PathVariable UUID tokenId) {
        return ResponseEntity.ok(ApiResponse.of(apiTokenService.revoke(projectId, tokenId)));
    }
}
